package de.homevoice.assistant.media

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings as AndroidSettings
import android.util.Log
import de.homevoice.assistant.settings.SettingsRepository
import kotlinx.coroutines.delay

const val SPOTIFY_PACKAGE = "com.spotify.music"
const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"

/**
 * Starts music on Spotify (Web API, precise) or YouTube Music (Android "play from search" intent)
 * and controls whatever is playing through the local media session.
 */
class MusicPlayer(
    private val context: Context,
    private val settings: SettingsRepository,
    val spotify: SpotifyClient,
    private val youTubeMusic: YouTubeMusicSearch,
    private val sessions: MediaSessions,
    private val radio: de.homevoice.assistant.radio.RadioPlayer,
    /** Brings the assistant UI back to the front after a music app was opened. */
    private val bringToFront: () -> Unit,
) {
    fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Starts playback and returns a short description of what is playing. */
    suspend fun play(query: String, type: String, service: String): String {
        // Starting Spotify/YouTube Music takes audio focus anyway; drop the radio so controls go there.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { radio.stop() }
        return when (service) {
            "youtube_music" -> {
                if (!isInstalled(YOUTUBE_MUSIC_PACKAGE)) throw IllegalStateException("YouTube Music ist nicht installiert")
                val target = try {
                    youTubeMusic.find(query, type)
                } catch (e: Exception) {
                    Log.w(TAG, "YouTube Music search failed, using the app's search", e)
                    null
                }
                if (target != null) {
                    openInApp(YOUTUBE_MUSIC_PACKAGE, target.url)
                    "Spiele ${target.title} auf YouTube Music"
                } else {
                    playFromSearch(YOUTUBE_MUSIC_PACKAGE, query, type)
                    "Suche nach \"$query\" in YouTube Music geöffnet"
                }
            }
            else -> {
                if (!isInstalled(SPOTIFY_PACKAGE)) throw IllegalStateException("Die Spotify-App ist nicht installiert")
                if (spotify.isConnected) {
                    val playback = spotify.resolve(query, type)
                    val device = findSpotifyDevice()
                    spotify.play(device.id, playback)
                    "Spiele ${playback.description} auf Spotify"
                } else {
                    // Without a linked account Spotify can still be asked to play a search result.
                    playFromSearch(SPOTIFY_PACKAGE, query, type)
                    "Suche nach \"$query\" auf Spotify gestartet (Konto nicht verbunden)"
                }
            }
        }
    }

    /** pause, resume, next, previous. Prefers the local media session, falls back to the Spotify API. */
    suspend fun control(action: String) {
        if (radio.isActive) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                when (action) {
                    "pause", "stop" -> radio.pause()
                    "resume", "play" -> radio.resume()
                    "toggle" -> if (radio.nowPlaying.value?.isPlaying == true) radio.pause() else radio.resume()
                    else -> throw IllegalArgumentException("Beim Radio gibt es kein nächstes/vorheriges Lied")
                }
            }
            return
        }
        if (sessions.control(action)) return
        if (!spotify.isConnected) throw IllegalStateException("Es läuft gerade keine Musik")
        when (action) {
            "pause", "stop" -> spotify.pause()
            "resume", "play" -> spotify.resume()
            "next" -> spotify.next()
            "previous" -> spotify.previous()
            else -> throw IllegalArgumentException("Unbekannte Aktion $action")
        }
    }

    /** The Spotify Connect device of this tablet; opens the Spotify app if it is not registered yet. */
    private suspend fun findSpotifyDevice(): SpotifyDevice {
        val names = listOfNotNull(
            settings.current.spotifyDeviceName.takeIf { it.isNotBlank() },
            Build.MODEL,
            AndroidSettings.Global.getString(context.contentResolver, "device_name"),
        ).map { it.trim().lowercase() }

        fun pick(devices: List<SpotifyDevice>): SpotifyDevice? =
            devices.firstOrNull { it.name.trim().lowercase() in names }
                ?: devices.singleOrNull { it.type.equals("Smartphone", true) || it.type.equals("Tablet", true) }

        pick(spotify.devices())?.let { return it }
        Log.i(TAG, "Spotify device not listed, opening the Spotify app")
        context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { context.startActivity(it) }
        try {
            repeat(15) {
                delay(1000)
                pick(spotify.devices())?.let { return it }
            }
        } finally {
            bringToFront()
        }
        val seen = spotify.devices().joinToString { "${it.name} (${it.type})" }.ifEmpty { "keine" }
        throw IllegalStateException("Spotify-Gerät nicht gefunden. Sichtbare Geräte: $seen")
    }

    /** Opens a deep link (e.g. music.youtube.com/watch?…) in the given app, which starts playback. */
    private suspend fun openInApp(pkg: String, url: String) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        delay(4000)
        bringToFront()
    }

    private suspend fun playFromSearch(pkg: String, query: String, type: String) {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(SearchManager.QUERY, query)
            when (type) {
                "artist" -> {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                    putExtra(MediaStore.EXTRA_MEDIA_ARTIST, query)
                }
                "album" -> {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE)
                    putExtra(MediaStore.EXTRA_MEDIA_ALBUM, query)
                }
                "playlist" -> {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE)
                    putExtra(MediaStore.EXTRA_MEDIA_PLAYLIST, query)
                }
                "track" -> {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Media.ENTRY_CONTENT_TYPE)
                    putExtra(MediaStore.EXTRA_MEDIA_TITLE, query)
                }
                else -> putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            }
        }
        context.startActivity(intent)
        // The music app comes to the front; return to the clock once playback had time to start.
        delay(4000)
        bringToFront()
    }

    private companion object {
        const val TAG = "VoiceAssistant"
    }
}
