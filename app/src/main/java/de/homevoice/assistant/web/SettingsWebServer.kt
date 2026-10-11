package de.homevoice.assistant.web

import android.content.Context
import android.util.Log
import de.homevoice.assistant.media.MediaSessions
import de.homevoice.assistant.media.MusicPlayer
import de.homevoice.assistant.media.PrefsSpotifyTokenStore
import de.homevoice.assistant.media.SPOTIFY_PACKAGE
import de.homevoice.assistant.media.YOUTUBE_MUSIC_PACKAGE
import de.homevoice.assistant.service.ConversationController
import de.homevoice.assistant.settings.Settings
import de.homevoice.assistant.settings.SettingsRepository
import de.homevoice.assistant.slideshow.PhotoRepository
import de.homevoice.assistant.settings.CUSTOM_WAKE_MODEL
import de.homevoice.assistant.wakeword.WakeWordDetector
import de.homevoice.assistant.wakeword.WakewordFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Tiny HTTP server for configuring the device from a computer browser.
 * All /api calls require the PIN shown on the device (header X-Pin).
 */
class SettingsWebServer(
    private val context: Context,
    private val settings: SettingsRepository,
    private val photos: PhotoRepository,
    private val controller: ConversationController,
    private val music: MusicPlayer,
    private val mediaSessions: MediaSessions,
    private val spotifyTokens: PrefsSpotifyTokenStore,
    val port: Int = 8080,
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }
    private val workers = Executors.newFixedThreadPool(4)
    @Volatile private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        Thread({
            try {
                val socket = ServerSocket(port)
                server = socket
                Log.i(TAG, "Settings web server listening on ${url() ?: "port $port"}")
                while (!socket.isClosed) {
                    val client = socket.accept()
                    workers.execute { handle(client) }
                }
            } catch (e: Exception) {
                if (server?.isClosed != true) Log.e(TAG, "Web server stopped", e)
            }
        }, "SettingsWebServer").apply { isDaemon = true; start() }
    }

    /** Address to show on the device, e.g. http://192.168.1.20:8080 */
    fun url(): String? = localIpAddress()?.let { "http://$it:$port" }

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: InputStream,
        val contentLength: Long,
    ) {
        fun readBody(max: Int = 1_000_000): String {
            require(contentLength <= max) { "Anfrage zu groß" }
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var remaining = contentLength
            while (remaining > 0) {
                val n = body.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) break
                out.write(buffer, 0, n)
                remaining -= n
            }
            return out.toString("UTF-8")
        }
    }

    private class Response(val status: Int, val contentType: String, val body: ByteArray) {
        companion object {
            fun json(obj: JsonObject, status: Int = 200) =
                Response(status, "application/json; charset=utf-8", obj.toString().toByteArray())
            fun error(status: Int, message: String) = json(buildJsonObject { put("error", message) }, status)
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 60_000
                val input = BufferedInputStream(s.getInputStream())
                val request = parse(input) ?: return
                val response = try {
                    route(request)
                } catch (e: IllegalArgumentException) {
                    Response.error(400, e.message ?: "Ungültige Anfrage")
                } catch (e: Exception) {
                    Log.w(TAG, "Web request failed", e)
                    Response.error(500, e.message ?: e.javaClass.simpleName)
                }
                write(s.getOutputStream(), response)
            } catch (e: Exception) {
                Log.w(TAG, "Web connection failed", e)
            }
        }
    }

    private fun parse(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        return Request(parts[0].uppercase(), path, query, headers, BoundedInputStream(input, length), length)
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("UTF-8")
            if (b == '\n'.code) return out.toString("UTF-8").trimEnd('\r')
            out.write(b)
            if (out.size() > 16_384) throw IllegalArgumentException("Header zu lang")
        }
    }

    private fun write(out: OutputStream, response: Response) {
        val reason = when (response.status) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Error"
        }
        val head = "HTTP/1.1 ${response.status} $reason\r\n" +
            "Content-Type: ${response.contentType}\r\n" +
            "Content-Length: ${response.body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(response.body)
        out.flush()
    }

    private fun route(r: Request): Response {
        if (r.method == "GET" && (r.path == "/" || r.path == "/index.html")) {
            val html = context.assets.open("web/index.html").use { it.readBytes() }
            return Response(200, "text/html; charset=utf-8", html)
        }
        if (!r.path.startsWith("/api/")) return Response.error(404, "Nicht gefunden")
        if (r.headers["x-pin"] != settings.webPin) {
            Thread.sleep(1000) // slow down PIN guessing
            return Response.error(401, "Falsche PIN")
        }
        return when ("${r.method} ${r.path}") {
            "GET /api/settings" -> Response.json(state())
            "POST /api/settings" -> {
                updateSettings(json.parseToJsonElement(r.readBody()).jsonObject)
                Response.json(state())
            }
            "POST /api/wakeword" -> {
                val name = r.query["name"] ?: throw IllegalArgumentException("name fehlt")
                val path = WakewordFiles.save(context, name, r.body)
                    ?: throw IllegalArgumentException("Datei muss auf .onnx enden")
                settings.update { it.copy(wakeModel = CUSTOM_WAKE_MODEL, customWakeModelPath = path) }
                Response.json(state())
            }
            "POST /api/photos" -> {
                val name = r.query["name"] ?: throw IllegalArgumentException("name fehlt")
                if (!photos.saveUpload(name, r.body, MAX_PHOTO_BYTES)) {
                    throw IllegalArgumentException("Nur JPG, PNG, WebP, HEIC oder GIF")
                }
                Response.json(state())
            }
            "GET /api/photos" -> Response.json(buildJsonObject {
                put("photos", buildJsonArray {
                    photos.listUploads().forEach { (name, faces) ->
                        add(buildJsonObject { put("name", name); put("faces", faces) })
                    }
                })
            })
            "GET /api/photos/thumb" -> {
                val bytes = photos.thumbnail(r.query["name"] ?: throw IllegalArgumentException("name fehlt"))
                    ?: return Response.error(404, "Foto nicht gefunden")
                Response(200, "image/jpeg", bytes)
            }
            "POST /api/photos/delete-one" -> {
                if (!photos.deleteUpload(r.query["name"] ?: throw IllegalArgumentException("name fehlt"))) {
                    return Response.error(404, "Foto nicht gefunden")
                }
                Response.json(state())
            }
            "POST /api/photos/delete" -> {
                photos.deleteUploads()
                Response.json(state())
            }
            "POST /api/test-ha" -> {
                val message = runBlocking { withContext(Dispatchers.Main) { controller.refreshHomeAssistantTools() } }
                Response.json(buildJsonObject { put("result", message) })
            }
            "POST /api/spotify/authorize" ->
                Response.json(buildJsonObject { put("url", music.spotify.authorizationUrl(SPOTIFY_REDIRECT_URI)) })
            "POST /api/spotify/callback" -> {
                val url = (json.parseToJsonElement(r.readBody()).jsonObject["url"] as? JsonPrimitive)?.content
                    ?: throw IllegalArgumentException("url fehlt")
                runBlocking {
                    music.spotify.completeAuthorization(url, SPOTIFY_REDIRECT_URI)
                    spotifyTokens.userName = music.spotify.displayName()
                }
                Response.json(state())
            }
            "POST /api/spotify/disconnect" -> {
                music.spotify.disconnect()
                spotifyTokens.userName = null
                Response.json(state())
            }
            "POST /api/spotify/devices" -> {
                val devices = runBlocking { music.spotify.devices() }
                Response.json(buildJsonObject {
                    put("devices", devices.joinToString { "${it.name} (${it.type})" }.ifEmpty { "keine" })
                })
            }
            else -> Response.error(404, "Unbekannter Endpunkt")
        }
    }

    /** Merges the posted fields into the current settings. Empty secrets mean "unchanged", null clears. */
    private fun updateSettings(patch: JsonObject) {
        val current = json.encodeToJsonElement(Settings.serializer(), settings.current).jsonObject.toMutableMap()
        for ((key, value) in patch) {
            if (key !in current || key in READ_ONLY) continue
            if (key in SECRETS) {
                when {
                    value is JsonNull -> current[key] = JsonPrimitive("")
                    (value as? JsonPrimitive)?.content.isNullOrEmpty() -> Unit
                    else -> current[key] = JsonPrimitive((value as JsonPrimitive).content.trim())
                }
            } else if (value !is JsonNull) {
                current[key] = value
            }
        }
        val next = json.decodeFromJsonElement(Settings.serializer(), JsonObject(current))
        settings.update { next }
    }

    private fun state(): JsonObject {
        val s = settings.current
        val all = json.encodeToJsonElement(Settings.serializer(), s).jsonObject
        val status = controller.status.value
        return buildJsonObject {
            put("settings", JsonObject(all.filterKeys { it !in SECRETS }))
            put("wakeModels", buildJsonArray {
                val models = WakeWordDetector.BUILTIN_MODELS.toList() +
                    listOfNotNull(s.customWakeModelPath.takeIf { it.isNotBlank() }
                        ?.let { CUSTOM_WAKE_MODEL to WakewordFiles.displayName(it) })
                models.forEach { (id, name) -> add(buildJsonObject { put("id", id); put("name", name) }) }
            })
            putJsonObject("secretsSet") { SECRETS.forEach { key -> put(key, (all[key] as JsonPrimitive).content.isNotEmpty()) } }
            putJsonObject("status") {
                put("state", status.state.name)
                put("hotword", status.hotword)
                put("hotwordError", status.hotwordError)
                put("homeAssistant", status.homeAssistant)
                put("error", status.error)
                put("wakePeak", controller.wakePeak)
                put("spotifyConnected", music.spotify.isConnected)
                put("spotifyUser", spotifyTokens.userName)
                put("spotifyInstalled", music.isInstalled(SPOTIFY_PACKAGE))
                put("youtubeMusicInstalled", music.isInstalled(YOUTUBE_MUSIC_PACKAGE))
                put("mediaAccess", mediaSessions.hasAccess)
                put("overlayAllowed", android.provider.Settings.canDrawOverlays(context))
                put("redirectUri", SPOTIFY_REDIRECT_URI)
                put("uploadedPhotos", photos.uploadedCount())
            }
        }
    }

    /** Limits reads to the request body so a client cannot stall us by sending less than announced. */
    private class BoundedInputStream(private val source: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            return source.read().also { if (it >= 0) remaining-- }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = source.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }
    }

    companion object {
        private const val TAG = "VoiceAssistant"
        /** Spotify only allows plain HTTP for loopback addresses, so the user copies the final URL back. */
        const val SPOTIFY_REDIRECT_URI = "http://127.0.0.1:8080/spotify/callback"
        private const val MAX_PHOTO_BYTES = 40L * 1024 * 1024
        private val SECRETS = setOf("geminiApiKey", "haToken")
        private val READ_ONLY = setOf("customWakeModelPath", "photoTreeUri")

        fun localIpAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
