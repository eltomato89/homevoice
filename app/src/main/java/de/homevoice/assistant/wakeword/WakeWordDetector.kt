package de.homevoice.assistant.wakeword

import android.content.Context
import android.util.Log
import de.homevoice.assistant.settings.CUSTOM_WAKE_MODEL
import de.homevoice.assistant.settings.Settings
import de.homevoice.wakeword.OpenWakeWord
import java.io.File

/** Local, offline wake word detection with openWakeWord (ONNX Runtime). */
class WakeWordDetector(private val context: Context) {
    private var detector: OpenWakeWord? = null

    /** Human-readable name of the active wake word, for the UI. */
    var keywordName: String = ""
        private set

    /** (Re)initializes the detector. Returns an error message or null on success. */
    @Synchronized
    fun init(settings: Settings): String? {
        release()
        return try {
            val (name, model) = if (settings.wakeModel == CUSTOM_WAKE_MODEL && settings.customWakeModelPath.isNotBlank()) {
                WakewordFiles.displayName(settings.customWakeModelPath) to File(settings.customWakeModelPath).readBytes()
            } else {
                val id = settings.wakeModel.takeIf { it in BUILTIN_MODELS } ?: DEFAULT_MODEL
                BUILTIN_MODELS.getValue(id) to asset("$id.onnx")
            }
            detector = OpenWakeWord(
                melspectrogramModel = asset("melspectrogram.onnx"),
                embeddingModel = asset("embedding_model.onnx"),
                wakeWordModel = model,
                threshold = settings.wakeThreshold,
                useXnnpack = true,
            )
            keywordName = name
            Log.i(TAG, "Wake word ready: $name (threshold ${settings.wakeThreshold})")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Wake word init failed", e)
            detector = null
            "Hotword-Fehler: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun asset(name: String): ByteArray = context.assets.open("wakeword/$name").use { it.readBytes() }

    /** Returns true when the wake word was detected. Called on the audio thread. */
    @Synchronized
    fun process(frame: ShortArray): Boolean {
        val d = detector ?: return false
        return try {
            d.process(frame).also {
                val t = d.timings
                if (t[3] >= 750) { // every ~60 s
                    val g = d.gateStats
                    Log.d(TAG, "Wake word ms/chunk: mel=%.1f emb=%.1f wake=%.1f, quiet %d%%".format(
                        t[0] / 1e6 / t[3], t[1] / 1e6 / t[3], t[2] / 1e6 / t[3], 100 * g[1] / maxOf(1, g[0] + g[1])))
                    t.fill(0)
                    g.fill(0)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wake word processing failed", e)
            false
        }
    }

    /** Forget buffered audio, e.g. after a conversation, so old speech cannot trigger. */
    @Synchronized
    fun reset() {
        detector?.reset()
    }

    /** Highest score since the last call (0..1). */
    @Synchronized
    fun takePeakScore(): Float = detector?.takePeakScore() ?: 0f

    @Synchronized
    fun release() {
        detector?.close()
        detector = null
    }

    companion object {
        const val FRAME_LENGTH = 512
        const val DEFAULT_MODEL = "alexa"
        val BUILTIN_MODELS = linkedMapOf(
            "alexa" to "Alexa",
            "hey_jarvis" to "Hey Jarvis",
            "hey_mycroft" to "Hey Mycroft",
            "hey_rhasspy" to "Hey Rhasspy",
        )
        private const val TAG = "VoiceAssistant"
    }
}
