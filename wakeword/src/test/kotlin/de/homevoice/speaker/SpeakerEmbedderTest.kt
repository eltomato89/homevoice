package de.homevoice.speaker

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Enrolls synthetic German voices (macOS `say`) with two sentences and identifies them from a third.
 * Synthetic voices are only a sanity check for the pipeline; real voices are tuned on the device.
 */
class SpeakerEmbedderTest {
    private val modelsDir = File(System.getProperty("models.dir"))
    private val audioDir = File(System.getProperty("audio.dir")).apply { mkdirs() }
    private val voices = listOf("Anna", "Eddy (Deutsch (Deutschland))", "Flo (Deutsch (Deutschland))",
        "Reed (Deutsch (Deutschland))", "Sandy (Deutsch (Deutschland))", "Shelley (Deutsch (Deutschland))",
        "Grandpa (Deutsch (Deutschland))")
    private val enroll = listOf(
        "Hallo, ich lese jetzt ein paar Sätze vor, damit der Assistent meine Stimme kennenlernt.",
        "Heute ist das Wetter ziemlich wechselhaft, morgen soll es aber wieder sonniger werden.",
    )
    private val test = "Was steht heute in meinem Kalender?"

    private fun speech(voice: String, text: String): ShortArray {
        val file = File(audioDir, "spk_${voice.hashCode()}_${text.hashCode()}.wav")
        if (!file.exists()) {
            ProcessBuilder("say", "-v", voice, "-o", file.absolutePath, "--data-format=LEI16@16000", text).start().waitFor()
        }
        val bytes = file.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val size = buf.getInt(pos + 4)
            if (String(bytes, pos, 4) == "data") return ShortArray(size / 2).also { buf.position(pos + 8); buf.asShortBuffer().get(it) }
            pos += 8 + size + (size and 1)
        }
        error("no data")
    }

    private fun evaluate(modelFile: String): Pair<Float, Float> {
        SpeakerEmbedder(File(modelsDir, modelFile).readBytes()).use { e ->
            val profiles = voices.associateWith { v -> SpeakerEmbedder.average(enroll.map { e.embed(speech(v, it)) }) }
            var correct = 0
            var minSame = 1f
            var maxOther = -1f
            for (v in voices) {
                val probe = e.embed(speech(v, test))
                val scores = profiles.mapValues { SpeakerEmbedder.similarity(probe, it.value) }
                if (scores.maxBy { it.value }.key == v) correct++
                minSame = minOf(minSame, scores.getValue(v))
                maxOther = maxOf(maxOther, scores.filterKeys { it != v }.values.max())
            }
            println("$modelFile: $correct/${voices.size} korrekt, gleicher Sprecher min=$minSame, fremd max=$maxOther")
            return correct.toFloat() / voices.size to (minSame - maxOther)
        }
    }

    @Test
    fun identifiesSyntheticGermanVoices() {
        assumeTrue(File("/usr/bin/say").exists())
        val candidates = listOf("speaker_campplus_3dspeaker.onnx")
            .filter { File(modelsDir, it).exists() }
        assumeTrue(candidates.isNotEmpty())
        candidates.forEach { m ->
            val (accuracy, _) = evaluate(m)
            assertTrue("$m accuracy $accuracy", accuracy >= 0.85f)
        }
    }

    @Test
    fun fbankHasExpectedShape() {
        val feats = Fbank.compute(FloatArray(16_000) { (kotlin.math.sin(it * 0.1) * 3000).toFloat() })
        assertTrue(feats.size == 100 && feats.all { it.size == 80 && it.all { v -> v.isFinite() } })
    }
}
