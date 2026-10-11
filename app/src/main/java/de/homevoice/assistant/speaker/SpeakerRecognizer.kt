package de.homevoice.assistant.speaker

import android.content.Context
import de.homevoice.speaker.SpeakerEmbedder

data class Identification(
    /** Recognized person, or null if nobody matched clearly enough. */
    val profile: SpeakerProfile?,
    val score: Float,
    /** All scores by name, best first (for tuning in the web interface). */
    val scores: List<Pair<String, Float>>,
)

/** Compares speech against the enrolled voice prints. Runs entirely on the device. */
class SpeakerRecognizer(private val context: Context, private val profiles: SpeakerProfiles) {
    private var embedder: SpeakerEmbedder? = null

    @Synchronized
    private fun embedder(): SpeakerEmbedder = embedder ?: SpeakerEmbedder(
        context.assets.open("wakeword/speaker_campplus_3dspeaker.onnx").use { it.readBytes() }
    ).also { embedder = it }

    @Synchronized
    fun embed(pcm: ShortArray): FloatArray = embedder().embed(pcm)

    /**
     * The best matching person if their score reaches [threshold] and is clearly ahead of the
     * runner-up (so two similar voices, e.g. siblings, lead to "unknown" instead of a wrong guess).
     */
    fun identify(pcm: ShortArray, threshold: Float): Identification {
        val candidates = profiles.profiles.value.mapNotNull { p -> p.centroid()?.let { p to it } }
        if (candidates.isEmpty()) return Identification(null, 0f, emptyList())
        val probe = embed(pcm)
        val scored = candidates.map { (p, c) -> p to SpeakerEmbedder.similarity(probe, c) }.sortedByDescending { it.second }
        val (best, score) = scored.first()
        val second = scored.getOrNull(1)?.second ?: -1f
        val clear = score >= threshold && score - second >= MIN_MARGIN
        return Identification(if (clear) best else null, score, scored.map { it.first.name to it.second })
    }

    private companion object {
        const val MIN_MARGIN = 0.08f
    }
}
