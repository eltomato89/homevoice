package de.homevoice.assistant.radio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.icy.IcyInfo
import de.homevoice.assistant.media.NowPlaying
import de.homevoice.assistant.settings.RadioStation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays internet radio streams inside the app (Media3 ExoPlayer, no extra app needed).
 * Handles audio focus itself: ducks while the assistant talks, stops when Spotify starts.
 * All methods must be called on the main thread.
 */
class RadioPlayer(private val context: Context) {
    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var retries = 0

    private val _station = MutableStateFlow<RadioStation?>(null)
    val station: StateFlow<RadioStation?> = _station.asStateFlow()

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    /** Station and current song (from the stream's ICY metadata) for the widget. */
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private var songTitle: String? = null

    /** True when a station is loaded (playing or paused), so controls go to the radio. */
    val isActive: Boolean get() = _station.value != null

    private fun player(): ExoPlayer = player ?: ExoPlayer.Builder(context).build().also { p ->
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            /* handleAudioFocus = */ true,
        )
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = publish()

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) retries = 0
                publish()
            }

            override fun onMetadata(metadata: Metadata) {
                for (i in 0 until metadata.length()) {
                    (metadata[i] as? IcyInfo)?.title?.takeIf { it.isNotBlank() }?.let {
                        songTitle = it.trim()
                        publish()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Radio stream error: ${error.errorCodeName}", error)
                // Streams drop now and then: reconnect a few times before giving up.
                val s = _station.value ?: return
                if (retries++ < MAX_RETRIES) {
                    handler.postDelayed({ if (_station.value == s) start(s) }, RETRY_DELAY_MS * retries)
                } else {
                    stop()
                }
            }
        })
        player = p
    }

    fun play(station: RadioStation) {
        retries = 0
        start(station)
    }

    private fun start(station: RadioStation) {
        _station.value = station
        songTitle = null
        val p = player()
        p.setMediaItem(MediaItem.fromUri(station.url))
        p.prepare()
        p.play()
        publish()
        Log.i(TAG, "Radio: ${station.name} (${station.url})")
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        val p = player ?: return
        val s = _station.value ?: return
        // A live stream that was paused for long is stale: reconnect instead of resuming old data.
        if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) start(s) else p.play()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        player?.run { stop(); clearMediaItems() }
        _station.value = null
        songTitle = null
        publish()
    }

    private fun publish() {
        val s = _station.value
        _nowPlaying.value = s?.let {
            NowPlaying(
                title = songTitle ?: it.name,
                artist = if (songTitle != null) it.name else "Radio",
                artwork = null,
                isPlaying = player?.isPlaying == true || player?.playbackState == Player.STATE_BUFFERING,
                packageName = context.packageName,
            )
        }
    }

    private companion object {
        const val TAG = "VoiceAssistant"
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 2_000L
    }
}
