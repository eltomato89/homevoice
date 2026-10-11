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
import de.homevoice.assistant.timer.AlarmPlayer
import de.homevoice.assistant.timer.AndroidAlarmScheduler
import de.homevoice.assistant.timer.PrefsTimerStore
import de.homevoice.assistant.timer.TimerManager
import de.homevoice.assistant.tools.ToolRegistry
import de.homevoice.assistant.tools.conversationTools
import de.homevoice.assistant.tools.musicTools
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

    val spotifyTokens = PrefsSpotifyTokenStore(app)

    val music = MusicPlayer(
        context = app,
        settings = settings,
        spotify = SpotifyClient(restHttp, { settings.current.spotifyClientId }, spotifyTokens),
        youTubeMusic = YouTubeMusicSearch(restHttp),
        sessions = mediaSessions,
        bringToFront = {
            app.startActivity(
                Intent(app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        },
    )

    val controller = ConversationController(
        context = app,
        settingsRepository = settings,
        timers = timerManager,
        tools = tools,
        media = mediaSessions,
        wsHttp = wsHttp,
        restHttp = restHttp,
        scope = scope,
    )

    val photos = PhotoRepository(app)

    val webServer = SettingsWebServer(app, settings, photos, controller, music, mediaSessions, spotifyTokens)

    init {
        tools.setGroup("conversation", conversationTools { controller.requestEnd() })
        tools.setGroup("timer", timerTools(timerManager))
        tools.setGroup("music", musicTools(music, mediaSessions, settings))
    }
}
