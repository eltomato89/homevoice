package de.homevoice.speaker

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Turns speech into a speaker embedding ("voice print") with a sherpa-onnx speaker model
 * (CAM++). Nothing leaves the device; only the vectors are stored.
 */
class SpeakerEmbedder(model: ByteArray) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(2)
        setSessionLogLevel(ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
    }
    private val session = env.createSession(model, options)
    private val inputName = session.inputNames.first()
    private val globalMean = session.metadata.customMetadata["feature_normalize_type"] == "global-mean"

    /** L2-normalized embedding of 16 kHz PCM16 speech (ideally ≥ 1.5 s, silence removed). */
    fun embed(pcm: ShortArray): FloatArray {
        val feats = Fbank.compute(FloatArray(pcm.size) { pcm[it].toFloat() })
        require(feats.size >= MIN_FRAMES) { "Zu wenig Sprache für eine Stimmerkennung" }
        if (globalMean) {
            val mean = FloatArray(Fbank.NUM_BINS)
            feats.forEach { f -> for (i in f.indices) mean[i] += f[i] }
            for (i in mean.indices) mean[i] /= feats.size
            feats.forEach { f -> for (i in f.indices) f[i] -= mean[i] }
        }
        val data = FloatBuffer.allocate(feats.size * Fbank.NUM_BINS)
        feats.forEach { data.put(it) }
        data.flip()
        OnnxTensor.createTensor(env, data, longArrayOf(1, feats.size.toLong(), Fbank.NUM_BINS.toLong())).use { input ->
            session.run(mapOf(inputName to input)).use { out ->
                val buf = (out[0] as OnnxTensor).floatBuffer
                return normalize(FloatArray(buf.remaining()).also { buf.get(it) })
            }
        }
    }

    override fun close() {
        session.close()
        options.close()
    }

    companion object {
        private const val MIN_FRAMES = 50 // 0.5 s

        fun normalize(v: FloatArray): FloatArray {
            val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-9f)
            return FloatArray(v.size) { v[it] / norm }
        }

        /** Cosine similarity of two normalized embeddings (−1..1, same speaker typically > 0.5). */
        fun similarity(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }

        /** Average of several normalized embeddings, normalized again (a person's profile). */
        fun average(embeddings: List<FloatArray>): FloatArray {
            val sum = FloatArray(embeddings.first().size)
            embeddings.forEach { e -> for (i in e.indices) sum[i] += e[i] }
            return normalize(sum)
        }
    }
}
