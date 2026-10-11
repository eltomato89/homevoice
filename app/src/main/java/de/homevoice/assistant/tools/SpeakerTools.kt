package de.homevoice.assistant.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import de.homevoice.assistant.service.ConversationController

fun speakerTools(controller: ConversationController): List<ToolHandler> = listOf(
    ToolHandler(
        declaration(
            "who_is_speaking",
            "Erkennt anhand der Stimme (auf dem Gerät), wer gerade gesprochen hat. Aufrufen bei \"Wer bin ich?\" " +
                "oder wenn für eine persönliche Frage (\"meine Termine\") noch kein sicherer Sprecherhinweis vorliegt.",
        )
    ) {
        val id = controller.identifyCurrentSpeaker()
        buildJsonObject {
            val p = id.profile
            if (p != null) {
                put("sprecher", p.name)
                if (p.calendar.isNotBlank()) put("kalender", p.calendar)
            } else {
                put("sprecher", "unbekannt")
                put("hinweis", "Stimme nicht sicher erkannt – nachfragen, wer spricht")
            }
            put("werte", buildJsonArray {
                id.scores.forEach { (n, s) -> add(buildJsonObject { put("name", n); put("aehnlichkeit", "%.2f".format(s)) }) }
            })
        }
    },
)
