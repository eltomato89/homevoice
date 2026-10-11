package de.homevoice.assistant.gemini

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Gemini text model (generateContent) with an automatic tool loop, for jobs that run without
 * a conversation. The model's turns are sent back verbatim (Gemini 3 needs its thought signatures).
 */
class GeminiTextClient(
    private val http: OkHttpClient,
    private val apiKey: () -> String,
    private val model: () -> String,
    private val googleSearch: () -> Boolean = { false },
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
) {
    /**
     * Runs [prompt] until the model stops calling tools; [execute] runs each function call.
     * Returns the model's final text.
     */
    suspend fun run(
        systemInstruction: String,
        prompt: String,
        functionDeclarations: List<JsonObject>,
        execute: suspend (FunctionCall) -> JsonObject,
        maxSteps: Int = 8,
    ): String {
        val contents = mutableListOf(userText(prompt))
        repeat(maxSteps) {
            val content = generate(systemInstruction, contents, functionDeclarations)
            contents += content
            val parts = (content["parts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val calls = parts.mapNotNull { p ->
                (p["functionCall"] as? JsonObject)?.let { c ->
                    FunctionCall(
                        id = (c["id"] as? JsonPrimitive)?.contentOrNull ?: "",
                        name = (c["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                        args = c["args"] as? JsonObject ?: JsonObject(emptyMap()),
                    )
                }
            }
            if (calls.isEmpty()) {
                return parts.mapNotNull { (it["text"] as? JsonPrimitive)?.contentOrNull }.joinToString("").trim()
            }
            val responses = calls.map { it to execute(it) }
            contents += buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray {
                    responses.forEach { (call, response) ->
                        add(buildJsonObject {
                            putJsonObject("functionResponse") {
                                put("name", call.name)
                                if (call.id.isNotEmpty()) put("id", call.id)
                                put("response", response)
                            }
                        })
                    }
                })
            }
        }
        return "Abgebrochen: zu viele Schritte"
    }

    private fun userText(text: String) = buildJsonObject {
        put("role", "user")
        putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
    }

    private suspend fun generate(system: String, contents: List<JsonObject>, tools: List<JsonObject>): JsonObject =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
                put("contents", JsonArray(contents))
                val search = googleSearch()
                if (tools.isNotEmpty() || search) putJsonArray("tools") {
                    if (search) add(buildJsonObject { putJsonObject("googleSearch") {} })
                    if (tools.isNotEmpty()) add(buildJsonObject { put("functionDeclarations", JsonArray(tools)) })
                }
                // Built-in tools (search) together with our functions need this switch.
                if (search && tools.isNotEmpty()) putJsonObject("toolConfig") { put("includeServerSideToolInvocations", true) }
            }.toString()
            val name = model().trim().removePrefix("models/")
            val request = Request.Builder()
                .url("$baseUrl/models/$name:generateContent")
                .header("x-goog-api-key", apiKey().trim())
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IOException("Gemini-Fehler (HTTP ${r.code}): ${text.take(300)}")
                val root = LiveProtocol.json.parseToJsonElement(text).jsonObject
                val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
                    ?: throw IOException("Keine Antwort von Gemini: ${text.take(300)}")
                candidate["content"] as? JsonObject ?: buildJsonObject { put("role", "model"); putJsonArray("parts") {} }
            }
        }
}
