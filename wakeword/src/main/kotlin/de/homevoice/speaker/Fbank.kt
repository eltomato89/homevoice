package de.homevoice.speaker

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Kaldi-compatible log-mel filterbank (the features speaker models from sherpa-onnx expect):
 * 16 kHz, 25 ms frames every 10 ms, no edge snipping, DC removal, pre-emphasis 0.97,
 * Povey window, 512-point FFT power spectrum, 80 mel bins from 20 Hz to 7600 Hz, natural log.
 */
object Fbank {
    const val SAMPLE_RATE = 16_000
    const val NUM_BINS = 80
    private const val FRAME_LENGTH = 400
    private const val FRAME_SHIFT = 160
    private const val FFT_SIZE = 512
    private const val PREEMPH = 0.97f
    private const val LOW_FREQ = 20f
    private const val HIGH_FREQ = 7600f
    private const val EPS = 1.1920929e-7f

    private val window = FloatArray(FRAME_LENGTH) { i ->
        (0.5 - 0.5 * cos(2 * PI * i / (FRAME_LENGTH - 1))).pow(0.85).toFloat()
    }

    /** Triangular mel filters over FFT bins 0..255 (Kaldi leaves out the Nyquist bin). */
    private val melBanks: Array<FloatArray> = run {
        fun mel(f: Float) = 1127f * ln(1f + f / 700f)
        val numFftBins = FFT_SIZE / 2
        val binWidth = SAMPLE_RATE.toFloat() / FFT_SIZE
        val melLow = mel(LOW_FREQ)
        val melHigh = mel(HIGH_FREQ)
        val delta = (melHigh - melLow) / (NUM_BINS + 1)
        Array(NUM_BINS) { b ->
            val left = melLow + b * delta
            val center = left + delta
            val right = center + delta
            FloatArray(numFftBins) { k ->
                val m = mel(binWidth * k)
                when {
                    m > left && m <= center -> (m - left) / (center - left)
                    m > center && m < right -> (right - m) / (right - center)
                    else -> 0f
                }
            }
        }
    }

    /** [samples] are 16 kHz PCM values in int16 range. Returns [frames][80]. */
    fun compute(samples: FloatArray): Array<FloatArray> {
        val n = samples.size
        val numFrames = (n + FRAME_SHIFT / 2) / FRAME_SHIFT
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        val power = FloatArray(FFT_SIZE / 2)
        return Array(numFrames) { f ->
            // Centered frames with reflected edges (snip_edges = false).
            val start = f * FRAME_SHIFT + FRAME_SHIFT / 2 - FRAME_LENGTH / 2
            var mean = 0f
            for (j in 0 until FRAME_LENGTH) {
                var s = start + j
                if (s < 0) s = -s - 1
                if (s >= n) s = 2 * n - 1 - s
                re[j] = if (s in 0 until n) samples[s] else 0f
                mean += re[j]
            }
            mean /= FRAME_LENGTH
            for (j in 0 until FRAME_LENGTH) re[j] -= mean
            for (j in FRAME_LENGTH - 1 downTo 1) re[j] -= PREEMPH * re[j - 1]
            re[0] -= PREEMPH * re[0]
            for (j in 0 until FRAME_LENGTH) re[j] *= window[j]
            for (j in FRAME_LENGTH until FFT_SIZE) re[j] = 0f
            im.fill(0f)
            fft(re, im)
            for (k in power.indices) power[k] = re[k] * re[k] + im[k] * im[k]
            FloatArray(NUM_BINS) { b ->
                val bank = melBanks[b]
                var e = 0f
                for (k in bank.indices) if (bank[k] != 0f) e += bank[k] * power[k]
                ln(max(e, EPS))
            }
        }
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar
                    im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar
                    im[i + k] += ai
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
