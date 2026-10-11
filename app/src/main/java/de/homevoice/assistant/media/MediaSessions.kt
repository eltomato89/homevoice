package de.homevoice.assistant.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Only needed so the system lets us read other apps' media sessions (Spotify, YouTube Music).
 * Enable via Android settings → notification access, or `adb shell cmd notification allow_listener`.
 */
class MediaListenerService : NotificationListenerService()

data class NowPlaying(
    val title: String,
    val artist: String,
    val artwork: Bitmap?,
    val isPlaying: Boolean,
    val packageName: String,
)

/** Watches the active media sessions of music apps and controls playback and volume. */
class MediaSessions(private val context: Context) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val listener = ComponentName(context, MediaListenerService::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private var controllers: List<MediaController> = emptyList()
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private var started = false

    val hasAccess: Boolean
        get() = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Starts watching; safe to call repeatedly (e.g. after access was granted). */
    fun start() {
        if (started || !hasAccess) return
        try {
            manager.addOnActiveSessionsChangedListener({ update(it.orEmpty()) }, listener, handler)
            update(manager.getActiveSessions(listener))
            started = true
        } catch (e: SecurityException) {
            Log.w(TAG, "No access to media sessions", e)
        }
    }

    private fun update(active: List<MediaController>) {
        callbacks.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        callbacks.clear()
        controllers = active.filter { it.packageName != context.packageName }
        for (controller in controllers) {
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
                override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
                override fun onSessionDestroyed() = refresh()
            }
            controller.registerCallback(callback, handler)
            callbacks[controller] = callback
        }
        refresh()
    }

    /** The session to show and control: a playing one first, otherwise the most recent with metadata. */
    private fun current(): MediaController? =
        controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null }

    private fun refresh() {
        val c = current()
        val meta = c?.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        _nowPlaying.value = if (c == null || title == null) null else NowPlaying(
            title = title,
            artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "",
            artwork = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            isPlaying = c.playbackState?.state == PlaybackState.STATE_PLAYING,
            packageName = c.packageName,
        )
    }

    /** Returns false when no local media session can be controlled. */
    fun control(action: String): Boolean {
        val c = current() ?: return false
        val t = c.transportControls
        when (action) {
            "pause", "stop" -> t.pause()
            "resume", "play" -> t.play()
            "toggle" -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            "next" -> t.skipToNext()
            "previous" -> t.skipToPrevious()
            else -> return false
        }
        return true
    }

    // ---- Volume ---------------------------------------------------------------------------------

    private val maxVolume get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    fun volumePercent(): Int =
        (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / maxVolume).roundToInt()

    /** Sets the music volume (0..100 %) and returns the value actually set. */
    fun setVolumePercent(percent: Int): Int {
        val index = (percent.coerceIn(0, 100) / 100f * maxVolume).roundToInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        return volumePercent()
    }

    // ---- Ducking while the assistant talks -------------------------------------------------------

    private val duckRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setOnAudioFocusChangeListener { }
        .build()

    /** Asks music apps to lower their volume (they restore it when [unduck] is called). */
    fun duck() {
        audioManager.requestAudioFocus(duckRequest)
    }

    fun unduck() {
        audioManager.abandonAudioFocusRequest(duckRequest)
    }

    private companion object {
        const val TAG = "VoiceAssistant"
    }
}
