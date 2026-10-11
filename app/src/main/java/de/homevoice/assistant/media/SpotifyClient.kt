package de.homevoice.assistant.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class SpotifyTokens(val accessToken: String, val refreshToken: String, val expiresAtMillis: Long)

interface SpotifyTokenStore {
    fun load(): SpotifyTokens?
    fun save(tokens: SpotifyTokens?)
}

data class SpotifyDevice(val id: String, val name: String, val type: String, val isActive: Boolean)

/** What was started, for the model to announce. */
data class SpotifyPlayback(val description: String, val contextUri: String?, val uris: List<String>)

class SpotifyException(message: String, val status: Int = 0, val reason: String? = null) : IOException(message)

/**
 * Spotify Web API client using the Authorization Code flow with PKCE (no client secret).
 * Playback control requires Spotify Premium.
 */
class SpotifyClient(
    private val http: OkHttpClient,
    private val clientId: () -> String,
    private val store: SpotifyTokenStore,
    private val apiBase: String = "https://api.spotify.com/v1",
    private val accountsBase: String = "https://accounts.spotify.com",
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshLock = Mutex()
    private var pending: Pair<String, String>? = null // state to code verifier

    val isConnected: Boolean get() = store.load() != null

    // ---- OAuth ------------------------------------------------------------------------------

    /** Starts a new authorization and returns the URL to open in a browser. */
    fun authorizationUrl(redirectUri: String): String {
        val id = clientId().trim()
        if (id.isEmpty()) throw SpotifyException("Spotify Client-ID fehlt")
        val verifier = randomString(64)
        val state = randomString(16)
        pending = state to verifier
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        return "$accountsBase/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", id)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("state", state)
            .addQueryParameter("scope", SCOPES)
            .build().toString()
    }

    /** Finishes the authorization with the URL the browser was redirected to. */
    suspend fun completeAuthorization(callbackUrl: String, redirectUri: String) {
        val url = callbackUrl.trim().toHttpUrl()
        url.queryParameter("error")?.let { throw SpotifyException("Spotify hat die Anmeldung abgelehnt: $it") }
        val code = url.queryParameter("code") ?: throw SpotifyException("Kein code in der Adresse gefunden")
        val (state, verifier) = pending ?: throw SpotifyException("Keine laufende Anmeldung – bitte neu starten")
        if (url.queryParameter("state") != state) throw SpotifyException("Anmeldung passt nicht (state) – bitte neu starten")
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", clientId().trim())
            .add("code_verifier", verifier)
            .build()
        store.save(tokenRequest(body, previousRefreshToken = null))
        pending = null
    }

    fun disconnect() = store.save(null)

    private suspend fun tokenRequest(body: FormBody, previousRefreshToken: String?): SpotifyTokens =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url("$accountsBase/api/token").post(body).build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code == 400 && text.contains("invalid_grant")) store.save(null)
                    throw SpotifyException("Spotify-Anmeldung fehlgeschlagen (HTTP ${response.code}): $text", response.code)
                }
                val obj = json.parseToJsonElement(text).jsonObject
                SpotifyTokens(
                    accessToken = obj.str("access_token") ?: throw SpotifyException("Kein access_token"),
                    refreshToken = obj.str("refresh_token") ?: previousRefreshToken
                        ?: throw SpotifyException("Kein refresh_token"),
                    expiresAtMillis = now() + ((obj["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600L) * 1000,
                )
            }
        }

    private suspend fun accessToken(forceRefresh: Boolean = false): String = refreshLock.withLock {
        val tokens = store.load() ?: throw SpotifyException("Spotify ist nicht verbunden")
        if (!forceRefresh && tokens.expiresAtMillis - now() > 60_000) return@withLock tokens.accessToken
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", tokens.refreshToken)
            .add("client_id", clientId().trim())
            .build()
        tokenRequest(body, tokens.refreshToken).also { store.save(it) }.accessToken
    }

    // ---- Web API ----------------------------------------------------------------------------

    /** Calls the Web API; returns the JSON body or null for empty responses. Retries once after a refresh on 401. */
    private suspend fun api(method: String, path: String, body: JsonObject? = null): JsonObject? {
        var token = accessToken()
        repeat(2) { attempt ->
            val result = withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(apiBase + path)
                    .header("Authorization", "Bearer $token")
                    .method(
                        method,
                        when {
                            body != null -> body.toString().toRequestBody("application/json".toMediaType())
                            method == "GET" -> null
                            else -> ByteArray(0).toRequestBody(null)
                        },
                    )
                    .build()
                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    when {
                        response.code == 401 && attempt == 0 -> null to true
                        !response.isSuccessful -> {
                            val err = runCatching { json.parseToJsonElement(text).jsonObject["error"] as? JsonObject }.getOrNull()
                            val reason = err?.str("reason")
                            val message = err?.str("message") ?: text.ifBlank { "HTTP ${response.code}" }
                            throw SpotifyException(explain(response.code, reason, message), response.code, reason)
                        }
                        text.isBlank() -> null to false
                        else -> (runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()) to false
                    }
                }
            }
            if (!result.second) return result.first
            token = accessToken(forceRefresh = true)
        }
        throw SpotifyException("Spotify lehnt den Zugriff ab")
    }

    private fun explain(status: Int, reason: String?, message: String): String = when {
        reason == "NO_ACTIVE_DEVICE" -> "Kein aktives Spotify-Gerät"
        reason == "PREMIUM_REQUIRED" -> "Für die Wiedergabesteuerung ist Spotify Premium nötig"
        status == 403 && message.contains("user may not be registered", ignoreCase = true) ->
            "Dieses Spotify-Konto ist in der Entwickler-App nicht als Nutzer eingetragen"
        status == 429 -> "Spotify-Anfragelimit erreicht, bitte später erneut versuchen"
        else -> "Spotify-Fehler ($status): $message"
    }

    suspend fun displayName(): String {
        val me = api("GET", "/me") ?: return "?"
        return me.str("display_name") ?: me.str("id") ?: "?"
    }

    suspend fun devices(): List<SpotifyDevice> =
        (api("GET", "/me/player/devices")?.get("devices") as? JsonArray).orEmpty().mapNotNull { el ->
            val d = el as? JsonObject ?: return@mapNotNull null
            SpotifyDevice(
                id = d.str("id") ?: return@mapNotNull null,
                name = d.str("name") ?: "",
                type = d.str("type") ?: "",
                isActive = (d["is_active"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }

    /** Finds something to play for [query]. [type]: track, artist, album, playlist, liked_songs or any. */
    suspend fun resolve(query: String, type: String): SpotifyPlayback {
        val q = query.trim()
        return when (type) {
            "liked_songs" -> {
                val items = (api("GET", "/me/tracks?limit=50")?.get("items") as? JsonArray).orEmpty()
                val uris = items.mapNotNull { (it as? JsonObject)?.obj("track")?.str("uri") }.shuffled()
                if (uris.isEmpty()) throw SpotifyException("Keine Lieblingssongs gefunden")
                SpotifyPlayback("deine Lieblingssongs", null, uris)
            }
            "playlist" -> ownPlaylist(q) ?: searchFirst(q, "playlist")
            "artist", "album", "track" -> searchFirst(q, type)
            else -> searchFirst(q, "track")
        }
    }

    private suspend fun ownPlaylist(query: String): SpotifyPlayback? {
        val items = (api("GET", "/me/playlists?limit=50")?.get("items") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
        val match = items.firstOrNull { it.str("name").equals(query, ignoreCase = true) }
            ?: items.firstOrNull { it.str("name")?.contains(query, ignoreCase = true) == true }
            ?: return null
        return SpotifyPlayback("deine Playlist ${match.str("name")}", match.str("uri"), emptyList())
    }

    private suspend fun searchFirst(query: String, type: String): SpotifyPlayback {
        if (query.isEmpty()) throw SpotifyException("Suchbegriff fehlt")
        val path = "/search?type=$type&limit=5&q=" + java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val items = (api("GET", path)?.obj("${type}s")?.get("items") as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject } // search can contain null entries
        val item = items.firstOrNull() ?: throw SpotifyException("Nichts gefunden für \"$query\"")
        val name = item.str("name") ?: query
        val artists = (item["artists"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("name") }
        val uri = item.str("uri") ?: throw SpotifyException("Ungültiges Suchergebnis")
        return when (type) {
            "track" -> SpotifyPlayback("$name von ${artists.joinToString()}", null, listOf(uri))
            "artist" -> SpotifyPlayback("Musik von $name", uri, emptyList())
            "album" -> SpotifyPlayback("das Album $name von ${artists.joinToString()}", uri, emptyList())
            else -> SpotifyPlayback("die Playlist $name", uri, emptyList())
        }
    }

    suspend fun play(deviceId: String, playback: SpotifyPlayback) {
        val body = buildJsonObject {
            playback.contextUri?.let { put("context_uri", it) }
            if (playback.uris.isNotEmpty()) put("uris", buildJsonArray { playback.uris.forEach { add(JsonPrimitive(it)) } })
        }
        api("PUT", "/me/player/play?device_id=$deviceId", body)
        if (playback.contextUri?.startsWith("spotify:artist:") == true || playback.uris.size > 1) {
            runCatching { api("PUT", "/me/player/shuffle?state=true&device_id=$deviceId") }
        }
    }

    suspend fun pause() { api("PUT", "/me/player/pause") }
    suspend fun resume() { api("PUT", "/me/player/play") }
    suspend fun next() { api("POST", "/me/player/next") }
    suspend fun previous() { api("POST", "/me/player/previous") }

    private fun randomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        return String(CharArray(length) { chars[random.nextInt(chars.length)] })
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    companion object {
        const val SCOPES = "user-read-playback-state user-modify-playback-state user-read-currently-playing " +
            "user-library-read playlist-read-private playlist-read-collaborative"
    }
}
