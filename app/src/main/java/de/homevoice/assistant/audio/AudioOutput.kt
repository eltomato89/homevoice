package de.homevoice.assistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Streaming PCM16 mono player for model audio (24 kHz). Supports instant flush on barge-in. */
class AudioOutput(private val sampleRate: Int) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = false
    @Volatile private var framesWritten = 0L
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private val lock = Object()

    /** True while audio is queued or still being played back by the hardware. */
    val isPlaying: Boolean
        get() {
            val t = track ?: return false
            if (queue.isNotEmpty()) return true
            return synchronized(lock) {
                (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < framesWritten
            }
        }

    fun start(communicationUsage: Boolean) {
        if (running) return
        val usage = if (communicationUsage) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate / 5 * 2)) // ~200 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        framesWritten = 0
        running = true
        t.play()
        thread = Thread({ writeLoop(t) }, "AudioOutput").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun writeLoop(t: AudioTrack) {
        while (running) {
            val chunk = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            synchronized(lock) {
                if (!running) return
                val n = t.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                if (n > 0) framesWritten += n / 2
            }
        }
    }

    fun enqueue(pcm: ByteArray) {
        if (running) queue.offer(pcm)
    }

    /** Drop everything that has not been played yet (model was interrupted). */
    fun flush() {
        queue.clear()
        val t = track ?: return
        synchronized(lock) {
            t.pause()
            t.flush()
            t.play()
            framesWritten = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        }
    }

    fun stop() {
        running = false
        queue.clear()
        thread?.join(500)
        thread = null
        track?.run {
            try { stop() } catch (_: IllegalStateException) {}
            release()
        }
        track = null
    }
}
