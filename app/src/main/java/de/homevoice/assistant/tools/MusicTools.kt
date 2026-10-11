package de.homevoice.assistant.tools

import de.homevoice.assistant.media.MediaSessions
import de.homevoice.assistant.media.MusicPlayer
import de.homevoice.assistant.settings.SettingsRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun musicTools(
    player: MusicPlayer,
    sessions: MediaSessions,
    settings: SettingsRepository,
    radio: de.homevoice.assistant.radio.RadioPlayer,
): List<ToolHandler> = listOf(
    ToolHandler(
        declaration(
            "play_music",
            "Startet Musik. Für Künstler type=artist, für Lieder type=track, Alben type=album, Playlists type=playlist, " +
                "die gespeicherten Lieblingssongs des Nutzers type=liked_songs (query dann leer lassen). " +
                "service nur angeben, wenn der Nutzer ausdrücklich Spotify oder YouTube Music nennt.",
            mapOf(
                "query" to ("STRING" to "Suchbegriff, z.B. Künstlername, Liedtitel oder Playlist-Name"),
                "type" to ("STRING" to "track, artist, album, playlist, liked_songs oder any"),
                "service" to ("STRING" to "spotify oder youtube_music"),
            ),
            required = listOf("query"),
        )
    ) { args ->
        val service = args.string("service")?.takeIf { it.isNotBlank() } ?: settings.current.defaultMusicService
        val description = player.play(args.string("query").orEmpty(), args.string("type") ?: "any", service)
        buildJsonObject { put("status", description) }
    },
    ToolHandler(
        declaration(
            "music_control",
            "Steuert die laufende Musik: pause (auch für 'stopp'), resume, next, previous.",
            mapOf("action" to ("STRING" to "pause, resume, next oder previous")),
            required = listOf("action"),
        )
    ) { args ->
        val action = args.string("action") ?: throw IllegalArgumentException("action fehlt")
        player.control(action)
        buildJsonObject { put("status", "ok: $action") }
    },
    ToolHandler(
        declaration(
            "set_volume",
            "Ändert die Musiklautstärke. Entweder percent (0-100), level auf einer Skala von 1 bis 10, " +
                "oder change='up'/'down' für lauter/leiser.",
            mapOf(
                "percent" to ("INTEGER" to "Lautstärke in Prozent (0-100)"),
                "level" to ("INTEGER" to "Lautstärke von 1 bis 10"),
                "change" to ("STRING" to "up oder down"),
            ),
        )
    ) { args ->
        val current = sessions.volumePercent()
        val target = when {
            args.number("percent") != null -> args.number("percent")!!.toInt()
            args.number("level") != null -> args.number("level")!!.toInt().coerceIn(0, 10) * 10
            args.string("change") == "up" -> current + VOLUME_STEP
            args.string("change") == "down" -> current - VOLUME_STEP
            else -> throw IllegalArgumentException("percent, level oder change angeben")
        }
        buildJsonObject { put("volume_percent", sessions.setVolumePercent(target)) }
    },
    ToolHandler(
        declaration("get_now_playing", "Liefert den gerade laufenden Titel, Interpret und die Lautstärke.")
    ) {
        val now = radio.nowPlaying.value ?: sessions.nowPlaying.value
        buildJsonObject {
            if (now == null) put("playing", false) else {
                put("playing", now.isPlaying)
                put("title", now.title)
                put("artist", now.artist)
                put("app", now.packageName)
            }
            put("volume_percent", sessions.volumePercent())
        }
    },
)

private const val VOLUME_STEP = 15
