package dev.howaboua.phonedigest

import org.junit.Assert.*
import org.junit.Test

class PairingPayloadTest {
    private val valid = """{"type":"phone-inbox","version":1,"endpoint":"http://100.64.0.1:8787","phoneToken":"fake-phone-token"}"""
    @Test fun acceptsSharedCliContractFixture() {
        val fixture = java.io.File("../../test/fixtures/pairing-v1.json").readText()
        val settings = PairingPayload.parse(fixture, "Europe/London")
        assertEquals("http://100.64.0.1:8787", settings.endpoint)
        assertEquals("pairing-fixture-phone-token", settings.token)
        assertEquals(ConnectionMode.TAILNET, settings.mode)
        assertEquals("", settings.accessId)
        assertEquals("", settings.accessSecret)
    }
    @Test fun importsValidatedDraftWithoutPersistenceOrNetwork() {
        val settings = PairingPayload.parse(valid, "Europe/London")
        assertEquals("http://100.64.0.1:8787", settings.endpoint)
        assertEquals("fake-phone-token", settings.token)
        assertEquals(ConnectionMode.TAILNET, settings.mode)
        assertEquals("", settings.accessSecret)
        val cloud = valid.replace("http://100.64.0.1:8787", "https://example.com").dropLast(1) +
            """, "accessClientId":"fake-id", "accessClientSecret":"fake-secret"}"""
        assertEquals("fake-secret", PairingPayload.parse(cloud, "Europe/London").accessSecret)
    }
    @Test fun rejectsAmbiguousUnsupportedMalformedOrUnsafePayloadsWithoutLeakingSecrets() {
        for (raw in listOf(
            valid.dropLast(1) + """, "phoneToken":"other"}""",
            valid.dropLast(1) + """, "botToken":"fake-bot"}""",
            valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("\"version\":1", "\"version\":1.0"),
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("phone-inbox", "other-app"),
            valid.replace("fake-phone-token", "contains space"),
            valid.replace("fake-phone-token", "x".repeat(1025)),
            valid.replace("http://100.64.0.1:8787", "100.64.0.1:8787"),
            valid.replace("http://100.64.0.1:8787", "http://example.com"),
            valid.dropLast(1) + """, "accessClientId":"fake-id"}""",
            valid.dropLast(1) + """, "accessClientId":"fake-id", "accessClientSecret":"fake-secret"}""",
            valid + "trailing", valid + " ".repeat(2048),
            valid.replace("fake-phone-token", "\\x41"), valid.replace('"', '\''),
            "{}", "[]", "https://example.com", valid.replace("\"phoneToken\":\"fake-phone-token\"", "\"phoneToken\":null")
        )) {
            val error = assertThrows(IllegalArgumentException::class.java) { PairingPayload.parse(raw, "Europe/London") }
            assertEquals("Invalid pairing QR. Generate a new Phone inbox pairing code.", error.message)
            assertNull(error.cause)
        }
    }
    @Test fun respectsUtf8ByteLimitNotJustCharacterCount() {
        val unicode = valid.replace("fake-phone-token", "界".repeat(700))
        assertTrue(unicode.length < 2048)
        assertThrows(IllegalArgumentException::class.java) { PairingPayload.parse(unicode, "Europe/London") }
    }
}
