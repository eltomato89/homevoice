package de.homevoice.assistant.tools

import de.homevoice.assistant.memory.MemoryStore
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Long-term memory tools. [currentSpeaker] returns the recognized speaker's name (or null), used
 * as the default person for personal facts ("ich mag …").
 */
fun memoryTools(memory: MemoryStore, currentSpeaker: () -> String?): List<ToolHandler> = listOf(
    ToolHandler(
        declaration(
            "remember",
            "Merkt sich dauerhaft etwas (Gedächtnis). Aufrufen, wenn der Nutzer sagt \"merk dir …\" oder eine dauerhafte " +
                "Vorliebe/Information nennt. Den Fakt als vollständigen, eigenständigen Satz formulieren. " +
                "Persönliches mit person (Name), Allgemeines für den Haushalt ohne person.",
            mapOf(
                "fact" to ("STRING" to "Der zu merkende Fakt, z.B. 'Mag keinen Koriander' oder 'Der Müll wird dienstags abgeholt'"),
                "person" to ("STRING" to "Name der Person, um die es geht; leer für den ganzen Haushalt"),
            ),
            required = listOf("fact"),
        )
    ) { args ->
        val speaker = currentSpeaker()
        val m = memory.add(args.string("fact").orEmpty(), args.string("person")?.takeIf { it.isNotBlank() }, speaker)
        buildJsonObject {
            put("gemerkt", m.text)
            put("id", m.id)
            m.person?.let { put("person", it) }
        }
    },
    ToolHandler(
        declaration(
            "forget",
            "Löscht einen Eintrag aus dem Gedächtnis. id aus der Liste im System-Prompt oder aus search_memory.",
            mapOf("id" to ("INTEGER" to "Id des Eintrags")),
            required = listOf("id"),
        )
    ) { args ->
        val id = args.number("id")?.toInt() ?: throw IllegalArgumentException("id fehlt")
        val removed = memory.remove(id) ?: throw IllegalArgumentException("Kein Eintrag mit id $id")
        buildJsonObject { put("vergessen", removed.text) }
    },
    ToolHandler(
        declaration(
            "search_memory",
            "Durchsucht das Gedächtnis nach Stichworten (für ältere Einträge, die nicht im System-Prompt stehen).",
            mapOf("query" to ("STRING" to "Stichworte")),
            required = listOf("query"),
        )
    ) { args ->
        val hits = memory.search(args.string("query").orEmpty()).take(20)
        buildJsonObject {
            put("treffer", buildJsonArray {
                hits.forEach { m ->
                    add(buildJsonObject { put("id", m.id); put("text", m.text); m.person?.let { put("person", it) } })
                }
            })
        }
    },
)
