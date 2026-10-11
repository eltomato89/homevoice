package de.homevoice.assistant.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaConverterTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    // Shape as produced by Home Assistant (voluptuous-openapi) for HassTurnOn.
    private val hassTurnOn = obj(
        """{
          "type":"object",
          "properties":{
            "name":{"type":"string"},
            "area":{"type":"string"},
            "domain":{"type":"array","items":{"type":"string","enum":["light","switch","fan"]}},
            "brightness":{"type":"integer","minimum":0,"maximum":100},
            "extra":{"anyOf":[{"type":"string"},{"type":"null"}],"description":"Optional"},
            "mode":{"type":["string","null"],"enum":["a","b",null]},
            "data":{"type":"object","additionalProperties":true}
          },
          "required":["name","missing"],
          "additionalProperties":false,
          "${'$'}schema":"http://json-schema.org/draft-07/schema#"
        }"""
    )

    @Test
    fun convertsHomeAssistantStyleSchema() {
        val decl = SchemaConverter.toFunctionDeclaration("ha_HassTurnOn", "Turns on a device", hassTurnOn)
        val params = decl["parameters"]!!.jsonObject
        assertEquals("OBJECT", params["type"]!!.jsonPrimitive.content)
        assertFalse("additionalProperties" in params)
        assertFalse("\$schema" in params)
        assertEquals(listOf("name"), params["required"]!!.jsonArray.map { it.jsonPrimitive.content })

        val props = params["properties"]!!.jsonObject
        assertEquals("STRING", props["name"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val domain = props["domain"]!!.jsonObject
        assertEquals("ARRAY", domain["type"]!!.jsonPrimitive.content)
        assertEquals(3, domain["items"]!!.jsonObject["enum"]!!.jsonArray.size)
        val brightness = props["brightness"]!!.jsonObject
        assertEquals("INTEGER", brightness["type"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(100), brightness["maximum"])

        val extra = props["extra"]!!.jsonObject
        assertEquals("STRING", extra["type"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), extra["nullable"])
        assertEquals("Optional", extra["description"]!!.jsonPrimitive.content)

        val mode = props["mode"]!!.jsonObject
        assertEquals(JsonPrimitive(true), mode["nullable"])
        assertEquals(listOf("a", "b"), mode["enum"]!!.jsonArray.map { it.jsonPrimitive.content })

        // Free-form object becomes text because Gemini rejects OBJECT without properties.
        assertEquals("STRING", props["data"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun omitsParametersForEmptySchema() {
        val decl = SchemaConverter.toFunctionDeclaration("ha_GetLiveContext", "", obj("""{"type":"object","properties":{}}"""))
        assertNull(decl["parameters"])
        assertEquals("ha_GetLiveContext", decl["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun integerEnumBecomesDescriptionHint() {
        val s = SchemaConverter.convert(obj("""{"type":"integer","enum":[1,2,3]}"""))!!
        assertEquals("INTEGER", s["type"]!!.jsonPrimitive.content)
        assertFalse("enum" in s)
        assertTrue(s["description"]!!.jsonPrimitive.content.contains("1, 2, 3"))
    }

    @Test
    fun sanitizesFunctionNames() {
        assertEquals("ha_light_turn_on", SchemaConverter.functionName("ha_", "light turn/on"))
        assertEquals(64, SchemaConverter.functionName("ha_", "x".repeat(100)).length)
    }

    @Test
    fun nullOnlyAnyOfIsDropped() {
        assertNull(SchemaConverter.convert(obj("""{"anyOf":[{"type":"null"}]}""")))
    }
}
