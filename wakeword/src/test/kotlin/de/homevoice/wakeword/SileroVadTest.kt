package de.homevoice.wakeword

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

class SileroVadTest {
    private val modelsDir = File(System.getProperty("models.dir"))
    private val audioDir = File(System.getProperty("audio.dir")).apply { mkdirs() }

    private fun vad() = SileroVad(File(modelsDir, "silero_vad.onnx").readBytes())

    private fun speech(voice: String, text: String): ShortArray {
        val file = File(audioDir, "vad_${voice}_${text.hashCode()}.wav")
        if (!file.exists()) {
            ProcessBuilder("say", "-v", voice, "-o", file.absolutePath, "--data-format=LEI16@16000", text).start().waitFor()
        }
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val size = buf.getInt(pos + 4)
            if (String(bytes, pos, 4) == "data") {
                return ShortArray(size / 2).also { buf.position(pos + 8); buf.asShortBuffer().get(it) }
            }
            pos += 8 + size + (size and 1)
        }
        error("no data")
    }

    /** Fraction of 512-sample frames with speech probability above 0.5. */
    private fun speechRatio(audio: ShortArray): Float {
        vad().use { v ->
            val frames = audio.size / SileroVad.FRAME
            var speech = 0
            for (i in 0 until frames) {
                if (v.process(audio.copyOfRange(i * SileroVad.FRAME, (i + 1) * SileroVad.FRAME)) > 0.5f) speech++
            }
            return speech.toFloat() / frames
        }
    }

    @Test
    fun detectsGermanSpeechEvenWhenQuiet() {
        assumeTrue(File("/usr/bin/say").exists())
        val random = Random(2)
        val text = "Wie wird das Wetter morgen in Lippstadt? Und mach bitte das Licht im Wohnzimmer an."
        val loud = speechRatio(speech("Anna", text))
        val quiet = speechRatio(speech("Anna", text).map { (it * 0.08 + random.nextGaussian() * 15).toInt().toShort() }.toShortArray())
        println("speech ratio loud=$loud quiet+noise=$quiet")
        assertTrue(loud > 0.5f)
        assertTrue(quiet > 0.4f)
    }

    @Test
    fun ignoresNoiseAndSilence() {
        val random = Random(3)
        val white = ShortArray(16_000 * 5) { (random.nextGaussian() * 800).toInt().toShort() }
        // Clatter: short loud bursts.
        val clatter = ShortArray(16_000 * 5) { i -> if ((i / 1600) % 7 == 0) (random.nextGaussian() * 6000).toInt().toShort() else 0 }
        val silence = ShortArray(16_000 * 3)
        val ratios = listOf(white, clatter, silence).map { speechRatio(it) }
        println("speech ratio white=${ratios[0]} clatter=${ratios[1]} silence=${ratios[2]}")
        ratios.forEach { assertTrue("noise detected as speech: $it", it < 0.05f) }
    }
}
