package de.homevoice.assistant.gemini

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiTextClientTest {
    @Test
    fun runsToolLoopAndReturnsSignatureVerbatim() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"speak","args":{"text":"Müll raus"},"id":"c1"},"thoughtSignature":"SIG"}]}}]}"""
        ))
        server.enqueue(MockResponse().setBody(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"Durchsage gemacht."}]}}]}"""
        ))
        server.start()
        val client = GeminiTextClient(OkHttpClient(), { "KEY" }, { "gemini-3.8-flash" }, baseUrl = server.url("/v1beta").toString().trimEnd('/'))
        val calls = mutableListOf<FunctionCall>()
        val result = client.run("system", "Job", emptyList(), { call ->
            calls += call
            buildJsonObject { put("status", "ok") }
        })
        assertEquals("Durchsage gemacht.", result)
        assertEquals("speak", calls.single().name)
        assertEquals(JsonPrimitive("Müll raus"), calls.single().args["text"])

        server.takeRequest()
        val second = server.takeRequest()
        assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", second.path)
        assertEquals("KEY", second.getHeader("x-goog-api-key"))
        val contents = Json.parseToJsonElement(second.body.readUtf8()).jsonObject["contents"]!!.jsonArray
        assertEquals(3, contents.size)
        assertEquals("SIG", contents[1].jsonObject["parts"]!!.jsonArray[0].jsonObject["thoughtSignature"]!!.jsonPrimitive.content)
        val response = contents[2].jsonObject["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject
        assertEquals("c1", response["id"]!!.jsonPrimitive.content)
        server.shutdown()
    }
}
