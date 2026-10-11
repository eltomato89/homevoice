package de.homevoice.assistant.gemini

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

data class SessionConfig(
    val model: String,
    val voice: String,
    val systemInstruction: String,
    /** Gemini FunctionDeclaration objects (name, description, parameters). */
    val functionDeclarations: List<JsonObject>,
    val transcribe: Boolean = true,
    /** Gemini's built-in Google Search tool. */
    val googleSearch: Boolean = false,
)

data class FunctionCall(val id: String, val name: String, val args: JsonObject)

/** Events decoded from a BidiGenerateContentServerMessage. One message may yield several events. */
sealed interface ServerEvent {
    data object SetupComplete : ServerEvent
    class Audio(val pcm: ByteArray) : ServerEvent
    data class InputTranscript(val text: String) : ServerEvent
    data class OutputTranscript(val text: String) : ServerEvent
    data object Interrupted : ServerEvent
    data object GenerationComplete : ServerEvent
    data object TurnComplete : ServerEvent
    data class ToolCall(val calls: List<FunctionCall>) : ServerEvent
    data class ToolCallCancellation(val ids: List<String>) : ServerEvent
    data class GoAway(val timeLeft: String?) : ServerEvent
    data object VoiceActivity : ServerEvent
    data class Error(val message: String) : ServerEvent
}

object LiveProtocol {
    const val ENDPOINT =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    const val INPUT_SAMPLE_RATE = 16_000
    const val OUTPUT_SAMPLE_RATE = 24_000

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun setupMessage(config: SessionConfig): String = buildJsonObject {
        putJsonObject("setup") {
            put("model", if (config.model.startsWith("models/")) config.model else "models/${config.model}")
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) }
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") {
                        putJsonObject("prebuiltVoiceConfig") { put("voiceName", config.voice) }
                    }
                }
            }
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", config.systemInstruction) }) }
            }
            if (config.functionDeclarations.isNotEmpty() || config.googleSearch) {
                putJsonArray("tools") {
                    if (config.googleSearch) add(buildJsonObject { putJsonObject("googleSearch") {} })
                    if (config.functionDeclarations.isNotEmpty()) {
                        add(buildJsonObject { put("functionDeclarations", JsonArray(config.functionDeclarations)) })
                    }
                }
            }
            putJsonObject("contextWindowCompression") { putJsonObject("slidingWindow") {} }
            if (config.transcribe) {
                putJsonObject("inputAudioTranscription") {}
                putJsonObject("outputAudioTranscription") {}
            }
        }
    }.toString()

    fun audioMessage(pcm16le: ByteArray): String = buildJsonObject {
        putJsonObject("realtimeInput") {
            putJsonObject("audio") {
                put("data", Base64.getEncoder().encodeToString(pcm16le))
                put("mimeType", "audio/pcm;rate=$INPUT_SAMPLE_RATE")
            }
        }
    }.toString()

    /** Adds text to the conversation; with [turnComplete] = false the model does not answer yet. */
    fun contextMessage(text: String, turnComplete: Boolean = false): String = buildJsonObject {
        putJsonObject("clientContent") {
            put("turns", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
                })
            })
            put("turnComplete", turnComplete)
        }
    }.toString()

    fun audioStreamEndMessage(): String = buildJsonObject {
        putJsonObject("realtimeInput") { put("audioStreamEnd", true) }
    }.toString()

    fun toolResponseMessage(responses: List<Triple<String, String, JsonObject>>): String = buildJsonObject {
        putJsonObject("toolResponse") {
            put("functionResponses", buildJsonArray {
                for ((id, name, response) in responses) {
                    add(buildJsonObject {
                        put("id", id)
                        put("name", name)
                        put("response", response)
                    })
                }
            })
        }
    }.toString()

    fun parse(text: String): List<ServerEvent> {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return listOf(ServerEvent.Error("Ungültige Server-Nachricht: ${e.message}"))
        }
        val events = mutableListOf<ServerEvent>()

        if ("setupComplete" in root) events += ServerEvent.SetupComplete

        root.obj("serverContent")?.let { content ->
            content.obj("inputTranscription")?.str("text")?.takeIf { it.isNotEmpty() }
                ?.let { events += ServerEvent.InputTranscript(it) }
            content.obj("modelTurn")?.arr("parts")?.forEach { part ->
                val inline = (part as? JsonObject)?.obj("inlineData") ?: return@forEach
                val mime = inline.str("mimeType") ?: ""
                val data = inline.str("data") ?: return@forEach
                if (mime.startsWith("audio/")) events += ServerEvent.Audio(Base64.getDecoder().decode(data))
            }
            content.obj("outputTranscription")?.str("text")?.takeIf { it.isNotEmpty() }
                ?.let { events += ServerEvent.OutputTranscript(it) }
            if (content.bool("interrupted")) events += ServerEvent.Interrupted
            if (content.bool("generationComplete")) events += ServerEvent.GenerationComplete
            if (content.bool("turnComplete")) events += ServerEvent.TurnComplete
        }

        root.obj("toolCall")?.let { toolCall ->
            val calls = toolCall.arr("functionCalls")?.mapNotNull { el ->
                val call = el as? JsonObject ?: return@mapNotNull null
                FunctionCall(
                    id = call.str("id") ?: "",
                    name = call.str("name") ?: return@mapNotNull null,
                    args = call.obj("args") ?: JsonObject(emptyMap()),
                )
            }.orEmpty()
            if (calls.isNotEmpty()) events += ServerEvent.ToolCall(calls)
        }

        root.obj("toolCallCancellation")?.let { c ->
            events += ServerEvent.ToolCallCancellation(
                c.arr("ids")?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            )
        }

        root.obj("goAway")?.let { events += ServerEvent.GoAway(it.str("timeLeft")) }
        if ("voiceActivity" in root) events += ServerEvent.VoiceActivity

        root.obj("error")?.let { events += ServerEvent.Error(it.str("message") ?: it.toString()) }
        return events
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
}

/** Wraps an arbitrary tool result so it is always a JSON object, as required by FunctionResponse.response. */
fun JsonElement.asResponseObject(): JsonObject =
    this as? JsonObject ?: buildJsonObject { put("result", this@asResponseObject) }
