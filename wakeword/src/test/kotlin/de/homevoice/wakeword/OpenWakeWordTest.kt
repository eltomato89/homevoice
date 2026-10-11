package de.homevoice.wakeword

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * End-to-end check of the streaming pipeline with real speech synthesized by macOS `say`.
 * Skipped on machines without `say`.
 */
class OpenWakeWordTest {
    private val modelsDir = File(System.getProperty("models.dir"))
    private val audioDir = File(System.getProperty("audio.dir")).apply { mkdirs() }

    private fun detector(model: String, gate: Boolean = true) = OpenWakeWord(
        File(modelsDir, "melspectrogram.onnx").readBytes(),
        File(modelsDir, "embedding_model.onnx").readBytes(),
        File(modelsDir, "$model.onnx").readBytes(),
        silenceGate = gate,
    )

    /** Speech surrounded by 1.5 s of silence, as 16 kHz PCM16. */
    private fun speech(voice: String, text: String): ShortArray {
        val file = File(audioDir, "${voice}_${text.hashCode()}.wav")
        if (!file.exists()) {
            val p = ProcessBuilder("say", "-v", voice, "-o", file.absolutePath, "--data-format=LEI16@16000", text)
                .redirectErrorStream(true).start()
            p.waitFor()
            check(p.exitValue() == 0) { p.inputStream.bufferedReader().readText() }
        }
        val pcm = wavData(file.readBytes())
        val silence = ShortArray(24_000)
        return silence + pcm + silence
    }

    private fun wavData(bytes: ByteArray): ShortArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = buf.getInt(pos + 4)
            if (id == "data") {
                val out = ShortArray(size / 2)
                buf.position(pos + 8)
                buf.asShortBuffer().get(out)
                return out
            }
            pos += 8 + size + (size and 1)
        }
        error("no data chunk")
    }

    /** Feeds audio in 512-sample frames like the app does; returns (detected, peak score). */
    private fun run(model: String, audio: ShortArray, gate: Boolean = true): Pair<Boolean, Float> {
        detector(model, gate).use { d ->
            var detected = false
            var peak = 0f
            audio.toList().chunked(512).forEach { frame ->
                if (d.process(frame.toShortArray())) detected = true
                peak = maxOf(peak, d.takePeakScore())
            }
            return detected to peak
        }
    }

    private fun hasSay() = File("/usr/bin/say").exists()

    @Test
    fun detectsHeyJarvis() {
        assumeTrue(hasSay())
        for (voice in listOf("Samantha", "Daniel", "Karen")) {
            val (detected, peak) = run("hey_jarvis", speech(voice, "Hey Jarvis"))
            println("hey_jarvis / $voice: peak=$peak")
            assertTrue("hey_jarvis not detected for $voice (peak $peak)", detected)
        }
    }

    @Test
    fun detectsAlexa() {
        assumeTrue(hasSay())
        for (voice in listOf("Samantha", "Daniel", "Anna")) {
            val (detected, peak) = run("alexa", speech(voice, "Alexa"))
            println("alexa / $voice: peak=$peak")
            assertTrue("alexa not detected for $voice (peak $peak)", detected)
        }
    }

    @Test
    fun ignoresOtherSpeech() {
        assumeTrue(hasSay())
        val audio = speech("Samantha", "Good morning. What is the weather like today? Please turn on the lights in the kitchen.") +
            speech("Anna", "Guten Morgen, wie wird das Wetter heute? Mach bitte das Licht in der Küche an.")
        for (model in listOf("hey_jarvis", "alexa")) {
            val (detected, peak) = run(model, audio)
            println("$model / negative: peak=$peak")
            assertFalse("$model false positive (peak $peak)", detected)
        }
    }

    /**
     * Quiet, distant speech over room noise, like the Echo Show microphone delivers (noise RMS ~15).
     * The silence gate must not make detection worse than running the full pipeline.
     */
    @Test
    fun silenceGateDoesNotHurtQuietSpeech() {
        assumeTrue(hasSay())
        val random = java.util.Random(1)
        for (voice in listOf("Samantha", "Anna")) {
            val audio = speech(voice, "Alexa").map { (it * 0.08 + random.nextGaussian() * 15).toInt().toShort() }.toShortArray()
            val gated = run("alexa", audio).second
            val full = run("alexa", audio, gate = false).second
            println("alexa / $voice quiet+noise: peak=$gated (without gate: $full)")
            assertTrue("gate degrades detection for $voice: $gated vs $full", gated >= full - 0.1f)
        }
        assertTrue(run("alexa", speech("Samantha", "Alexa").map { (it * 0.08 + random.nextGaussian() * 15).toInt().toShort() }.toShortArray()).first)
    }

    @Test
    fun silenceScoresLow() {
        val (detected, peak) = run("alexa", ShortArray(16_000 * 5))
        println("alexa / silence: peak=$peak")
        assertFalse(detected)
    }
}
