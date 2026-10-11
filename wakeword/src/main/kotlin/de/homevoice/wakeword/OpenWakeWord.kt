package de.homevoice.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Streaming openWakeWord detector (https://github.com/dscripka/openWakeWord), a port of the
 * reference Python pipeline:
 *
 *  16 kHz PCM ─(80 ms chunks)→ melspectrogram.onnx ─(76 frames × 32)→ embedding_model.onnx
 *  ─(N × 96 features)→ <wake word>.onnx → score 0..1
 *
 * Not thread-safe; feed audio from one thread.
 */
class OpenWakeWord(
    melspectrogramModel: ByteArray,
    embeddingModel: ByteArray,
    wakeWordModel: ByteArray,
    var threshold: Float = 0.5f,
    /** Use the XNNPACK execution provider (much faster on ARM; available in onnxruntime-android). */
    useXnnpack: Boolean = false,
    /**
     * Skip inference while the room is quiet and reuse the result of the last quiet window instead.
     * Cuts idle CPU to almost nothing; speech above the adaptive noise gate is processed normally.
     */
    private val silenceGate: Boolean = true,
) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
        if (useXnnpack) addXnnpack(mapOf("intra_op_num_threads" to "1"))
    }
    private val melSession = env.createSession(melspectrogramModel, options)
    private val embeddingSession = env.createSession(embeddingModel, options)
    private val wakeSession = env.createSession(wakeWordModel, options)

    private val melInput = melSession.inputNames.first()
    private val embeddingInput = embeddingSession.inputNames.first()
    private val wakeInput = wakeSession.inputNames.first()

    /** Number of embedding frames the wake word model looks at (16 for the stock models). */
    val featureFrames: Int = run {
        val info = wakeSession.inputInfo.values.first().info as ai.onnxruntime.TensorInfo
        info.shape.getOrNull(1)?.toInt()?.takeIf { it > 0 } ?: 16
    }

    // Raw audio history: one chunk plus the 3 hops of context the melspectrogram window needs.
    private val history = FloatArray(CHUNK + MEL_CONTEXT)
    private val pending = FloatArray(CHUNK)
    private var pendingCount = 0

    private val melFrames = ArrayDeque<FloatArray>()
    private val features = ArrayDeque<FloatArray>()

    // Silence gate state: adaptive noise floor and how many chunks ago the last loud chunk was.
    private var noiseFloor = 50f
    private var chunksSinceLoud = Int.MAX_VALUE / 2
    private var quietEmbedding: FloatArray? = null
    private var realMelFrames = 0
    private var quietChunks = 0

    /** Number of chunks processed vs. skipped by the silence gate (for diagnostics). */
    val gateStats = LongArray(2)

    /** Accumulated inference time per stage in nanoseconds (mel, embedding, wake word) and chunk count. */
    val timings = LongArray(4)


    /** Highest score seen since the last call to [takePeakScore]; useful to tune the threshold. */
    @Volatile private var peak = 0f

    init {
        reset()
    }

    /** Clears all state, e.g. after a detection so the same utterance does not trigger twice. */
    fun reset() {
        history.fill(0f)
        pendingCount = 0
        melFrames.clear()
        // The reference implementation starts with a buffer of ones.
        repeat(EMBEDDING_WINDOW) { melFrames.addLast(FloatArray(MEL_BINS) { 1f }) }
        features.clear()
        chunksSinceLoud = Int.MAX_VALUE / 2
        realMelFrames = 0
        quietEmbedding = null
    }

    fun takePeakScore(): Float = peak.also { peak = 0f }

    /**
     * Feeds 16 kHz mono PCM16 samples (any length). Returns true when the wake word was detected
     * in this audio; the detector then resets itself.
     */
    fun process(samples: ShortArray): Boolean {
        var detected = false
        var offset = 0
        while (offset < samples.size) {
            val n = minOf(CHUNK - pendingCount, samples.size - offset)
            for (i in 0 until n) pending[pendingCount + i] = samples[offset + i].toFloat()
            pendingCount += n
            offset += n
            if (pendingCount == CHUNK) {
                pendingCount = 0
                val score = processChunk()
                if (score > peak) peak = score
                if (score >= threshold) {
                    detected = true
                    reset()
                }
            }
        }
        return detected
    }

    /** Processes one 80 ms chunk; returns the wake word score (0 while buffers are still filling). */
    private fun processChunk(): Float {
        System.arraycopy(history, CHUNK, history, 0, MEL_CONTEXT)
        System.arraycopy(pending, 0, history, MEL_CONTEXT, CHUNK)

        if (silenceGate) updateGate(pending)
        // A window counts as quiet when no loud chunk is inside it (76 mel frames ≈ 10 chunks).
        // Only then are cached results reused, so pauses inside a word are always computed for real.
        val windowQuiet = silenceGate && chunksSinceLoud > EMBEDDING_CHUNKS
        gateStats[if (windowQuiet) 1 else 0]++
        // Recompute the cached quiet results now and then so they follow the room noise.
        if (windowQuiet && ++quietChunks % CACHE_REFRESH_CHUNKS == 0) {
            quietEmbedding = null
        }

        var t = System.nanoTime()
        // The melspectrogram is cheap and always computed for real: substituting it makes the
        // following embeddings see an unnaturally static noise floor and hurts detection.
        val mel = melspectrogram(history)
        realMelFrames += mel.size
        timings[0] += System.nanoTime() - t
        timings[3]++
        for (frame in mel) {
            melFrames.addLast(frame)
            if (melFrames.size > MAX_MEL_FRAMES) melFrames.removeFirst()
        }
        if (melFrames.size < EMBEDDING_WINDOW) return 0f

        t = System.nanoTime()
        val embedding = quietEmbedding.takeIf { windowQuiet }
            ?: embedding(melFrames.takeLast(EMBEDDING_WINDOW)).also {
                // Only cache once the window holds real audio, not the initial placeholder frames.
                if (windowQuiet && realMelFrames >= EMBEDDING_WINDOW) quietEmbedding = it
            }
        features.addLast(embedding)
        timings[1] += System.nanoTime() - t
        if (features.size > featureFrames) features.removeFirst()
        if (features.size < featureFrames) return 0f
        // Nothing but quiet windows in view: the score cannot rise.
        if (silenceGate && chunksSinceLoud > EMBEDDING_CHUNKS + featureFrames) return 0f

        t = System.nanoTime()
        return wakeScore(features).also { timings[2] += System.nanoTime() - t }
    }

    /** Updates the adaptive noise floor and the distance to the last chunk above the gate. */
    private fun updateGate(chunk: FloatArray) {
        var sum = 0.0
        for (v in chunk) sum += v * v
        val rms = kotlin.math.sqrt(sum / chunk.size).toFloat()
        // Follow the floor down quickly and up slowly, so speech does not raise it.
        noiseFloor += if (rms < noiseFloor) (rms - noiseFloor) * 0.2f else (rms - noiseFloor) * 0.002f
        val loud = rms > maxOf(MIN_GATE, noiseFloor * GATE_FACTOR)
        chunksSinceLoud = if (loud) 0 else chunksSinceLoud + 1
    }

    private fun melspectrogram(audio: FloatArray): List<FloatArray> {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong())).use { input ->
            melSession.run(mapOf(melInput to input)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                val frames = out.remaining() / MEL_BINS
                return List(frames) {
                    // Same scaling as openWakeWord's melspec_transform: x / 10 + 2
                    FloatArray(MEL_BINS) { out.get() / 10f + 2f }
                }
            }
        }
    }

    private fun embedding(window: List<FloatArray>): FloatArray {
        val data = FloatBuffer.allocate(EMBEDDING_WINDOW * MEL_BINS)
        window.forEach { data.put(it) }
        data.flip()
        val shape = longArrayOf(1, EMBEDDING_WINDOW.toLong(), MEL_BINS.toLong(), 1)
        OnnxTensor.createTensor(env, data, shape).use { input ->
            embeddingSession.run(mapOf(embeddingInput to input)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                return FloatArray(out.remaining()).also { out.get(it) }
            }
        }
    }

    private fun wakeScore(window: Collection<FloatArray>): Float {
        val dim = window.first().size
        val data = FloatBuffer.allocate(window.size * dim)
        window.forEach { data.put(it) }
        data.flip()
        OnnxTensor.createTensor(env, data, longArrayOf(1, window.size.toLong(), dim.toLong())).use { input ->
            wakeSession.run(mapOf(wakeInput to input)).use { result ->
                return (result[0] as OnnxTensor).floatBuffer.get(0)
            }
        }
    }

    override fun close() {
        melSession.close()
        embeddingSession.close()
        wakeSession.close()
        options.close()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 80 ms of audio, the step size of the reference implementation. */
        const val CHUNK = 1280
        private const val MEL_CONTEXT = 160 * 3
        private const val MEL_BINS = 32
        private const val EMBEDDING_WINDOW = 76
        private const val MAX_MEL_FRAMES = 120
        private const val EMBEDDING_CHUNKS = 10
        private const val MIN_GATE = 20f
        private const val GATE_FACTOR = 2f
        private const val CACHE_REFRESH_CHUNKS = 64
    }
}
