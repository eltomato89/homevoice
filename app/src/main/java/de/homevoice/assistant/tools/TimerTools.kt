package de.homevoice.assistant.tools

import de.homevoice.assistant.timer.TimerEntry
import de.homevoice.assistant.timer.TimerManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Small builder helpers for Gemini FunctionDeclarations (OpenAPI schema subset). */
internal fun declaration(
    name: String,
    description: String,
    properties: Map<String, Pair<String, String>> = emptyMap(),
    required: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("name", name)
    put("description", description)
    if (properties.isNotEmpty()) {
        putJsonObject("parameters") {
            put("type", "OBJECT")
            putJsonObject("properties") {
                for ((prop, typeAndDescription) in properties) {
                    putJsonObject(prop) {
                        put("type", typeAndDescription.first)
                        put("description", typeAndDescription.second)
                    }
                }
            }
            if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        }
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.number(key: String): Double? =
    (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
internal fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

fun timerTools(timers: TimerManager): List<ToolHandler> {
    fun describe(t: TimerEntry) = buildJsonObject {
        put("id", t.id)
        t.label?.let { put("label", it) }
        put("remaining_seconds", timers.remainingSeconds(t))
        put("total_seconds", t.durationSeconds)
    }

    return listOf(
        ToolHandler(
            declaration(
                "set_timer",
                "Stellt einen Countdown-Timer. Für Uhrzeiten (Wecker) die Differenz bis zur Zielzeit in Sekunden berechnen.",
                mapOf(
                    "duration_seconds" to ("INTEGER" to "Dauer in Sekunden"),
                    "label" to ("STRING" to "Optionaler Name, z.B. 'Nudeln'"),
                ),
                required = listOf("duration_seconds"),
            )
        ) { args ->
            val seconds = args.number("duration_seconds")?.toLong()
                ?: throw IllegalArgumentException("duration_seconds fehlt")
            val entry = timers.add(seconds, args.string("label"))
            buildJsonObject {
                put("status", "gestellt")
                put("timer", describe(entry))
            }
        },
        ToolHandler(
            declaration(
                "cancel_timer",
                "Bricht laufende Timer ab – per id, per label oder alle. Ohne Angabe wird der einzige laufende Timer abgebrochen.",
                mapOf(
                    "id" to ("INTEGER" to "Id des Timers"),
                    "label" to ("STRING" to "Name des Timers"),
                    "all" to ("BOOLEAN" to "true, um alle Timer abzubrechen"),
                ),
            )
        ) { args ->
            val cancelled = timers.cancel(
                id = args.number("id")?.toInt(),
                label = args.string("label"),
                all = args.flag("all"),
            )
            buildJsonObject {
                put("cancelled", buildJsonArray { cancelled.forEach { add(describe(it)) } })
                if (cancelled.isEmpty()) {
                    put("hint", "Kein passender Timer. Laufende Timer: ${timers.timers.value.size}")
                }
            }
        },
        ToolHandler(
            declaration("list_timers", "Listet alle laufenden Timer mit Restzeit auf.")
        ) {
            buildJsonObject {
                put("timers", buildJsonArray { timers.timers.value.forEach { add(describe(it)) } })
                put("ringing", timers.ringing.value.size)
            }
        },
        ToolHandler(
            declaration("stop_alarm", "Stoppt einen gerade klingelnden Timer-Alarm.")
        ) {
            buildJsonObject { put("stopped", timers.stopRinging()) }
        },
    )
}

/** Tools that control the conversation itself. */
fun conversationTools(onEndConversation: () -> Unit): List<ToolHandler> = listOf(
    ToolHandler(
        declaration(
            "end_conversation",
            "Beendet das Gespräch und schaltet das Mikrofon ab, sobald die aktuelle Antwort gesprochen ist. " +
                "Immer aufrufen, wenn der Nutzer sich verabschiedet (z.B. 'danke, tschüss', 'das war's', 'stopp') oder fertig ist.",
        )
    ) {
        onEndConversation()
        buildJsonObject { put("status", "Gespräch wird beendet") }
    },
)
