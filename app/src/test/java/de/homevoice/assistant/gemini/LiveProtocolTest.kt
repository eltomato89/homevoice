package de.homevoice.assistant.gemini

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LiveProtocolTest {

    private fun parse(text: String) = LiveProtocol.json.parseToJsonElement(text).jsonObject

    @Test
    fun setupContainsModelVoiceToolsAndTranscription() {
        val tool = buildJsonObject { put("name", "set_timer"); put("description", "x") }
        val setup = parse(
            LiveProtocol.setupMessage(SessionConfig("gemini-3.8-live", "Kore", "Sei nett", listOf(tool)))
        )["setup"]!!.jsonObject

        assertEquals("models/gemini-3.8-live", setup["model"]!!.jsonPrimitive.content)
        val gen = setup["generationConfig"]!!.jsonObject
        assertEquals("AUDIO", gen["responseModalities"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(
            "Kore",
            gen["speechConfig"]!!.jsonObject["voiceConfig"]!!.jsonObject["prebuiltVoiceConfig"]!!
                .jsonObject["voiceName"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "Sei nett",
            setup["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        val decls = setup["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals("set_timer", decls[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue("inputAudioTranscription" in setup)
        assertTrue("contextWindowCompression" in setup)
    }

    @Test
    fun setupOmitsToolsWhenEmptyAndKeepsModelsPrefix() {
        val setup = parse(LiveProtocol.setupMessage(SessionConfig("models/x", "Puck", "", emptyList())))["setup"]!!.jsonObject
        assertFalse("tools" in setup)
        assertEquals("models/x", setup["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun audioMessageIsBase64PcmWithRate() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val audio = parse(LiveProtocol.audioMessage(pcm))["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject
        assertEquals("audio/pcm;rate=16000", audio["mimeType"]!!.jsonPrimitive.content)
        assertArrayEquals(pcm, Base64.getDecoder().decode(audio["data"]!!.jsonPrimitive.content))
    }

    @Test
    fun toolResponseShape() {
        val msg = parse(
            LiveProtocol.toolResponseMessage(listOf(Triple("call-1", "list_timers", buildJsonObject { put("ok", true) })))
        )
        val r = msg["toolResponse"]!!.jsonObject["functionResponses"]!!.jsonArray[0].jsonObject
        assertEquals("call-1", r["id"]!!.jsonPrimitive.content)
        assertEquals("list_timers", r["name"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), r["response"]!!.jsonObject["ok"])
    }

    @Test
    fun parsesSetupComplete() {
        assertEquals(listOf(ServerEvent.SetupComplete), LiveProtocol.parse("""{"setupComplete":{}}"""))
    }

    @Test
    fun parsesAudioTranscriptsAndTurnCompleteInOrder() {
        val pcm = byteArrayOf(10, 20, 30, 40)
        val b64 = Base64.getEncoder().encodeToString(pcm)
        val events = LiveProtocol.parse(
            """{"serverContent":{
                 "inputTranscription":{"text":"Wie spät"},
                 "modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64"}}]},
                 "outputTranscription":{"text":"Es ist"},
                 "turnComplete":true}}"""
        )
        assertEquals(ServerEvent.InputTranscript("Wie spät"), events[0])
        assertArrayEquals(pcm, (events[1] as ServerEvent.Audio).pcm)
        assertEquals(ServerEvent.OutputTranscript("Es ist"), events[2])
        assertEquals(ServerEvent.TurnComplete, events[3])
    }

    @Test
    fun parsesInterrupted() {
        assertEquals(listOf(ServerEvent.Interrupted), LiveProtocol.parse("""{"serverContent":{"interrupted":true}}"""))
    }

    @Test
    fun parsesToolCallWithArgs() {
        val events = LiveProtocol.parse(
            """{"toolCall":{"functionCalls":[{"id":"a1","name":"set_timer","args":{"duration_seconds":300,"label":"Nudeln"}}]}}"""
        )
        val call = (events.single() as ServerEvent.ToolCall).calls.single()
        assertEquals("a1", call.id)
        assertEquals("set_timer", call.name)
        assertEquals(JsonPrimitive(300), call.args["duration_seconds"])
    }

    @Test
    fun parsesToolCallWithoutArgs() {
        val call = (LiveProtocol.parse("""{"toolCall":{"functionCalls":[{"id":"b","name":"list_timers"}]}}""")
            .single() as ServerEvent.ToolCall).calls.single()
        assertEquals(JsonObject(emptyMap()), call.args)
    }

    @Test
    fun parsesCancellationGoAwayAndUsageOnly() {
        assertEquals(
            listOf(ServerEvent.ToolCallCancellation(listOf("a1", "a2"))),
            LiveProtocol.parse("""{"toolCallCancellation":{"ids":["a1","a2"]}}"""),
        )
        assertEquals(listOf(ServerEvent.GoAway("10s")), LiveProtocol.parse("""{"goAway":{"timeLeft":"10s"}}"""))
        assertEquals(emptyList<ServerEvent>(), LiveProtocol.parse("""{"usageMetadata":{"totalTokenCount":5}}"""))
    }

    @Test
    fun invalidJsonYieldsError() {
        assertTrue(LiveProtocol.parse("not json").single() is ServerEvent.Error)
    }
}
