package de.homevoice.assistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Short synthesized feedback tones, so no audio assets are needed. */
class Earcons {
    enum class Tone { WAKE, END, ERROR }

    private val tones: Map<Tone, ShortArray> = mapOf(
        Tone.WAKE to sequence(listOf(660.0 to 0.07, 990.0 to 0.10)),
        Tone.END to sequence(listOf(880.0 to 0.07, 587.0 to 0.10)),
        Tone.ERROR to sequence(listOf(330.0 to 0.15, 247.0 to 0.25)),
    )

    fun play(tone: Tone) {
        val pcm = tones.getValue(tone)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(pcm, 0, pcm.size)
        track.setNotificationMarkerPosition(pcm.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) = t.release()
            override fun onPeriodicNotification(t: AudioTrack) {}
        })
        track.play()
    }

    private fun sequence(notes: List<Pair<Double, Double>>): ShortArray {
        val parts = notes.map { (freq, seconds) -> note(freq, seconds) }
        return ShortArray(parts.sumOf { it.size }).also { out ->
            var pos = 0
            for (p in parts) { p.copyInto(out, pos); pos += p.size }
        }
    }

    private fun note(freq: Double, seconds: Double): ShortArray {
        val n = (RATE * seconds).toInt()
        val fade = RATE / 100 // 10 ms fade in/out against clicks
        return ShortArray(n) { i ->
            val env = min(1.0, min(i, n - 1 - i).toDouble() / fade)
            (sin(2 * PI * freq * i / RATE) * env * 0.35 * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private companion object {
        const val RATE = 22_050
    }
}
