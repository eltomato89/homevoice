package de.homevoice.assistant.timer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class TimerEntry(
    val id: Int,
    val label: String?,
    val durationSeconds: Long,
    val endAtMillis: Long,
)

interface AlarmScheduler {
    fun schedule(id: Int, atMillis: Long)
    fun cancel(id: Int)
}

interface TimerStore {
    fun load(): List<TimerEntry>
    fun save(timers: List<TimerEntry>)
}

interface AlarmSound {
    fun start()
    fun stop()
}

/**
 * Keeps running and ringing timers. Timers survive process death through [store]
 * and are fired by the system alarm scheduler, which calls [fire].
 */
class TimerManager(
    private val scheduler: AlarmScheduler,
    private val store: TimerStore,
    private val sound: AlarmSound,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _timers = MutableStateFlow<List<TimerEntry>>(emptyList())
    val timers: StateFlow<List<TimerEntry>> = _timers.asStateFlow()

    private val _ringing = MutableStateFlow<List<TimerEntry>>(emptyList())
    val ringing: StateFlow<List<TimerEntry>> = _ringing.asStateFlow()

    val isRinging: Boolean get() = _ringing.value.isNotEmpty()

    /** Reloads persisted timers; timers that elapsed while the app was not running ring immediately. */
    @Synchronized
    fun restore() {
        val (elapsed, pending) = store.load().partition { it.endAtMillis <= now() }
        _timers.value = pending.sortedBy { it.endAtMillis }
        pending.forEach { scheduler.schedule(it.id, it.endAtMillis) }
        if (elapsed.isNotEmpty()) ring(elapsed)
        persist()
    }

    @Synchronized
    fun add(durationSeconds: Long, label: String?): TimerEntry {
        require(durationSeconds > 0) { "Dauer muss größer als 0 sein" }
        val id = ((_timers.value + _ringing.value).maxOfOrNull { it.id } ?: 0) + 1
        val entry = TimerEntry(id, label?.trim()?.takeIf { it.isNotEmpty() }, durationSeconds, now() + durationSeconds * 1000)
        _timers.value = (_timers.value + entry).sortedBy { it.endAtMillis }
        scheduler.schedule(id, entry.endAtMillis)
        persist()
        return entry
    }

    /** Cancels matching running timers. Label match is case-insensitive. Returns the cancelled timers. */
    @Synchronized
    fun cancel(id: Int? = null, label: String? = null, all: Boolean = false): List<TimerEntry> {
        val current = _timers.value
        val targets = when {
            all -> current
            id != null -> current.filter { it.id == id }
            label != null -> current.filter { it.label.equals(label.trim(), ignoreCase = true) }
            current.size == 1 -> current
            else -> emptyList()
        }
        if (targets.isEmpty()) return emptyList()
        targets.forEach { scheduler.cancel(it.id) }
        _timers.value = current - targets.toSet()
        persist()
        return targets
    }

    /** Called when the system alarm for [id] goes off. */
    @Synchronized
    fun fire(id: Int) {
        val entry = _timers.value.firstOrNull { it.id == id } ?: return
        _timers.value = _timers.value - entry
        ring(listOf(entry))
        persist()
    }

    /** Stops all ringing timers. Returns true if something was ringing. */
    @Synchronized
    fun stopRinging(): Boolean {
        if (_ringing.value.isEmpty()) return false
        _ringing.value = emptyList()
        sound.stop()
        return true
    }

    fun remainingSeconds(entry: TimerEntry): Long = maxOf(0, (entry.endAtMillis - now() + 999) / 1000)

    private fun ring(entries: List<TimerEntry>) {
        val wasRinging = _ringing.value.isNotEmpty()
        _ringing.value = _ringing.value + entries
        if (!wasRinging) sound.start()
    }

    private fun persist() = store.save(_timers.value)
}
