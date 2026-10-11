package de.homevoice.assistant.tasks

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.homevoice.assistant.App
import de.homevoice.assistant.gemini.GeminiTextClient
import de.homevoice.assistant.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Runs scheduled jobs: wakes up via the system alarm for time-based jobs, checks event conditions
 * in Home Assistant once a minute (local network only, no Gemini tokens), and executes due jobs
 * with the Gemini text model and the normal tools (plus "speak" for an announcement).
 */
class TaskRunner(
    private val context: Context,
    private val store: TaskStore,
    private val tools: ToolRegistry,
    private val gemini: GeminiTextClient,
    private val scope: CoroutineScope,
    /** Base context (date, household, home status, memory) for the model. */
    private val baseInstruction: () -> String,
    /** Renders a Jinja expression in Home Assistant; null when HA is not connected. */
    private val evaluate: suspend (String) -> String?,
) {
    private val running = Mutex()
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun start() {
        scheduleAlarm()
        scope.launch { store.tasks.collect { scheduleAlarm() } }
        scope.launch {
            while (isActive) {
                delay(EVENT_CHECK_MS)
                checkEvents()
            }
        }
        scope.launch { runDue() } // catch up after a restart
    }

    /** One system alarm for the earliest time-based job; the receiver calls [runDue]. */
    private fun scheduleAlarm() {
        val pending = PendingIntent.getBroadcast(
            context, ALARM_REQUEST, Intent(context, TaskAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = store.nextRunAt()
        if (at == null) alarmManager.cancel(pending)
        else alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    suspend fun runDue() {
        val now = System.currentTimeMillis()
        for (task in store.due(now)) {
            if (now - (task.runAt ?: now) > MAX_LATE_MS) {
                // Device was off for long: do not fire stale reminders, just move repeating jobs on.
                Log.i(TAG, "Skipping stale job ${task.id}")
                store.completed(task, "Übersprungen (Gerät war aus)")
            } else {
                execute(task)
            }
        }
    }

    private suspend fun checkEvents() {
        for (task in store.tasks.value.filter { it.isEvent }) {
            val raw = try {
                evaluate("{{ ((${task.condition})) | bool }}") ?: return // HA not connected
            } catch (e: Exception) {
                Log.w(TAG, "Condition of job ${task.id} failed: ${e.message}")
                continue
            }
            val isTrue = raw.trim().equals("true", ignoreCase = true)
            val current = store.get(task.id) ?: continue
            if (isTrue != current.conditionWasTrue) store.replace(current.copy(conditionWasTrue = isTrue))
            if (isTrue && !current.conditionWasTrue) execute(current.copy(conditionWasTrue = true))
        }
    }

    /** Executes a job now (also used by "Jetzt ausführen" in the web interface). Returns the result text. */
    suspend fun execute(task: ScheduledTask): String = running.withLock {
        Log.i(TAG, "Running job ${task.id}: ${task.prompt}")
        val result = try {
            withTimeout(RUN_TIMEOUT_MS) {
                gemini.run(
                    systemInstruction = instruction(task),
                    prompt = "Führe jetzt diesen geplanten Job aus: ${task.prompt}",
                    functionDeclarations = tools.declarations(excluding = EXCLUDED_TOOLS),
                    execute = { call -> tools.execute(call.name, call.args) },
                )
            }.ifBlank { "Erledigt" }
        } catch (e: Exception) {
            Log.w(TAG, "Job ${task.id} failed", e)
            "Fehler: ${e.message ?: e.javaClass.simpleName}"
        }
        Log.i(TAG, "Job ${task.id} result: $result")
        store.completed(task, result)
        result
    }

    private fun instruction(task: ScheduledTask): String = buildString {
        append("Du bist der Sprachassistent eines Haushalts und führst gerade einen geplanten Job aus – ")
        append("ohne laufendes Gespräch. Niemand kann dir antworten.\n")
        append("Für Durchsagen am Gerät in der Küche nutze speak (kurz, natürlich, mit Namen der Person). ")
        append("Für Nachrichten aufs Handy nutze den passenden notify-Dienst von Home Assistant über ha_call_service ")
        append("(Dienste notfalls mit ha_list_services finden). Erledige genau das, was im Job steht, nichts anderes. ")
        append("Antworte am Ende mit einem Satz, was du getan hast.\n")
        append("Job #${task.id}")
        task.createdBy?.let { append(", angelegt von $it") }
        append(" am ").append(format(task.createdAt))
        task.conditionDescription?.let { append(", ausgelöst: $it") }
        append(".\n\n")
        append(baseInstruction())
    }

    private fun format(millis: Long) =
        DateTimeFormatter.ofPattern("EEEE, d. MMMM, HH:mm 'Uhr'", Locale.GERMAN)
            .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    companion object {
        private const val TAG = "VoiceAssistant"
        private const val ALARM_REQUEST = 4711
        private const val EVENT_CHECK_MS = 60_000L
        private const val RUN_TIMEOUT_MS = 90_000L
        private const val MAX_LATE_MS = 60 * 60_000L
        /** Conversation-only tools that make no sense in a background job. */
        private val EXCLUDED_TOOLS = setOf("end_conversation", "who_is_speaking")
    }
}

class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as App).container
        val pending = goAsync()
        container.scope.launch {
            try { container.taskRunner.runDue() } finally { pending.finish() }
        }
    }
}
