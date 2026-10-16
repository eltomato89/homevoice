package de.homevoice.assistant.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationGuardTest {
    private fun args(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun extractsMessageFromTheVariantsGeminiUses() {
        assertEquals("Gute Nacht, Jens!", notificationText(args("""{"domain":"notify","service":"notify","data":"{\"message\": \"Gute Nacht, Jens!\"}"}""")))
        assertEquals("Hallo", notificationText(args("""{"domain":"notify","service":"send_message","entity_id":"notify.iphone","data":{"message":"Hallo"}}""")))
        assertEquals("Hi", notificationText(args("""{"service":"notify.mobile_app_iphone","message":"Hi"}""")))
        assertNull(notificationText(args("""{"domain":"light","service":"turn_on","entity_id":"light.kueche"}""")))
    }

    @Test
    fun blocksTheSameMessageWithinThreeMinutes() {
        NotificationGuard.clear()
        assertTrue(NotificationGuard.firstSend("Gute Nacht, Jens! Schlaf gut.", now = 0))
        assertFalse(NotificationGuard.firstSend("gute nacht jens schlaf gut", now = 60_000))
        assertTrue(NotificationGuard.firstSend("Etwas anderes", now = 61_000))
        assertTrue(NotificationGuard.firstSend("Gute Nacht, Jens! Schlaf gut.", now = 4 * 60_000))
    }
}
