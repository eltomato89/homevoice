package de.homevoice.assistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * Single microphone reader shared by wake word detection and the Gemini stream.
 * Delivers 16 kHz mono PCM16 frames of [frameLength] samples to [onFrame] on a dedicated thread.
 */
class AudioInput(
    private val frameLength: Int,
    private val onFrame: (ShortArray) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private val effects = mutableListOf<AudioEffect>()

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission") // checked by the caller before start()
    fun start() {
        if (running) return
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            // ~1 s, so short pauses of the audio thread (e.g. speaker recognition at the wake word) lose nothing.
            maxOf(minBuffer, frameLength * 2 * 32),
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord konnte nicht initialisiert werden" }
        enableEffects(rec.audioSessionId)
        record = rec
        running = true
        rec.startRecording()
        thread = Thread({ readLoop(rec) }, "AudioInput").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun enableEffects(sessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(sessionId)?.let { it.enabled = true; effects += it }
        }
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(sessionId)?.let { it.enabled = true; effects += it }
        }
        Log.i(TAG, "AEC available=${AcousticEchoCanceler.isAvailable()} NS available=${NoiseSuppressor.isAvailable()}")
    }

    private fun readLoop(rec: AudioRecord) {
        while (running) {
            val frame = ShortArray(frameLength)
            var offset = 0
            while (offset < frameLength && running) {
                val n = rec.read(frame, offset, frameLength - offset)
                if (n < 0) {
                    Log.e(TAG, "AudioRecord.read failed: $n")
                    running = false
                    break
                }
                offset += n
            }
            if (offset == frameLength) {
                try {
                    onFrame(frame)
                } catch (t: Throwable) {
                    Log.e(TAG, "Frame handler failed", t)
                }
            }
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        effects.forEach { it.release() }
        effects.clear()
        record?.run {
            try { stop() } catch (_: IllegalStateException) {}
            release()
        }
        record = null
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val TAG = "VoiceAssistant"
    }
}

fun ShortArray.toLittleEndianBytes(): ByteArray {
    val out = ByteArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt()
        out[i * 2] = (v and 0xFF).toByte()
        out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
    }
    return out
}
