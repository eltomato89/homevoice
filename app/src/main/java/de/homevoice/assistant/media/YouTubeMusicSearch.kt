package de.homevoice.assistant.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Something YouTube Music can play via a music.youtube.com link. */
data class YouTubeMusicTarget(val title: String, val kind: String, val videoId: String?, val playlistId: String?) {
    /** A song starts its radio afterwards; playlists, albums and artist mixes play as a list. */
    val url: String
        get() = if (videoId != null) "https://music.youtube.com/watch?v=$videoId&list=RDAMVM$videoId"
        else "https://music.youtube.com/watch?list=$playlistId"
}

/**
 * Finds playable items through the search endpoint the YouTube Music web app uses (no API key).
 * Unofficial: if YouTube changes the format, playback falls back to the app's own search.
 */
class YouTubeMusicSearch(
    private val http: OkHttpClient,
    private val endpoint: String = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun find(query: String, type: String): YouTubeMusicTarget {
        if (type == "liked_songs") return YouTubeMusicTarget("deine Lieblingssongs", "playlist", null, "LM")
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20250101.01.00")
                    put("hl", "de")
                    put("gl", "DE")
                }
            }
            put("query", query)
        }.toString()
        val response = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(endpoint)
                .header("Origin", "https://music.youtube.com")
                .header("User-Agent", "Mozilla/5.0")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw IOException("YouTube-Music-Suche fehlgeschlagen (HTTP ${r.code})")
                r.body?.string().orEmpty()
            }
        }
        return select(parse(json.parseToJsonElement(response)), type)
            ?: throw IOException("Nichts gefunden für \"$query\"")
    }

    companion object {
        private val ARTIST = setOf("künstler", "artist")
        private val SONG = setOf("titel", "song")
        private val ALBUM = setOf("album", "single", "ep")
        private val PLAYLIST = setOf("playlist")

        /** All playable results in page order; the top-result card comes first. */
        fun parse(root: JsonElement): List<YouTubeMusicTarget> {
            val out = mutableListOf<YouTubeMusicTarget>()
            walk(root) { key, obj ->
                when (key) {
                    "musicCardShelfRenderer" -> {
                        val title = runs(obj.path("title")).firstOrNull() ?: return@walk
                        val kind = kind(obj.path("subtitle"))
                        val play = obj.path("thumbnailOverlay", "musicItemThumbnailOverlayRenderer", "content",
                            "musicPlayButtonRenderer", "playNavigationEndpoint")
                        // Artist cards have no play overlay but a "Zufallsmix" (shuffle) button first.
                        val buttons = (obj["buttons"] as? JsonArray).orEmpty()
                            .mapNotNull { (it as? JsonObject)?.path("buttonRenderer", "command") }
                        (listOfNotNull(play) + buttons).firstNotNullOfOrNull { target(title, kind, it) }?.let { out += it }
                    }
                    "musicResponsiveListItemRenderer" -> {
                        val columns = (obj["flexColumns"] as? JsonArray).orEmpty().mapNotNull {
                            (it as? JsonObject)?.path("musicResponsiveListItemFlexColumnRenderer", "text")
                        }
                        val title = columns.getOrNull(0)?.let { runs(it).firstOrNull() } ?: return@walk
                        val kind = columns.getOrNull(1)?.let { kind(it) } ?: ""
                        val play = obj.path("overlay", "musicItemThumbnailOverlayRenderer", "content",
                            "musicPlayButtonRenderer", "playNavigationEndpoint") ?: return@walk
                        target(title, kind, play)?.let { out += it }
                    }
                }
            }
            return out
        }

        /** Picks the best result for the requested type (artist, track, album, playlist, any). */
        fun select(results: List<YouTubeMusicTarget>, type: String): YouTubeMusicTarget? = when (type) {
            "artist" -> results.firstOrNull { it.kind in ARTIST && it.playlistId != null }
                ?: results.firstOrNull { it.kind in SONG }
            "track" -> results.firstOrNull { it.videoId != null && it.kind in SONG }
                ?: results.firstOrNull { it.videoId != null }
            "album" -> results.firstOrNull { it.kind in ALBUM && it.playlistId != null }
            "playlist" -> results.firstOrNull { it.kind in PLAYLIST && it.playlistId != null }
                ?: results.firstOrNull { it.playlistId != null }
            else -> results.firstOrNull()
        }

        private fun target(title: String, kind: String, endpoint: JsonObject): YouTubeMusicTarget? {
            (endpoint["watchEndpoint"] as? JsonObject)?.str("videoId")?.let {
                return YouTubeMusicTarget(title, kind, it, null)
            }
            (endpoint["watchPlaylistEndpoint"] as? JsonObject)?.str("playlistId")?.let {
                return YouTubeMusicTarget(title, kind, null, it)
            }
            return null
        }

        /** "Album • Queen • 1975" → "album" */
        private fun kind(text: JsonObject?): String =
            text?.let { runs(it).joinToString("") }?.substringBefore(" • ")?.trim()?.lowercase() ?: ""

        private fun runs(text: JsonObject?): List<String> =
            (text?.get("runs") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("text") }

        private fun walk(element: JsonElement, visit: (String, JsonObject) -> Unit) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (value is JsonObject) visit(key, value)
                    walk(value, visit)
                }
                is JsonArray -> element.forEach { walk(it, visit) }
                else -> Unit
            }
        }

        private fun JsonObject.path(vararg keys: String): JsonObject? {
            var current: JsonObject? = this
            for (k in keys) current = current?.get(k) as? JsonObject
            return current
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
