package de.homevoice.assistant.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HomeAssistantMcpClientTest {
    private val server = MockWebServer()
    private val requests = mutableListOf<Pair<String, RecordedRequest>>()
    private var sse = false
    private var expireOnce = false

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                val method = body["method"]!!.jsonPrimitive.content
                requests += method to request
                val id = body["id"]?.jsonPrimitive?.content
                if (method == "tools/call" && expireOnce) {
                    expireOnce = false
                    return MockResponse().setResponseCode(404)
                }
                val result = when (method) {
                    "initialize" -> """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"home-assistant"}}"""
                    "notifications/initialized" -> return MockResponse().setResponseCode(202)
                    "tools/list" -> """{"tools":[{"name":"HassTurnOn","description":"Turns on","inputSchema":{"type":"object","properties":{"name":{"type":"string"}}}},{"name":"GetLiveContext","description":"Context","inputSchema":{"type":"object","properties":{}}}]}"""
                    "tools/call" -> """{"content":[{"type":"text","text":"{\"success\":true}"}],"isError":false}"""
                    else -> error("unexpected $method")
                }
                val message = """{"jsonrpc":"2.0","id":$id,"result":$result}"""
                val response = MockResponse().setHeader("Mcp-Session-Id", "sess-1")
                return if (sse) {
                    response.setHeader("Content-Type", "text/event-stream")
                        .setBody("event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\nevent: message\ndata: $message\n\n")
                } else {
                    response.setHeader("Content-Type", "application/json").setBody(message)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        // The hanging-stream test leaves a throttled response behind that cannot shut down cleanly.
        runCatching { server.shutdown() }
    }

    private fun client() = HomeAssistantMcpClient(OkHttpClient(), server.url("/").toString(), "TOKEN")

    @Test
    fun normalizesEndpoint() {
        assertEquals("http://ha:8123/api/mcp", HomeAssistantMcpClient.normalizeEndpoint("http://ha:8123/"))
        assertEquals("http://ha:8123/api/mcp", HomeAssistantMcpClient.normalizeEndpoint(" http://ha:8123/api/mcp "))
        assertEquals(
            "http://172.20.60.100:8123/api/webhook/mcp_abc",
            HomeAssistantMcpClient.normalizeEndpoint("http://172.20.60.100:8123/api/webhook/mcp_abc"),
        )
    }

    @Test
    fun initializesListsAndCallsWithJsonResponses() = runBlocking {
        val c = client()
        val tools = c.listTools()
        assertEquals(listOf("HassTurnOn", "GetLiveContext"), tools.map { it.name })
        val result = c.callTool("HassTurnOn", buildJsonObject { put("name", "Küche") })
        assertEquals("{\"success\":true}", result.text)
        assertFalse(result.isError)

        assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/call"), requests.map { it.first })
        assertTrue(requests.all { it.second.getHeader("Authorization") == "Bearer TOKEN" })
        assertEquals("/api/mcp", requests[0].second.path)
        assertEquals(null, requests[0].second.getHeader("Mcp-Session-Id"))
        assertEquals("sess-1", requests[2].second.getHeader("Mcp-Session-Id"))
    }

    @Test
    fun parsesSseResponses() = runBlocking {
        sse = true
        assertEquals(2, client().listTools().size)
    }

    @Test
    fun reinitializesWhenSessionExpired() = runBlocking {
        val c = client()
        c.listTools()
        expireOnce = true
        val result = c.callTool("HassTurnOn", buildJsonObject { })
        assertFalse(result.isError)
        assertEquals(
            listOf("initialize", "notifications/initialized", "tools/list", "tools/call", "initialize", "notifications/initialized", "tools/call"),
            requests.map { it.first },
        )
    }

    @Test
    fun webhookUrlWithoutTokenSendsNoAuthorization() = runBlocking {
        val c = HomeAssistantMcpClient(OkHttpClient(), server.url("/api/webhook/mcp_secret").toString(), "")
        c.listTools()
        assertTrue(requests.all { it.second.getHeader("Authorization") == null })
        assertEquals("/api/webhook/mcp_secret", requests[0].second.path)
    }

    @Test
    fun timeoutAbortsAHangingEventStream() = runBlocking {
        val c = client()
        c.listTools()
        // Server answers tools/call with an event stream that never delivers the result.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(": ping\n\n")
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.KEEP_OPEN)
                .setBodyDelay(0, java.util.concurrent.TimeUnit.SECONDS)
                .also { it.throttleBody(1, 60, java.util.concurrent.TimeUnit.SECONDS) }
        }
        val started = System.currentTimeMillis()
        val result = runCatching { kotlinx.coroutines.withTimeout(500) { c.callTool("HassTurnOn", buildJsonObject { }) } }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
        assertTrue("took ${System.currentTimeMillis() - started} ms", System.currentTimeMillis() - started < 3_000)
    }

    @Test
    fun rejectsBadToken() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401)
        }
        val error = runCatching { client().listTools() }.exceptionOrNull()
        assertTrue(error is McpException)
        assertTrue(error!!.message!!.contains("401"))
    }
}
