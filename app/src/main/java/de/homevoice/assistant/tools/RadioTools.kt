package de.homevoice.assistant.tools

import de.homevoice.assistant.radio.RadioBrowser
import de.homevoice.assistant.radio.RadioPlayer
import de.homevoice.assistant.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun radioTools(radio: RadioPlayer, browser: RadioBrowser, settings: SettingsRepository): List<ToolHandler> = listOf(
    ToolHandler(
        declaration(
            "play_radio",
            "Spielt einen Internet-Radiosender. Gespeicherte Sender des Nutzers haben Vorrang (Namen stehen im System-Prompt), " +
                "sonst wird im Radio-Browser-Verzeichnis gesucht und der Sender gespeichert. Zum Stoppen music_control pause.",
            mapOf("station" to ("STRING" to "Sendername, z.B. '1LIVE', 'WDR 2', 'Deutschlandfunk'")),
            required = listOf("station"),
        )
    ) { args ->
        val query = args.string("station")?.trim().orEmpty()
        require(query.isNotEmpty()) { "station fehlt" }
        val saved = RadioBrowser.findSaved(query, settings.current.radioStations)
        val station = saved ?: run {
            val found = browser.search(query, limit = 1).firstOrNull()
                ?: throw IllegalStateException("Keinen Sender \"$query\" gefunden")
            found.toStation().also { learned ->
                // Remember it, so next time no lookup is needed (and it shows up in the web interface).
                settings.update { s -> s.copy(radioStations = s.radioStations + learned) }
            }
        }
        withContext(Dispatchers.Main) { radio.play(station) }
        buildJsonObject {
            put("status", "Spiele ${station.name}")
            if (saved == null) put("hinweis", "Sender neu gefunden und gespeichert")
        }
    },
)
