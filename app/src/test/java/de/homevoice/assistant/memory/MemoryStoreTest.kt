package de.homevoice.assistant.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemoryStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000L
    private fun store(file: File = File(tmp.root, "m.json")) = MemoryStore(file) { now++ }

    @Test
    fun addsPersistsAndDeduplicates() {
        val file = File(tmp.root, "m.json")
        val s = store(file)
        val a = s.add("Der Müll wird dienstags abgeholt", null, "Jens")
        val b = s.add("Mag keinen Koriander", "Mona", "Mona")
        assertEquals(a, s.add(" der müll wird dienstags abgeholt ", null, null))
        assertEquals(listOf(1, 2), store(file).memories.value.map { it.id }) // reloaded from disk
        assertEquals("Mona", b.person)
    }

    @Test
    fun editRemoveAndSearch() {
        val s = store()
        s.add("Mag keinen Koriander", "Mona", null)
        s.add("Lieblingsfarbe ist blau", "Thilo", null)
        s.edit(1, "Mag keinen Koriander und keine Oliven", "Mona")
        assertEquals(listOf("Mag keinen Koriander und keine Oliven"), s.search("oliven mona").map { it.text })
        assertEquals("Lieblingsfarbe ist blau", s.remove(2)?.text)
        assertNull(s.remove(2))
    }

    @Test
    fun promptKeepsNewestWithinBudget() {
        val s = store()
        repeat(50) { s.add("Fakt Nummer $it mit etwas Text", null, null) }
        val section = s.promptSection(maxChars = 400)!!
        assertTrue(section.contains("Fakt Nummer 49"))
        assertTrue(!section.contains("Fakt Nummer 0 "))
        assertTrue(section.contains("ältere Einträge"))
        assertTrue(section.length < 520)
    }
}
