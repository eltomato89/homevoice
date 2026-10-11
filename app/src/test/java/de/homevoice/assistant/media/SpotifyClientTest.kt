package de.homevoice.assistant.media

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class SpotifyClientTest {
    private val server = MockWebServer()
    private val requests = mutableListOf<Pair<String, String>>() // "METHOD path" to body
    private var tokens: SpotifyTokens? = null
    private var now = 1_000_000L
    private var expireNextApiCall = false
    private val routes = mutableMapOf<String, MockResponse>()

    private val store = object : SpotifyTokenStore {
        override fun load() = tokens
        override fun save(tokens: SpotifyTokens?) { this@SpotifyClientTest.tokens = tokens }
    }

    private val redirect = "http://127.0.0.1:8080/spotify/callback"

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val key = "${request.method} ${request.path}"
                requests += key to body
                if (request.path == "/accounts/api/token") {
                    val n = requests.count { it.first.endsWith("/api/token") }
                    return json("""{"access_token":"A$n","refresh_token":"R$n","expires_in":3600}""")
                }
                if (expireNextApiCall && request.getHeader("Authorization") == "Bearer A1") {
                    expireNextApiCall = false
                    return MockResponse().setResponseCode(401)
                }
                return routes.entries.firstOrNull { key.startsWith(it.key) }?.value ?: MockResponse().setResponseCode(204)
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(text: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(text)

    private fun client() = SpotifyClient(
        OkHttpClient(), { "CLIENT" }, store,
        apiBase = server.url("/v1").toString().trimEnd('/'),
        accountsBase = server.url("/accounts").toString().trimEnd('/'),
        now = { now },
    )

    private suspend fun connected(): SpotifyClient {
        val c = client()
        val auth = c.authorizationUrl(redirect).toHttpUrl()
        c.completeAuthorization("$redirect?code=CODE&state=${auth.queryParameter("state")}", redirect)
        return c
    }

    @Test
    fun authorizationUsesPkceAndExchangesCode() = runBlocking {
        val c = client()
        val auth = c.authorizationUrl(redirect).toHttpUrl()
        assertEquals("CLIENT", auth.queryParameter("client_id"))
        assertEquals("S256", auth.queryParameter("code_challenge_method"))
        assertEquals(redirect, auth.queryParameter("redirect_uri"))
        assertTrue(auth.queryParameter("scope")!!.contains("user-modify-playback-state"))

        c.completeAuthorization("$redirect?code=CODE&state=${auth.queryParameter("state")}", redirect)
        val form = requests.single { it.first.endsWith("/api/token") }.second
        assertTrue(form.contains("grant_type=authorization_code"))
        assertTrue(form.contains("code=CODE"))
        val verifier = Regex("code_verifier=([A-Za-z0-9]+)").find(form)!!.groupValues[1]
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        assertEquals(auth.queryParameter("code_challenge"), challenge)
        assertEquals("A1", tokens!!.accessToken)
        assertEquals("R1", tokens!!.refreshToken)
    }

    @Test
    fun rejectsWrongState() = runBlocking {
        val c = client()
        c.authorizationUrl(redirect)
        val error = runCatching { c.completeAuthorization("$redirect?code=X&state=WRONG", redirect) }.exceptionOrNull()
        assertTrue(error is SpotifyException)
        assertNull(tokens)
    }

    @Test
    fun refreshesExpiredTokenAndRetriesOn401() = runBlocking {
        val c = connected()
        routes["GET /v1/me"] = json("""{"id":"u1","display_name":"Jens"}""")
        expireNextApiCall = true
        assertEquals("Jens", c.displayName())
        assertEquals("A2", tokens!!.accessToken) // refreshed after 401
        now += 3_600_000 // expire
        c.displayName()
        assertEquals("A3", tokens!!.accessToken)
        assertTrue(requests.last { it.first.endsWith("/api/token") }.second.contains("grant_type=refresh_token"))
    }

    @Test
    fun resolvesArtistAndStartsPlaybackWithShuffle() = runBlocking {
        val c = connected()
        routes["GET /v1/search?type=artist"] = json(
            """{"artists":{"items":[null,{"name":"Queen","uri":"spotify:artist:q"}]}}"""
        )
        val playback = c.resolve("Queen", "artist")
        assertEquals("spotify:artist:q", playback.contextUri)
        assertEquals("Musik von Queen", playback.description)
        c.play("DEV", playback)
        val play = requests.single { it.first.startsWith("PUT /v1/me/player/play") }
        assertEquals("PUT /v1/me/player/play?device_id=DEV", play.first)
        assertEquals("spotify:artist:q", Json.parseToJsonElement(play.second).jsonObject["context_uri"]!!.jsonPrimitive.content)
        assertTrue(requests.any { it.first.startsWith("PUT /v1/me/player/shuffle?state=true") })
    }

    @Test
    fun prefersOwnPlaylistAndPlaysTracksAsUris() = runBlocking {
        val c = connected()
        routes["GET /v1/me/playlists"] = json(
            """{"items":[{"name":"Sommer 2026","uri":"spotify:playlist:own"}]}"""
        )
        assertEquals("spotify:playlist:own", c.resolve("sommer", "playlist").contextUri)

        routes["GET /v1/search?type=track"] = json(
            """{"tracks":{"items":[{"name":"Bohemian Rhapsody","uri":"spotify:track:b","artists":[{"name":"Queen"}]}]}}"""
        )
        val track = c.resolve("Bohemian Rhapsody", "track")
        assertEquals(listOf("spotify:track:b"), track.uris)
        c.play("DEV", track)
        val body = Json.parseToJsonElement(requests.last { it.first.startsWith("PUT /v1/me/player/play") }.second).jsonObject
        assertEquals("spotify:track:b", body["uris"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun explainsNoActiveDeviceAndPremium() = runBlocking {
        val c = connected()
        routes["PUT /v1/me/player/pause"] = MockResponse().setResponseCode(404)
            .setBody("""{"error":{"status":404,"message":"Player command failed: No active device found","reason":"NO_ACTIVE_DEVICE"}}""")
        val e = runCatching { c.pause() }.exceptionOrNull() as SpotifyException
        assertEquals("NO_ACTIVE_DEVICE", e.reason)
        assertEquals("Kein aktives Spotify-Gerät", e.message)
    }

    @Test
    fun parsesDevices() = runBlocking {
        val c = connected()
        routes["GET /v1/me/player/devices"] = json(
            """{"devices":[{"id":"d1","name":"Echo Show 5 (2nd Generation)","type":"Smartphone","is_active":false}]}"""
        )
        assertEquals(listOf(SpotifyDevice("d1", "Echo Show 5 (2nd Generation)", "Smartphone", false)), c.devices())
    }
}
