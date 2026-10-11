package de.homevoice.assistant.tools

import de.homevoice.assistant.tasks.ScheduledTask
import de.homevoice.assistant.tasks.TaskStore
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME_FORMAT = DateTimeFormatter.ofPattern("EEEE, d. MMMM, HH:mm 'Uhr'", Locale.GERMAN)

fun describe(task: ScheduledTask): String = when {
    task.isEvent -> (task.conditionDescription ?: "bei Ereignis") + if (task.repeatEvent) " (jedes Mal)" else " (einmal)"
    else -> {
        val at = task.runAt?.let { TIME_FORMAT.format(java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: "?"
        at + when (task.repeat) {
            "daily" -> ", täglich"
            "weekdays" -> ", werktags"
            "weekly" -> ", wöchentlich"
            else -> ""
        }
    }
}

/**
 * Tools to plan jobs. [speak] makes an announcement on the device (used by jobs, not in conversations);
 * [currentSpeaker] is stored as the creator.
 */
fun taskTools(store: TaskStore, currentSpeaker: () -> String?, speak: suspend (String) -> Unit): List<ToolHandler> = listOf(
    ToolHandler(
        declaration(
            "schedule_task",
            "Plant einen Job, den du später selbst ausführst: zu einer Uhrzeit (at, optional repeat) ODER bei einem " +
                "Ereignis (condition). prompt ist die Anweisung an dich zum Ausführungszeitpunkt, mit Person und Kanal, " +
                "z.B. 'Erinnere Jens per Durchsage daran, den Müll rauszubringen' oder 'Schicke Mona eine Push-Nachricht " +
                "aufs iPhone mit der Wettervorhersage'. Für Timer unter einer Stunde lieber set_timer verwenden.",
            mapOf(
                "prompt" to ("STRING" to "Anweisung zum Ausführungszeitpunkt"),
                "at" to ("STRING" to "Lokale Zeit ISO 8601, z.B. 2026-10-12T10:00"),
                "repeat" to ("STRING" to "none, daily, weekdays oder weekly"),
                "condition" to ("STRING" to "Jinja-Ausdruck für Home Assistant, der wahr wird, wenn der Job laufen soll, z.B. states('sensor.spulmaschine_energy_power')|float(0) < 5"),
                "condition_description" to ("STRING" to "Kurzbeschreibung des Ereignisses, z.B. 'wenn die Spülmaschine fertig ist'"),
                "every_time" to ("BOOLEAN" to "Bei Ereignissen: jedes Mal ausführen statt nur einmal"),
            ),
            required = listOf("prompt"),
        )
    ) { args ->
        val prompt = args.string("prompt")?.trim().orEmpty()
        require(prompt.isNotEmpty()) { "prompt fehlt" }
        val condition = args.string("condition")?.trim()?.takeIf { it.isNotEmpty() }
        val at = args.string("at")?.trim()?.takeIf { it.isNotEmpty() }
        require((condition == null) != (at == null)) { "Entweder at (Uhrzeit) oder condition (Ereignis) angeben" }
        val runAt = at?.let {
            val time = LocalDateTime.parse(it.removeSuffix("Z").substringBefore("+").take(19).let { s -> if (s.length == 16) "$s:00" else s })
            time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        if (runAt != null) require(runAt > System.currentTimeMillis() - 60_000) { "Der Zeitpunkt liegt in der Vergangenheit" }
        val repeat = args.string("repeat")?.takeIf { it in setOf("daily", "weekdays", "weekly") } ?: "none"
        val task = store.add { id, createdAt ->
            ScheduledTask(
                id = id, prompt = prompt, runAt = runAt, repeat = if (runAt != null) repeat else "none",
                condition = condition, conditionDescription = args.string("condition_description"),
                repeatEvent = args.flag("every_time"),
                // Start "true" so a condition that already holds does not fire right away.
                conditionWasTrue = condition != null,
                createdAt = createdAt, createdBy = currentSpeaker(),
            )
        }
        buildJsonObject {
            put("geplant", task.id)
            put("wann", describe(task))
            if (condition != null) put("hinweis", "Die Bedingung wird jede Minute geprüft; ist sie gerade schon erfüllt, wird erst die nächste Erfüllung gezählt.")
        }
    },
    ToolHandler(
        declaration("list_tasks", "Listet alle geplanten Jobs mit id und Zeitpunkt/Ereignis auf.")
    ) {
        buildJsonObject {
            put("jobs", buildJsonArray {
                store.tasks.value.forEach { t ->
                    add(buildJsonObject {
                        put("id", t.id); put("prompt", t.prompt); put("wann", describe(t))
                        t.createdBy?.let { put("von", it) }
                    })
                }
            })
        }
    },
    ToolHandler(
        declaration(
            "cancel_task",
            "Löscht einen geplanten Job (id aus list_tasks).",
            mapOf("id" to ("INTEGER" to "Id des Jobs")),
            required = listOf("id"),
        )
    ) { args ->
        val id = args.number("id")?.toInt() ?: throw IllegalArgumentException("id fehlt")
        val removed = store.remove(id) ?: throw IllegalArgumentException("Kein Job mit id $id")
        buildJsonObject { put("geloescht", removed.prompt) }
    },
    ToolHandler(
        declaration(
            "speak",
            "Nur in geplanten Jobs: spricht eine Durchsage am Gerät in der Küche. Im normalen Gespräch nicht verwenden – da sprichst du direkt.",
            mapOf("text" to ("STRING" to "Text der Durchsage")),
            required = listOf("text"),
        )
    ) { args ->
        val text = args.string("text")?.trim().orEmpty()
        require(text.isNotEmpty()) { "text fehlt" }
        speak(text)
        buildJsonObject { put("status", "Durchsage gestartet") }
    },
)
