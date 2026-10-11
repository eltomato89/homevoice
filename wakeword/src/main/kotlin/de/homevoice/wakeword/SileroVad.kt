package de.homevoice.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD v4 (ONNX): probability that a 512-sample (32 ms) frame at 16 kHz contains speech.
 * Distinguishes speech from noise, music or clatter far better than an energy threshold.
 * Stateful and not thread-safe; feed consecutive frames from one thread.
 */
class SileroVad(model: ByteArray) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
    }
    private val session = env.createSession(model, options)
    private var h = FloatArray(STATE_SIZE)
    private var c = FloatArray(STATE_SIZE)
    private val input = FloatArray(FRAME)

    fun reset() {
        h = FloatArray(STATE_SIZE)
        c = FloatArray(STATE_SIZE)
    }

    /** Speech probability (0..1) for exactly [FRAME] samples of 16 kHz PCM16. */
    fun process(frame: ShortArray): Float {
        require(frame.size == FRAME) { "Silero VAD expects $FRAME samples" }
        for (i in 0 until FRAME) input[i] = frame[i] / 32768f
        val stateShape = longArrayOf(2, 1, 64)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, FRAME.toLong())).use { audio ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(SAMPLE_RATE)), longArrayOf()).use { sr ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(h), stateShape).use { hIn ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(c), stateShape).use { cIn ->
                        session.run(mapOf("input" to audio, "sr" to sr, "h" to hIn, "c" to cIn)).use { out ->
                            val prob = (out[0] as OnnxTensor).floatBuffer.get(0)
                            h = (out[1] as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                            c = (out[2] as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                            return prob
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        session.close()
        options.close()
    }

    companion object {
        const val FRAME = 512
        private const val SAMPLE_RATE = 16_000L
        private const val STATE_SIZE = 2 * 64
    }
}
