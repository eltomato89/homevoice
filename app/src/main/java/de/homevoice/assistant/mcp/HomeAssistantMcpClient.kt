package de.homevoice.assistant.mcp

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

data class McpTool(val name: String, val description: String, val inputSchema: JsonObject?)

data class McpCallResult(val text: String, val isError: Boolean)

class McpException(message: String) : IOException(message)

/**
 * Minimal MCP client (Streamable HTTP transport, JSON-RPC 2.0) for Home Assistant: either the
 * built-in "Model Context Protocol Server" (base URL + long-lived access token) or a secret
 * webhook URL such as the one of the HA-MCP integration (no token needed).
 */
class HomeAssistantMcpClient(
    private val http: OkHttpClient,
    baseUrl: String,
    private val token: String,
) {
    val endpoint: String = normalizeEndpoint(baseUrl)

    private val json = Json { ignoreUnknownKeys = true }
    private val ids = AtomicInteger(1)
    private val initLock = Mutex()
    @Volatile private var sessionId: String? = null
    @Volatile private var initialized = false

    suspend fun listTools(): List<McpTool> {
        val tools = mutableListOf<McpTool>()
        var cursor: String? = null
        do {
            val params = buildJsonObject { cursor?.let { put("cursor", it) } }
            val result = request("tools/list", params)
            (result["tools"] as? JsonArray)?.forEach { el ->
                val t = el as? JsonObject ?: return@forEach
                val name = (t["name"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                tools += McpTool(
                    name = name,
                    description = (t["description"] as? JsonPrimitive)?.contentOrNull ?: "",
                    inputSchema = t["inputSchema"] as? JsonObject,
                )
            }
            cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull
        } while (cursor != null)
        return tools
    }

    suspend fun callTool(name: String, arguments: JsonObject): McpCallResult {
        val result = request("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        })
        val text = (result["content"] as? JsonArray).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
                "text" -> (obj["text"] as? JsonPrimitive)?.contentOrNull
                "resource" -> (obj["resource"] as? JsonObject)?.get("text")?.let { (it as? JsonPrimitive)?.contentOrNull }
                else -> null
            }
        }.joinToString("\n")
        val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull == true
        return McpCallResult(text.ifEmpty { result["structuredContent"]?.toString() ?: "" }, isError)
    }

    private suspend fun request(method: String, params: JsonObject): JsonObject {
        ensureInitialized()
        return try {
            rpc(method, params)
        } catch (e: SessionExpired) {
            // Server dropped our session: initialize again once and retry.
            initialized = false
            sessionId = null
            ensureInitialized()
            rpc(method, params)
        }
    }

    private suspend fun ensureInitialized() = initLock.withLock {
        if (initialized) return@withLock
        rpc("initialize", buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "HomeVoice")
                put("version", "0.1.0")
            }
        })
        notify("notifications/initialized")
        initialized = true
    }

    private suspend fun notify(method: String) = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
        }.toString()
        execute(post(body)) { response ->
            if (!response.isSuccessful && response.code != 202) {
                throw McpException("$method fehlgeschlagen: HTTP ${response.code}")
            }
        }
    }

    private suspend fun rpc(method: String, params: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val id = ids.getAndIncrement()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()
        execute(post(body)) { response ->
            if (response.code == 404 && sessionId != null) throw SessionExpired()
            if (response.code == 401 || response.code == 403) {
                throw McpException(
                    if (token.isBlank()) "Home Assistant verlangt Anmeldung (HTTP ${response.code}) – Token oder Webhook-URL prüfen"
                    else "Home Assistant lehnt den Token ab (HTTP ${response.code})"
                )
            }
            if (!response.isSuccessful) throw McpException("$method fehlgeschlagen: HTTP ${response.code}")
            response.header("Mcp-Session-Id")?.let { sessionId = it }
            val message = readResponse(response, id)
            (message["error"] as? JsonObject)?.let { err ->
                throw McpException("MCP-Fehler: ${(err["message"] as? JsonPrimitive)?.contentOrNull ?: err}")
            }
            message["result"] as? JsonObject ?: JsonObject(emptyMap())
        }
    }

    /**
     * Runs the blocking HTTP call on the IO dispatcher and aborts the connection when the coroutine is
     * cancelled (e.g. by the tool timeout). Without this a server that keeps an event stream open
     * would block the caller forever, because cancellation cannot interrupt a blocking read.
     */
    private suspend fun <T> execute(request: Request, block: (Response) -> T): T = coroutineScope {
        val call = http.newCall(request)
        val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            withContext(Dispatchers.IO) { call.execute().use(block) }
        } catch (e: IOException) {
            ensureActive() // report a cancellation/timeout as such, not as "socket closed"
            throw e
        } finally {
            canceller.cancel()
        }
    }

    /** Reads either a plain JSON body or an SSE stream and returns the JSON-RPC response with [id]. */
    private fun readResponse(response: Response, id: Int): JsonObject {
        val body = response.body ?: throw McpException("Leere Antwort")
        val contentType = response.header("Content-Type") ?: ""
        if (!contentType.startsWith("text/event-stream")) {
            return json.parseToJsonElement(body.string()).jsonObject
        }
        val source = body.source()
        val data = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart()).append('\n')
                line.isEmpty() && data.isNotEmpty() -> {
                    val message = parseMessage(data.toString())
                    data.clear()
                    if (message != null && message.matchesId(id)) return message
                }
            }
        }
        if (data.isNotEmpty()) parseMessage(data.toString())?.takeIf { it.matchesId(id) }?.let { return it }
        throw McpException("Keine Antwort im Event-Stream")
    }

    private fun parseMessage(text: String): JsonObject? =
        try { json.parseToJsonElement(text.trim()) as? JsonObject } catch (_: Exception) { null }

    private fun JsonObject.matchesId(id: Int): Boolean = (this["id"] as? JsonPrimitive)?.intOrNull == id

    private fun post(body: String): Request = Request.Builder()
        .url(endpoint)
        .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
        .header("Accept", "application/json, text/event-stream")
        .header("MCP-Protocol-Version", PROTOCOL_VERSION)
        .apply { sessionId?.let { header("Mcp-Session-Id", it) } }
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()

    private class SessionExpired : IOException()

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"

        /** A bare base URL gets the built-in /api/mcp path; any URL with a path is used as is. */
        fun normalizeEndpoint(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            val path = trimmed.substringAfter("://", trimmed).substringAfter('/', "")
            return if (path.isEmpty()) "$trimmed/api/mcp" else trimmed
        }
    }
}
