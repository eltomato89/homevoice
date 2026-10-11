package de.homevoice.assistant.settings

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.AcousticEchoCanceler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.security.SecureRandom

const val DEFAULT_MODEL = "gemini-3.8-live"
const val DEFAULT_VOICE = "Kore"
const val CUSTOM_WAKE_MODEL = "custom"

val DEFAULT_SYSTEM_PROMPT = """
Du bist ein freundlicher Sprachassistent auf einem Smart Display in einem privaten Zuhause.
Antworte kurz, natürlich und gesprochen – keine Aufzählungen, kein Markdown.
Sprich Deutsch, außer der Nutzer spricht eine andere Sprache.
Nutze die Timer-Tools für Timer und Wecker. Nutze play_music, music_control und set_volume für Musik ("spiel …", "lauter", "Lautstärke 5", "stopp die Musik"). Nutze die Tools mit Präfix "ha_" um das Smart Home (Home Assistant) zu steuern oder abzufragen.
Bestätige ausgeführte Aktionen in einem kurzen Satz.
Wenn der Nutzer sich verabschiedet ("tschüss", "danke, das war's", "stopp", "gute Nacht"), sich ohne weitere Frage bedankt oder offensichtlich fertig ist: sag zuerst kurz "Tschüss!" (oder Ähnliches, höchstens zwei Wörter) und rufe direkt danach end_conversation auf. Sprich nach end_conversation nichts mehr.
""".trimIndent()

@Serializable
data class Settings(
    val geminiApiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val voice: String = DEFAULT_VOICE,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    /** Seconds of silence after the model finished speaking before the session is closed. */
    val followUpSeconds: Int = 5,
    /** Do not send microphone audio while the model is speaking (for devices with poor echo cancellation). */
    val halfDuplex: Boolean = false,
    /** Use communication audio mode so the platform echo canceller can work. */
    val communicationAudioMode: Boolean = true,
    /** Built-in openWakeWord model id (see WakeWordDetector.BUILTIN_MODELS) or CUSTOM_WAKE_MODEL. */
    val wakeModel: String = "alexa",
    /** Absolute path of an uploaded openWakeWord .onnx model, used when wakeModel == CUSTOM_WAKE_MODEL. */
    val customWakeModelPath: String = "",
    /** Detection threshold for the wake word score (0..1); lower = more sensitive. */
    val wakeThreshold: Float = 0.5f,
    /** Client ID of the user's own Spotify developer app (PKCE, no secret). */
    val spotifyClientId: String = "",
    /** Name of this device in Spotify Connect, if it differs from the model name. */
    val spotifyDeviceName: String = "",
    /** "spotify" or "youtube_music". */
    val defaultMusicService: String = "spotify",
    val haUrl: String = "",
    val haToken: String = "",
    val photoTreeUri: String = "",
    val slideIntervalSeconds: Int = 30,
    val showSeconds: Boolean = false,
    val nightModeEnabled: Boolean = false,
    val nightStartHour: Int = 22,
    val nightEndHour: Int = 7,
)

/** Small SharedPreferences-backed settings store exposing a [StateFlow]. */
class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current: Settings get() = _settings.value

    /** PIN protecting the settings web server; generated on first use. */
    val webPin: String = prefs.getString("webPin", null) ?: "%06d".format(SecureRandom().nextInt(1_000_000)).also {
        prefs.edit().putString("webPin", it).apply()
    }

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        save(next)
        _settings.value = next
    }

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            geminiApiKey = prefs.getString("geminiApiKey", d.geminiApiKey)!!,
            model = prefs.getString("model", d.model)!!,
            voice = prefs.getString("voice", d.voice)!!,
            systemPrompt = prefs.getString("systemPrompt", d.systemPrompt)!!,
            followUpSeconds = prefs.getInt("followUpSeconds", d.followUpSeconds),
            // Without hardware echo cancellation the model would hear (and interrupt) itself.
            halfDuplex = prefs.getBoolean("halfDuplex", !AcousticEchoCanceler.isAvailable()),
            communicationAudioMode = prefs.getBoolean("communicationAudioMode", d.communicationAudioMode),
            wakeModel = prefs.getString("wakeModel", d.wakeModel)!!,
            customWakeModelPath = prefs.getString("customWakeModelPath", d.customWakeModelPath)!!,
            wakeThreshold = prefs.getFloat("wakeThreshold", d.wakeThreshold),
            spotifyClientId = prefs.getString("spotifyClientId", d.spotifyClientId)!!,
            spotifyDeviceName = prefs.getString("spotifyDeviceName", d.spotifyDeviceName)!!,
            defaultMusicService = prefs.getString("defaultMusicService", d.defaultMusicService)!!,
            haUrl = prefs.getString("haUrl", d.haUrl)!!,
            haToken = prefs.getString("haToken", d.haToken)!!,
            photoTreeUri = prefs.getString("photoTreeUri", d.photoTreeUri)!!,
            slideIntervalSeconds = prefs.getInt("slideIntervalSeconds", d.slideIntervalSeconds),
            showSeconds = prefs.getBoolean("showSeconds", d.showSeconds),
            nightModeEnabled = prefs.getBoolean("nightModeEnabled", d.nightModeEnabled),
            nightStartHour = prefs.getInt("nightStartHour", d.nightStartHour),
            nightEndHour = prefs.getInt("nightEndHour", d.nightEndHour),
        )
    }

    private fun save(s: Settings) {
        prefs.edit()
            .putString("geminiApiKey", s.geminiApiKey)
            .putString("model", s.model)
            .putString("voice", s.voice)
            .putString("systemPrompt", s.systemPrompt)
            .putInt("followUpSeconds", s.followUpSeconds)
            .putBoolean("halfDuplex", s.halfDuplex)
            .putBoolean("communicationAudioMode", s.communicationAudioMode)
            .putString("wakeModel", s.wakeModel)
            .putString("customWakeModelPath", s.customWakeModelPath)
            .putFloat("wakeThreshold", s.wakeThreshold)
            .putString("spotifyClientId", s.spotifyClientId)
            .putString("spotifyDeviceName", s.spotifyDeviceName)
            .putString("defaultMusicService", s.defaultMusicService)
            .putString("haUrl", s.haUrl)
            .putString("haToken", s.haToken)
            .putString("photoTreeUri", s.photoTreeUri)
            .putInt("slideIntervalSeconds", s.slideIntervalSeconds)
            .putBoolean("showSeconds", s.showSeconds)
            .putBoolean("nightModeEnabled", s.nightModeEnabled)
            .putInt("nightStartHour", s.nightStartHour)
            .putInt("nightEndHour", s.nightEndHour)
            .apply()
    }
}
