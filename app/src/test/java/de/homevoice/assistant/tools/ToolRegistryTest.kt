package de.homevoice.assistant.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {
    private val empty = JsonObject(emptyMap())

    @Test
    fun dispatchesByNameAndWrapsNonObjectResults() = runBlocking {
        val registry = ToolRegistry()
        registry.setGroup("a", listOf(ToolHandler(declaration("echo", "")) { args -> args["v"]!! }))
        val result = registry.execute("echo", buildJsonObject { put("v", 42) })
        assertEquals(JsonPrimitive(42), result["result"])
    }

    @Test
    fun unknownToolAndExceptionsBecomeErrors() = runBlocking {
        val registry = ToolRegistry()
        registry.setGroup("a", listOf(ToolHandler(declaration("boom", "")) { throw IllegalStateException("kaputt") }))
        assertTrue(registry.execute("nope", empty)["error"].toString().contains("nope"))
        assertEquals(JsonPrimitive("kaputt"), registry.execute("boom", empty)["error"])
    }

    @Test
    fun timeoutBecomesError() = runBlocking {
        val registry = ToolRegistry()
        registry.setGroup("a", listOf(ToolHandler(declaration("slow", "")) { delay(5_000); empty }))
        assertTrue("error" in registry.execute("slow", empty, timeoutMs = 50))
    }

    @Test
    fun replacingGroupReplacesDeclarations() {
        val registry = ToolRegistry()
        registry.setGroup("ha", listOf(ToolHandler(declaration("ha_a", "")) { empty }))
        registry.setGroup("timer", listOf(ToolHandler(declaration("t", "")) { empty }))
        registry.setGroup("ha", emptyList())
        assertEquals(listOf("t"), registry.declarations().map { (it["name"] as JsonPrimitive).content })
    }
}
