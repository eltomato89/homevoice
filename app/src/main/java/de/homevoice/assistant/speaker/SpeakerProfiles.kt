package de.homevoice.assistant.speaker

import android.content.Context
import android.util.Log
import de.homevoice.speaker.SpeakerEmbedder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** A household member: voice prints from enrollment recordings and their own calendar. */
@Serializable
data class SpeakerProfile(
    val id: String,
    val name: String,
    /** Home Assistant calendar entity, e.g. calendar.mona; empty = none. */
    val calendar: String = "",
    val embeddings: List<List<Float>> = emptyList(),
) {
    /** Average voice print, or null before the first recording. */
    fun centroid(): FloatArray? =
        embeddings.takeIf { it.isNotEmpty() }?.let { list -> SpeakerEmbedder.average(list.map { it.toFloatArray() }) }
}

/** Stores the profiles in app storage (only numbers, no recordings). */
class SpeakerProfiles(context: Context) {
    private val file = File(context.filesDir, "speakers.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SpeakerProfile.serializer())

    private val _profiles = MutableStateFlow(load())
    val profiles: StateFlow<List<SpeakerProfile>> = _profiles.asStateFlow()

    private fun load(): List<SpeakerProfile> = try {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList()
    } catch (e: Exception) {
        Log.w("VoiceAssistant", "Speaker profiles unreadable", e)
        emptyList()
    }

    @Synchronized
    private fun update(transform: (List<SpeakerProfile>) -> List<SpeakerProfile>) {
        val next = transform(_profiles.value)
        file.writeText(json.encodeToString(serializer, next))
        _profiles.value = next
    }

    fun add(name: String, calendar: String): SpeakerProfile {
        val profile = SpeakerProfile(UUID.randomUUID().toString().take(8), name.trim(), calendar.trim())
        update { it + profile }
        return profile
    }

    fun edit(id: String, name: String, calendar: String) =
        update { list -> list.map { if (it.id == id) it.copy(name = name.trim(), calendar = calendar.trim()) else it } }

    fun addSample(id: String, embedding: FloatArray) =
        update { list -> list.map { if (it.id == id) it.copy(embeddings = it.embeddings + listOf(embedding.toList())) else it } }

    fun clearSamples(id: String) = update { list -> list.map { if (it.id == id) it.copy(embeddings = emptyList()) else it } }

    fun remove(id: String) = update { list -> list.filterNot { it.id == id } }

    fun get(id: String): SpeakerProfile? = _profiles.value.firstOrNull { it.id == id }
}
