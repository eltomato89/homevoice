package de.homevoice.assistant.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Converts MCP tool definitions (JSON Schema) into Gemini FunctionDeclarations.
 * Gemini accepts only an OpenAPI 3 subset, so unsupported constructs ($ref, additionalProperties,
 * type unions, non-string enums, empty objects) are simplified.
 */
object SchemaConverter {
    private val NAME_INVALID = Regex("[^a-zA-Z0-9_.-]")

    fun functionName(prefix: String, mcpName: String): String =
        (prefix + mcpName.replace(NAME_INVALID, "_")).take(64)

    fun toFunctionDeclaration(name: String, description: String, inputSchema: JsonObject?): JsonObject {
        val params = inputSchema?.let { convert(it) }
        return buildJsonObject {
            put("name", name)
            put("description", description.ifBlank { name })
            val props = params?.get("properties") as? JsonObject
            if (params != null && params["type"] == JsonPrimitive("OBJECT") && !props.isNullOrEmpty()) {
                put("parameters", params)
            }
        }
    }

    /** Returns a Gemini schema for [schema], or null when nothing usable is left. */
    fun convert(schema: JsonObject): JsonObject? {
        // anyOf/oneOf: keep the first non-null alternative, remember nullability.
        val alternatives = (schema["anyOf"] ?: schema["oneOf"]) as? JsonArray
        if (alternatives != null) {
            val options = alternatives.filterIsInstance<JsonObject>()
            val nonNull = options.filterNot { it.typeNames() == listOf("null") }
            val chosen = nonNull.firstOrNull() ?: return null
            val merged = JsonObject(chosen + schema.filterKeys { it == "description" && "description" !in chosen })
            val converted = convert(merged) ?: return null
            return if (nonNull.size < options.size) converted.withNullable() else converted
        }

        val typeNames = schema.typeNames()
        val nullable = "null" in typeNames
        val type = typeNames.firstOrNull { it != "null" } ?: inferType(schema)

        val description = (schema["description"] as? JsonPrimitive)?.contentOrNull
            ?: (schema["title"] as? JsonPrimitive)?.contentOrNull

        val result = when (type) {
            "object" -> convertObject(schema, description)
            "array" -> buildJsonObject {
                put("type", "ARRAY")
                description?.let { put("description", it) }
                val items = (schema["items"] as? JsonObject)?.let { convert(it) }
                    ?: buildJsonObject { put("type", "STRING") }
                put("items", items)
                copyNumber(schema, "minItems")
                copyNumber(schema, "maxItems")
            }
            "integer", "number" -> buildJsonObject {
                put("type", type.uppercase())
                put("description", withEnumHint(description, schema))
                copyNumber(schema, "minimum")
                copyNumber(schema, "maximum")
            }.withoutBlankDescription()
            "boolean" -> buildJsonObject {
                put("type", "BOOLEAN")
                description?.let { put("description", it) }
            }
            else -> buildJsonObject {
                put("type", "STRING")
                description?.let { put("description", it) }
                val values = enumValues(schema)
                if (values.isNotEmpty()) putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
            }
        }
        return result?.let { if (nullable) it.withNullable() else it }
    }

    private fun convertObject(schema: JsonObject, description: String?): JsonObject {
        val properties = (schema["properties"] as? JsonObject).orEmpty()
            .mapNotNull { (key, value) -> (value as? JsonObject)?.let { convert(it) }?.let { key to it } }
            .toMap()
        if (properties.isEmpty()) {
            // Gemini rejects OBJECT without properties; accept free-form JSON as text instead.
            return buildJsonObject {
                put("type", "STRING")
                put("description", listOfNotNull(description, "JSON-Objekt als Text").joinToString(" – "))
            }
        }
        val required = (schema["required"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .filter { it in properties }
        return buildJsonObject {
            put("type", "OBJECT")
            description?.let { put("description", it) }
            put("properties", JsonObject(properties))
            if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        }
    }

    private fun JsonObject.typeNames(): List<String> = when (val t = this["type"]) {
        is JsonPrimitive -> listOfNotNull(t.contentOrNull)
        is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> emptyList()
    }

    private fun inferType(schema: JsonObject): String = when {
        "properties" in schema -> "object"
        "items" in schema -> "array"
        else -> "string"
    }

    private fun enumValues(schema: JsonObject): List<String> {
        val raw = (schema["enum"] as? JsonArray)?.toList()
            ?: schema["const"]?.let { listOf(it) }
            ?: return emptyList()
        return raw.filter { it !is JsonNull }.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }

    private fun withEnumHint(description: String?, schema: JsonObject): String {
        val values = enumValues(schema)
        if (values.isEmpty()) return description ?: ""
        return listOfNotNull(description, "Erlaubte Werte: ${values.joinToString()}").joinToString(". ")
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.copyNumber(schema: JsonObject, key: String) {
        (schema[key] as? JsonPrimitive)?.takeIf { !it.isString }?.let { put(key, it) }
    }

    private fun JsonObject.withNullable(): JsonObject = JsonObject(this + ("nullable" to JsonPrimitive(true)))

    private fun JsonObject.withoutBlankDescription(): JsonObject =
        if ((this["description"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) JsonObject(this - "description") else this
}
