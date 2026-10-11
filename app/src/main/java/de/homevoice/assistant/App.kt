package de.homevoice.assistant

import android.app.Application
import android.content.Intent
import de.homevoice.assistant.media.MediaSessions
import de.homevoice.assistant.media.MusicPlayer
import de.homevoice.assistant.media.PrefsSpotifyTokenStore
import de.homevoice.assistant.media.SpotifyClient
import de.homevoice.assistant.media.YouTubeMusicSearch
import de.homevoice.assistant.service.ConversationController
import de.homevoice.assistant.settings.SettingsRepository
import de.homevoice.assistant.slideshow.PhotoRepository
import de.homevoice.assistant.speaker.SpeakerProfiles
import de.homevoice.assistant.speaker.SpeakerRecognizer
import de.homevoice.assistant.timer.AlarmPlayer
import de.homevoice.assistant.timer.AndroidAlarmScheduler
import de.homevoice.assistant.timer.PrefsTimerStore
import de.homevoice.assistant.timer.TimerManager
import de.homevoice.assistant.tools.ToolRegistry
import de.homevoice.assistant.tools.conversationTools
import de.homevoice.assistant.tools.musicTools
import de.homevoice.assistant.tools.radioTools
import de.homevoice.assistant.tools.speakerTools
import de.homevoice.assistant.tools.memoryTools
import de.homevoice.assistant.tools.taskTools
import de.homevoice.assistant.tasks.TaskRunner
import de.homevoice.assistant.tasks.TaskStore
import de.homevoice.assistant.gemini.GeminiTextClient
import de.homevoice.assistant.memory.MemoryStore
import de.homevoice.assistant.radio.RadioBrowser
import de.homevoice.assistant.radio.RadioPlayer
import de.homevoice.assistant.tools.timerTools
import de.homevoice.assistant.web.SettingsWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.timerManager.restore()
        container.webServer.start()
        container.scope.launch { container.photos.optimizeUploads() }
        container.taskRunner.start()
    }
}

/** Manual dependency wiring; everything here lives as long as the process. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings = SettingsRepository(app)

    private val baseHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    /** WebSocket client: no read timeout (server may be silent while tools run), keep-alive pings. */
    val wsHttp: OkHttpClient = baseHttp.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    val restHttp: OkHttpClient = baseHttp.newBuilder()
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS) // backstop for event streams that never finish
        .build()

    lateinit var timerManager: TimerManager
        private set

    init {
        timerManager = TimerManager(
            scheduler = AndroidAlarmScheduler(app),
            store = PrefsTimerStore(app),
            sound = AlarmPlayer(app) { timerManager.stopRinging() },
        )
    }

    val tools = ToolRegistry()

    val mediaSessions = MediaSessions(app)

    val radio = RadioPlayer(app)

    val radioBrowser = RadioBrowser(restHttp)

    val spotifyTokens = PrefsSpotifyTokenStore(app)

    val music = MusicPlayer(
        context = app,
        settings = settings,
        spotify = SpotifyClient(restHttp, { settings.current.spotifyClientId }, spotifyTokens),
        youTubeMusic = YouTubeMusicSearch(restHttp),
        sessions = mediaSessions,
        radio = radio,
        bringToFront = {
            app.startActivity(
                Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        },
    )

    val speakerProfiles = SpeakerProfiles(app)

    val memory = MemoryStore(java.io.File(app.filesDir, "memories.json"))

    val tasks = TaskStore(java.io.File(app.filesDir, "tasks.json"))

    val speakerRecognizer = SpeakerRecognizer(app, speakerProfiles)

    val controller = ConversationController(
        context = app,
        settingsRepository = settings,
        timers = timerManager,
        tools = tools,
        media = mediaSessions,
        profiles = speakerProfiles,
        speakers = speakerRecognizer,
        memory = memory,
        wsHttp = wsHttp,
        restHttp = restHttp,
        scope = scope,
    )

    val photos = PhotoRepository(app)

    /** Background jobs may think for a while: longer timeouts than for Home Assistant calls. */
    private val jobHttp: OkHttpClient = baseHttp.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    val taskRunner = TaskRunner(
        context = app,
        store = tasks,
        tools = tools,
        gemini = GeminiTextClient(jobHttp, { settings.current.geminiApiKey }, { settings.current.taskModel }, { settings.current.googleSearch }),
        scope = scope,
        baseInstruction = { controller.backgroundInstruction() },
        evaluate = { template -> controller.evaluateTemplate(template) },
    )

    val webServer = SettingsWebServer(app, settings, photos, controller, music, mediaSessions, spotifyTokens, radio, radioBrowser, speakerProfiles, memory, tasks)

    init {
        tools.setGroup("conversation", conversationTools { controller.requestEnd() })
        tools.setGroup("timer", timerTools(timerManager))
        tools.setGroup("music", musicTools(music, mediaSessions, settings, radio))
        tools.setGroup("radio", radioTools(radio, radioBrowser, settings))
        tools.setGroup("speaker", speakerTools(controller))
        tools.setGroup("memory", memoryTools(memory) { controller.currentSpeakerName() })
        tools.setGroup("tasks", taskTools(tasks, { controller.currentSpeakerName() }) { text ->
            kotlinx.coroutines.withContext(Dispatchers.Main) { controller.announce(text) }
        })
    }
}
