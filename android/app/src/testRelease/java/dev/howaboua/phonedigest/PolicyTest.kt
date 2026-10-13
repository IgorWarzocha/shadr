package dev.howaboua.phonedigest

import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    @Test fun authenticationContentIsOmitted() {
        assertEquals("" to "", Policy.redact("com.azure.authenticator", "Approval", "Secret ABCDEF", emptySet()))
        assertEquals("" to "", Policy.redact("example.chat", "Your verification code", "A7B2C9", emptySet()))
        assertEquals("" to "", Policy.redact("example.chat", "Contact", "123 456", emptySet()))
    }
    @Test fun ordinaryContentIsNotBlanketRemoved() {
        assertEquals("Hello" to "Meet at 18:30", Policy.redact("com.chase.sig.android", "Hello", "Meet at 18:30", emptySet()))
    }
    @Test fun releaseRejectsPublicAndLoopbackHttp() {
        for (endpoint in listOf("http://example.com", "http://127.0.0.1:8787")) {
            assertThrows(IllegalArgumentException::class.java) { Policy.validate(endpoint, "phone", "Europe/London") }
        }
    }
    @Test fun cancellationOnlyFailsAfterItsConfirmationWindow() {
        assertEquals(ClearingAction.WAIT, Policy.clearingAction(true, true, false, 100, 1))
        assertEquals(ClearingAction.CANCEL, Policy.clearingAction(true, true, false, Policy.CLEAR_RETRY_MILLIS, 1))
        assertEquals(ClearingAction.ACK_FAILED, Policy.clearingAction(true, true, false, Policy.CLEAR_CONFIRM_MILLIS, 2))
        assertEquals(ClearingAction.ACK_APPLIED, Policy.clearingAction(false, true, true, 100, 1))
        assertEquals(ClearingAction.ACK_NOT_FOUND, Policy.clearingAction(false, false, false, 0, 0))
    }
}
