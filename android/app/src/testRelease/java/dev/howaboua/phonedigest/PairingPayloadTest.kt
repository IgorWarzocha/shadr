package dev.howaboua.phonedigest

import org.junit.Assert.assertThrows
import org.junit.Test

class PairingPayloadTest {
    @Test fun rejectsMalformedOrUnsafePairingCodes() {
        for (payload in listOf(
            "not a pairing code",
            """{"type":"phone-inbox","version":1,"endpoint":"http://example.com","phoneToken":"fake"}""",
            """{"type":"phone-inbox","version":1,"endpoint":"https://example.com","phoneToken":"fake","phoneToken":"other"}"""
        )) {
            assertThrows(IllegalArgumentException::class.java) { PairingPayload.parse(payload, "Europe/London") }
        }
    }
}
