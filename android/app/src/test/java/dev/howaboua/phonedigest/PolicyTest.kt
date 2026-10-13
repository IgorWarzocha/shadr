package dev.howaboua.phonedigest

import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    @Test fun sequencesArePositiveSafeAndNeverWrap() {
        assertEquals(1L, Policy.nextSequence(0))
        assertEquals(101L, Policy.nextSequence(100))
        assertEquals(Policy.MAX_SEQUENCE, Policy.nextSequence(Policy.MAX_SEQUENCE - 1))
        assertThrows(IllegalStateException::class.java) { Policy.nextSequence(Policy.MAX_SEQUENCE) }
        assertThrows(IllegalStateException::class.java) { Policy.nextSequence(-1) }
    }
    @Test fun onlyUserPauseStopsNewCancellationButDoesNotHideObservedOutcomes() {
        assertEquals(ClearingAction.CANCEL, Policy.clearingAction(true, false, false))
        assertEquals(ClearingAction.CANCEL, Policy.clearingAction(true, true, false))
        assertEquals(ClearingAction.WAIT, Policy.clearingAction(true, false, true))
        assertEquals(ClearingAction.WAIT, Policy.clearingAction(true, true, true))
        assertEquals(ClearingAction.ACK_DISMISSED, Policy.clearingAction(false, true, true))
        assertEquals(ClearingAction.ACK_MISSING, Policy.clearingAction(false, false, true))
    }
    @Test fun allKnownAuthenticatorContentIsSuppressedIncludingLetterSecrets() {
        for (pkg in Policy.builtinAuthenticators + setOf("custom.auth")) {
            assertEquals("" to "", Policy.redact(pkg, "Approval", "Secret ABCDEF with no digits", setOf("custom.auth")))
        }
    }
    @Test fun otpContextSuppressesWholeNotificationAcrossApps() {
        for (code in listOf("123456", "123 456", "123-456", "12 34 56", "A7B2C9", "ABCDEF", "１２ ３４ ５６")) {
            for (label in listOf("OTP", "2FA", "MFA", "Your verification code", "Your login code", "Sign-in confirmation")) {
                assertEquals("" to "", Policy.redact("example.chat", label, code, emptySet()))
            }
        }
    }
    @Test fun numericCodeCandidatesSuppressContentEvenWithoutLoginWording() {
        for (text in listOf("Reference 620391", "Gate code 1234", "Verify 123 456", "Digits １２３４５６")) {
            assertEquals("" to "", Policy.redact("example.sms", "Contact", text, emptySet()))
        }
    }
    @Test fun ordinaryBankSystemAdminAndChatContentStillRelays() {
        for (pkg in listOf("com.chase.sig.android", "com.android.systemui", "example.admin", "example.chat")) {
            assertEquals("Hello" to "Meet at 18:30", Policy.redact(pkg, "Hello", "Meet at 18:30", emptySet()))
        }
    }
    @Test fun cleartextRestrictedToTailnetAndTimeZoneValidated() {
        Policy.validate("http://100.64.0.1:8787", "phone-token", "Europe/London")
        Policy.validate("https://inbox.example", "phone-token", "Europe/London")
        for ((endpoint, token, zone) in listOf(
            Triple("http://example.com", "token", "Europe/London"),
            Triple("http://100.128.0.1", "token", "Europe/London"),
            Triple("https://user:secret@example.com", "token", "Europe/London"),
            Triple("https://example.com?token=x", "token", "Europe/London"),
            Triple("https://example.com/notifications", "token", "Europe/London"),
            Triple("https://example.com", "", "Europe/London"),
            Triple("https://example.com", "token", "London")
        )) assertThrows(IllegalArgumentException::class.java) { Policy.validate(endpoint, token, zone) }
    }
    @Test fun loopbackHttpIsOnlyAcceptedByDebugBuilds() {
        for (host in listOf("127.0.0.1", "localhost", "10.0.2.2")) {
            if (BuildConfig.DEBUG) Policy.validate("http://$host:8799", "phone", "Europe/London")
            else assertThrows(IllegalArgumentException::class.java) { Policy.validate("http://$host:8799", "phone", "Europe/London") }
        }
    }
}
