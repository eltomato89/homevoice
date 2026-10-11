package de.homevoice.assistant

import android.app.Application
import de.homevoice.assistant.service.ConversationController
import de.homevoice.assistant.settings.SettingsRepository
import de.homevoice.assistant.slideshow.PhotoRepository
import de.homevoice.assistant.timer.AlarmPlayer
import de.homevoice.assistant.timer.AndroidAlarmScheduler
import de.homevoice.assistant.timer.PrefsTimerStore
import de.homevoice.assistant.timer.TimerManager
import de.homevoice.assistant.tools.ToolRegistry
import de.homevoice.assistant.tools.conversationTools
import de.homevoice.assistant.tools.timerTools
import de.homevoice.assistant.web.SettingsWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    val controller = ConversationController(
        context = app,
        settingsRepository = settings,
        timers = timerManager,
        tools = tools,
        wsHttp = wsHttp,
        restHttp = restHttp,
        scope = scope,
    )

    val photos = PhotoRepository(app)

    val webServer = SettingsWebServer(app, settings, photos, controller)

    init {
        tools.setGroup("conversation", conversationTools { controller.requestEnd() })
        tools.setGroup("timer", timerTools(timerManager))
    }
}
