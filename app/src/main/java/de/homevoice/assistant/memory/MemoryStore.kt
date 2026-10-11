package de.homevoice.assistant.memory

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Something the assistant should remember. [person] = null means it applies to the whole household. */
@Serializable
data class Memory(
    val id: Int,
    val text: String,
    val person: String? = null,
    val createdAt: Long,
    /** Who told the assistant (recognized speaker), if known. */
    val source: String? = null,
)

/** The assistant's long-term memory, stored as JSON in app storage. */
class MemoryStore(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = ListSerializer(Memory.serializer())

    private val _memories = MutableStateFlow(load())
    val memories: StateFlow<List<Memory>> = _memories.asStateFlow()

    private fun load(): List<Memory> = try {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList()
    } catch (e: Exception) {
        Log.w("VoiceAssistant", "Memories unreadable", e)
        emptyList()
    }

    @Synchronized
    private fun update(transform: (List<Memory>) -> List<Memory>): List<Memory> {
        val next = transform(_memories.value)
        file.writeText(json.encodeToString(serializer, next))
        _memories.value = next
        return next
    }

    @Synchronized
    fun add(text: String, person: String?, source: String?): Memory {
        val clean = text.trim()
        require(clean.isNotEmpty()) { "Nichts zu merken" }
        // Same fact again (e.g. repeated "merk dir"): keep one entry.
        _memories.value.firstOrNull { it.text.equals(clean, ignoreCase = true) && it.person == person }?.let { return it }
        val memory = Memory((_memories.value.maxOfOrNull { it.id } ?: 0) + 1, clean, person?.trim()?.ifEmpty { null }, now(), source)
        update { it + memory }
        return memory
    }

    fun edit(id: Int, text: String, person: String?) {
        require(text.isNotBlank()) { "Text fehlt" }
        update { list -> list.map { if (it.id == id) it.copy(text = text.trim(), person = person?.trim()?.ifEmpty { null }) else it } }
    }

    fun remove(id: Int): Memory? {
        val memory = _memories.value.firstOrNull { it.id == id } ?: return null
        update { list -> list.filterNot { it.id == id } }
        return memory
    }

    /** Entries containing all words of [query] (case-insensitive), newest first. */
    fun search(query: String): List<Memory> {
        val words = query.lowercase().split(Regex("\\W+")).filter { it.length > 1 }
        return _memories.value
            .filter { m -> val t = (m.text + " " + (m.person ?: "")).lowercase(); words.all { it in t } }
            .sortedByDescending { it.createdAt }
    }

    /**
     * Prompt section with the newest memories that fit into [maxChars]; older ones stay reachable
     * through the search tool.
     */
    fun promptSection(maxChars: Int = 6000): String? {
        val all = _memories.value.sortedByDescending { it.createdAt }
        if (all.isEmpty()) return null
        val lines = mutableListOf<String>()
        var used = 0
        for (m in all) {
            val line = "- [${m.id}] " + (m.person?.let { "($it) " } ?: "") + m.text
            if (used + line.length > maxChars) break
            lines += line
            used += line.length + 1
        }
        val more = all.size - lines.size
        return buildString {
            append(lines.sortedBy { it.substringAfter("[").substringBefore("]").toInt() }.joinToString("\n"))
            if (more > 0) append("\n(+$more ältere Einträge, mit search_memory durchsuchbar)")
        }
    }
}
