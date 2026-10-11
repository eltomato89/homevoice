package de.homevoice.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/** A face with coordinates normalized to 0..1 of the image width/height. */
data class Face(val left: Float, val top: Float, val right: Float, val bottom: Float, val score: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

/**
 * On-device face detection with UltraFace RFB-640 (MIT, github.com/Linzaer/Ultra-Light-Fast-Generic-Face-Detector-1MB).
 * Takes ARGB pixels so it works with Android bitmaps and desktop images alike.
 */
class FaceDetector(model: ByteArray) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(2)
        setSessionLogLevel(ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR) // model export warnings
    }
    private val session = env.createSession(model, options)
    private val inputName = session.inputNames.first()

    fun detect(argb: IntArray, width: Int, height: Int, minScore: Float = 0.7f): List<Face> {
        // Letterbox into the 640×480 input so portrait photos are not squashed.
        val scale = minOf(IN_W.toFloat() / width, IN_H.toFloat() / height)
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        val padX = (IN_W - w) / 2
        val padY = (IN_H - h) / 2
        val plane = IN_W * IN_H
        val input = FloatArray(3 * plane) // padding stays 0 = grey after normalization
        val step = 1f / scale
        for (y in 0 until h) {
            for (x in 0 until w) {
                // Average a 2×2 grid inside the source area to avoid aliasing when shrinking.
                var r = 0; var g = 0; var b = 0
                for (sy in 0..1) for (sx in 0..1) {
                    val px = ((x + 0.25f + sx * 0.5f) * step).toInt().coerceIn(0, width - 1)
                    val py = ((y + 0.25f + sy * 0.5f) * step).toInt().coerceIn(0, height - 1)
                    val c = argb[py * width + px]
                    r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                }
                val i = (y + padY) * IN_W + (x + padX)
                input[i] = (r / 4f - 127f) / 128f
                input[plane + i] = (g / 4f - 127f) / 128f
                input[2 * plane + i] = (b / 4f - 127f) / 128f
            }
        }
        val candidates = mutableListOf<Face>()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, IN_H.toLong(), IN_W.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { out ->
                val scores = (out[0] as OnnxTensor).floatBuffer
                val boxes = (out[1] as OnnxTensor).floatBuffer
                val n = scores.remaining() / 2
                for (k in 0 until n) {
                    val score = scores.get(k * 2 + 1)
                    if (score < minScore) continue
                    // Boxes are normalized to the 640×480 input; map back through the letterbox.
                    fun mx(v: Float) = ((v * IN_W - padX) / w).coerceIn(0f, 1f)
                    fun my(v: Float) = ((v * IN_H - padY) / h).coerceIn(0f, 1f)
                    val face = Face(mx(boxes.get(k * 4)), my(boxes.get(k * 4 + 1)), mx(boxes.get(k * 4 + 2)), my(boxes.get(k * 4 + 3)), score)
                    if (face.width > 0.005f && face.height > 0.005f) candidates += face
                }
            }
        }
        return nonMaxSuppression(candidates)
    }

    private fun nonMaxSuppression(faces: List<Face>, maxOverlap: Float = 0.3f): List<Face> {
        val kept = mutableListOf<Face>()
        for (f in faces.sortedByDescending { it.score }) {
            if (kept.none { iou(it, f) > maxOverlap }) kept += f
        }
        return kept
    }

    private fun iou(a: Face, b: Face): Float {
        val w = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)
        val h = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        val inter = w * h
        return inter / (a.width * a.height + b.width * b.height - inter)
    }

    override fun close() {
        session.close()
        options.close()
    }

    private companion object {
        const val IN_W = 640
        const val IN_H = 480
    }
}
