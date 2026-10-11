package de.homevoice.assistant.timer

import de.homevoice.assistant.tools.timerTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerManagerTest {
    private var now = 1_000_000L
    private val scheduled = mutableMapOf<Int, Long>()
    private var stored = listOf<TimerEntry>()
    private var soundOn = false

    private val scheduler = object : AlarmScheduler {
        override fun schedule(id: Int, atMillis: Long) { scheduled[id] = atMillis }
        override fun cancel(id: Int) { scheduled.remove(id) }
    }
    private val store = object : TimerStore {
        override fun load() = stored
        override fun save(timers: List<TimerEntry>) { stored = timers }
    }
    private val sound = object : AlarmSound {
        override fun start() { soundOn = true }
        override fun stop() { soundOn = false }
    }

    private fun manager() = TimerManager(scheduler, store, sound) { now }

    @Test
    fun addSchedulesAndPersists() {
        val m = manager()
        val t = m.add(300, " Nudeln ")
        assertEquals("Nudeln", t.label)
        assertEquals(now + 300_000, scheduled[t.id])
        assertEquals(listOf(t), stored)
        now += 120_000
        assertEquals(180, m.remainingSeconds(t))
    }

    @Test
    fun cancelByLabelIdSingleAndAll() {
        val m = manager()
        val a = m.add(60, "Eier")
        val b = m.add(120, "Pizza")
        assertTrue(m.cancel().isEmpty()) // ambiguous with two timers
        assertEquals(listOf(a), m.cancel(label = "eier"))
        assertEquals(listOf(b), m.cancel()) // only one left
        m.add(10, null); m.add(20, null)
        assertEquals(2, m.cancel(all = true).size)
        assertTrue(scheduled.isEmpty())
        assertTrue(stored.isEmpty())
    }

    @Test
    fun fireRingsAndStopSilences() {
        val m = manager()
        val t = m.add(60, null)
        m.fire(t.id)
        assertTrue(m.isRinging)
        assertTrue(soundOn)
        assertTrue(m.timers.value.isEmpty())
        assertTrue(m.stopRinging())
        assertFalse(soundOn)
        assertFalse(m.stopRinging())
    }

    @Test
    fun restoreRingsElapsedAndReschedulesPending() {
        stored = listOf(TimerEntry(1, "alt", 60, now - 1), TimerEntry(2, "neu", 60, now + 30_000))
        val m = manager()
        m.restore()
        assertEquals(listOf(1), m.ringing.value.map { it.id })
        assertEquals(listOf(2), m.timers.value.map { it.id })
        assertEquals(now + 30_000, scheduled[2])
        assertTrue(soundOn)
    }

    @Test
    fun timerToolsRoundTrip() = runBlocking {
        val m = manager()
        val tools = timerTools(m).associateBy { it.name }
        val set = tools.getValue("set_timer").handler(buildJsonObject { put("duration_seconds", 90); put("label", "Tee") }) as JsonObject
        assertEquals(JsonPrimitive("gestellt"), set["status"])
        val list = tools.getValue("list_timers").handler(JsonObject(emptyMap())) as JsonObject
        assertEquals(1, list["timers"]!!.jsonArray.size)
        val cancel = tools.getValue("cancel_timer").handler(buildJsonObject { put("label", "tee") }) as JsonObject
        assertEquals(1, cancel["cancelled"]!!.jsonArray.size)
        assertTrue(m.timers.value.isEmpty())
    }
}
