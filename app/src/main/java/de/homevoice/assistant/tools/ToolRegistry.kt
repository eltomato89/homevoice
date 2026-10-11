package de.homevoice.assistant.tools

import android.util.Log
import de.homevoice.assistant.gemini.asResponseObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

class ToolHandler(
    /** Gemini FunctionDeclaration: name, description and optional parameters (OpenAPI schema subset). */
    val declaration: JsonObject,
    val handler: suspend (args: JsonObject) -> JsonElement,
) {
    val name: String = (declaration["name"] as kotlinx.serialization.json.JsonPrimitive).content
}

/** Holds all tools offered to the model, grouped so a group (e.g. Home Assistant) can be replaced at once. */
class ToolRegistry {
    private val groups = ConcurrentHashMap<String, List<ToolHandler>>()

    fun setGroup(group: String, tools: List<ToolHandler>) {
        groups[group] = tools
    }

    fun groupSize(group: String): Int = groups[group]?.size ?: 0

    private fun all(): List<ToolHandler> = groups.entries.sortedBy { it.key }.flatMap { it.value }

    fun declarations(): List<JsonObject> = all().map { it.declaration }

    /** Declarations without the given tool names (e.g. job-only tools in a conversation). */
    fun declarations(excluding: Set<String>): List<JsonObject> =
        all().filterNot { it.name in excluding }.map { it.declaration }

    suspend fun execute(name: String, args: JsonObject, timeoutMs: Long = 20_000): JsonObject {
        val tool = all().firstOrNull { it.name == name }
            ?: return error("Unbekanntes Tool: $name")
        return try {
            withTimeout(timeoutMs) { tool.handler(args) }.asResponseObject()
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            error("Zeitüberschreitung beim Ausführen von $name")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("VoiceAssistant", "Tool $name failed", e)
            error(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun error(message: String) = buildJsonObject { put("error", message) }
}
