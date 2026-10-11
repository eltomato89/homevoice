package de.homevoice.assistant.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

class TaskStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun nextOccurrenceSkipsWeekendsAndCatchesUp() {
        // Fri 2026-10-16 07:00 → next weekday is Mon 2026-10-19 07:00
        assertEquals(at("2026-10-19T07:00"), nextOccurrence(at("2026-10-16T07:00"), "weekdays", at("2026-10-16T07:01"), zone))
        assertEquals(at("2026-10-17T07:00"), nextOccurrence(at("2026-10-16T07:00"), "daily", at("2026-10-16T07:01"), zone))
        // Device off for three days: next run is in the future, not in the past.
        assertEquals(at("2026-10-20T07:00"), nextOccurrence(at("2026-10-16T07:00"), "daily", at("2026-10-19T12:00"), zone))
        assertEquals(at("2026-10-23T07:00"), nextOccurrence(at("2026-10-16T07:00"), "weekly", at("2026-10-16T08:00"), zone))
        assertNull(nextOccurrence(at("2026-10-16T07:00"), "none", at("2026-10-16T08:00"), zone))
    }

    @Test
    fun oneOffJobsAreRemovedRepeatingOnesMoveOn() {
        var now = at("2026-10-12T10:00")
        val store = TaskStore(File(tmp.root, "t.json")) { now }
        val once = store.add { id, c -> ScheduledTask(id, "Erinnere Jens", runAt = at("2026-10-12T10:00"), createdAt = c) }
        val daily = store.add { id, c -> ScheduledTask(id, "Wetter aufs iPhone", runAt = at("2026-10-12T07:00"), repeat = "daily", createdAt = c) }
        assertEquals(listOf(once.id, daily.id), store.due().map { it.id })
        assertNull(store.completed(once, "ok"))
        assertEquals(at("2026-10-13T07:00"), store.completed(daily, "ok")!!.runAt)
        assertEquals(at("2026-10-13T07:00"), TaskStore(File(tmp.root, "t.json")).nextRunAt()) // persisted
    }
}
