package de.homevoice.assistant.radio

import de.homevoice.assistant.settings.RadioStation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class RadioSearchResult(
    val name: String,
    val url: String,
    val country: String,
    val codec: String,
    val bitrate: Int,
    val clicks: Int,
) {
    fun toStation() = RadioStation(name.trim(), url)
}

/**
 * Searches the open Radio Browser directory (radio-browser.info, ~50k stations, no API key).
 * It is a community service that is occasionally down, hence the mirror list.
 */
class RadioBrowser(
    private val http: OkHttpClient,
    private val servers: List<String> = listOf(
        "https://de1.api.radio-browser.info",
        "https://de2.api.radio-browser.info",
        "https://all.api.radio-browser.info",
    ),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(query: String, limit: Int = 10): List<RadioSearchResult> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (server in servers) {
            try {
                val url = "$server/json/stations/search".toHttpUrl().newBuilder()
                    .addQueryParameter("name", query.trim())
                    .addQueryParameter("hidebroken", "true")
                    .addQueryParameter("order", "clickcount")
                    .addQueryParameter("reverse", "true")
                    .addQueryParameter("limit", (limit * 3).toString())
                    .build()
                val request = Request.Builder().url(url).header("User-Agent", "HomeVoice/0.1").build()
                val body = http.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    r.body?.string().orEmpty()
                }
                return@withContext rank(query, parse(body)).take(limit)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException("Radio Browser nicht erreichbar (${lastError?.message})")
    }

    internal fun parse(body: String): List<RadioSearchResult> =
        (json.parseToJsonElement(body) as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            fun i(k: String) = (o[k] as? JsonPrimitive)?.intOrNull ?: 0
            val url = s("url_resolved").ifBlank { s("url") }
            if (url.isBlank() || s("lastcheckok") == "0") return@mapNotNull null
            RadioSearchResult(s("name"), url, s("countrycode"), s("codec"), i("bitrate"), i("clickcount"))
        }

    companion object {
        /** "Radio 1 Live!" → "radio1live" */
        fun normalize(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

        /** Exact name matches first, then names containing the query, each by popularity; duplicates removed. */
        fun rank(query: String, results: List<RadioSearchResult>): List<RadioSearchResult> {
            val q = normalize(query)
            return results
                .distinctBy { normalize(it.name) }
                .sortedWith(compareBy<RadioSearchResult>(
                    { if (normalize(it.name) == q) 0 else if (normalize(it.name).startsWith(q)) 1 else 2 },
                    { -it.clicks },
                ))
        }

        /** Finds a saved station: exact (normalized) name first, then names contained in each other. */
        fun findSaved(query: String, stations: List<RadioStation>): RadioStation? {
            val q = normalize(query).removePrefix("radio").ifEmpty { normalize(query) }
            stations.firstOrNull { normalize(it.name) == normalize(query) || normalize(it.name) == q }?.let { return it }
            return stations.firstOrNull {
                val n = normalize(it.name)
                n.isNotEmpty() && q.isNotEmpty() && (n.contains(q) || q.contains(n))
            }
        }
    }
}
