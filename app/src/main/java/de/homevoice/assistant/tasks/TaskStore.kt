package de.homevoice.assistant.tasks

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A job the assistant runs by itself: [prompt] is an instruction to the model at execution time,
 * e.g. "Erinnere Jens per Durchsage daran, den Müll rauszubringen".
 * Triggered at a time ([runAt], optionally repeating) or by an event ([condition]).
 */
@Serializable
data class ScheduledTask(
    val id: Int,
    val prompt: String,
    /** Next execution (epoch millis) for time-based jobs. */
    val runAt: Long? = null,
    /** none, daily, weekdays, weekly. */
    val repeat: String = "none",
    /** Jinja expression evaluated in Home Assistant every minute; the job runs when it becomes true. */
    val condition: String? = null,
    /** Human-readable trigger description, e.g. "wenn die Spülmaschine fertig ist". */
    val conditionDescription: String? = null,
    /** For event jobs: run every time the condition becomes true (otherwise once, then the job is removed). */
    val repeatEvent: Boolean = false,
    val conditionWasTrue: Boolean = false,
    val createdAt: Long,
    val createdBy: String? = null,
    val lastRunAt: Long? = null,
    val lastResult: String? = null,
) {
    val isEvent get() = condition != null
}

/** Next occurrence after [from] for a repeating job that last ran at [previous]. */
fun nextOccurrence(previous: Long, repeat: String, from: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
    if (repeat == "none") return null
    var t = LocalDateTime.ofInstant(Instant.ofEpochMilli(previous), zone)
    val limit = LocalDateTime.ofInstant(Instant.ofEpochMilli(from), zone)
    do {
        t = when (repeat) {
            "weekly" -> t.plusWeeks(1)
            "weekdays" -> {
                var n = t.plusDays(1)
                while (n.dayOfWeek == DayOfWeek.SATURDAY || n.dayOfWeek == DayOfWeek.SUNDAY) n = n.plusDays(1)
                n
            }
            else -> t.plusDays(1) // daily
        }
    } while (!t.isAfter(limit))
    return t.atZone(zone).toInstant().toEpochMilli()
}

class TaskStore(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = ListSerializer(ScheduledTask.serializer())

    private val _tasks = MutableStateFlow(load())
    val tasks: StateFlow<List<ScheduledTask>> = _tasks.asStateFlow()

    private fun load(): List<ScheduledTask> = try {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList()
    } catch (e: Exception) {
        Log.w("VoiceAssistant", "Tasks unreadable", e)
        emptyList()
    }

    @Synchronized
    fun update(transform: (List<ScheduledTask>) -> List<ScheduledTask>) {
        val next = transform(_tasks.value)
        file.writeText(json.encodeToString(serializer, next))
        _tasks.value = next
    }

    @Synchronized
    fun add(task: (id: Int, createdAt: Long) -> ScheduledTask): ScheduledTask {
        val created = task((_tasks.value.maxOfOrNull { it.id } ?: 0) + 1, now())
        update { it + created }
        return created
    }

    fun get(id: Int) = _tasks.value.firstOrNull { it.id == id }

    fun remove(id: Int): ScheduledTask? = get(id)?.also { t -> update { list -> list.filterNot { it.id == t.id } } }

    fun replace(task: ScheduledTask) = update { list -> list.map { if (it.id == task.id) task else it } }

    /** Earliest pending time-based run, for the system alarm. */
    fun nextRunAt(): Long? = _tasks.value.mapNotNull { it.runAt }.minOrNull()

    /** Time-based jobs that are due now. */
    fun due(at: Long = now()): List<ScheduledTask> = _tasks.value.filter { it.runAt != null && it.runAt <= at }

    /**
     * Records a run: one-off jobs are removed, repeating ones move to their next occurrence.
     * Returns the updated job (or null if it was removed).
     */
    @Synchronized
    fun completed(task: ScheduledTask, result: String): ScheduledTask? {
        val at = now()
        val next = when {
            task.isEvent -> if (task.repeatEvent) task.copy(lastRunAt = at, lastResult = result) else null
            else -> nextOccurrence(task.runAt ?: at, task.repeat, at)?.let { task.copy(runAt = it, lastRunAt = at, lastResult = result) }
        }
        update { list -> if (next == null) list.filterNot { it.id == task.id } else list.map { if (it.id == task.id) next else it } }
        return next
    }
}
