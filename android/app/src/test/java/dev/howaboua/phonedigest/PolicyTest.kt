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
    @Test fun weekNeverAutomaticallyEnablesLive() {
        assertFalse(Policy.liveAllowed(false, 1000, 1000 + Policy.WEEK * 20))
        assertFalse(Policy.liveAllowed(true, 1000, 999 + Policy.WEEK))
        assertTrue(Policy.liveAllowed(true, 1000, 1000 + Policy.WEEK))
        assertFalse(Policy.liveAllowed(true, 1000, 999))
        assertFalse(Policy.liveAllowed(true, 0, Policy.WEEK * 20))
    }
    @Test fun serverAndPhoneMustBothAllowLive() {
        val keys = listOf("key")
        assertEquals(emptyList<String>(), Policy.liveQueue(keys, true, true))
        assertEquals(emptyList<String>(), Policy.liveQueue(keys, false, false))
        assertEquals(keys, Policy.liveQueue(keys, false, true))
    }
    @Test fun authenticatorDigitsRemovedFromTitleAndBody() {
        val result = Policy.redact("example.auth", "Bank 123456", "Account 456: 789012", setOf("example.auth"))
        assertEquals("Bank ", result.first)
        assertEquals("Account : ", result.second)
    }
    @Test fun customAuthenticatorRedactsUnicodeDigits() {
        assertEquals("Code ", Policy.redact("local.auth", "", "Code １２３４５６", setOf("local.auth")).second)
    }
    @Test fun clearSmsLoginCodesAreRedacted() {
        val result = Policy.redact("example.sms", "Bank", "Your verification code is 123456", emptySet(), setOf("example.sms"))
        assertEquals("Bank", result.first)
        assertEquals("Your verification code is ", result.second)
    }
    @Test fun qualifiedSmsGroupedCodesAreRedacted() {
        for (text in listOf("Your verification code is 123 456", "Your login code is 123-456", "OTP: １２３ ４５６")) {
            assertFalse(Policy.redact("example.sms", "Bank", text, emptySet(), setOf("example.sms")).second.any { it.isDigit() })
            assertFalse(Policy.redact("example.chat", "Contact", text, emptySet()).second.any { it.isDigit() })
        }
    }
    @Test fun personMessagesAndGateCodesRemainIntact() {
        val text = "Your verification code is 123456. Meet at 18:30"
        assertFalse(Policy.redact("example.chat", "Contact", text, emptySet()).second.contains("123456"))
        assertEquals("Gate code [code]", Policy.redact("example.sms", "Contact", "Gate code 1234", emptySet()).second)
        assertEquals("Verify gate [code]", Policy.redact("example.sms", "Contact", "Verify gate 1234", emptySet()).second)
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
        )) {
            assertThrows(IllegalArgumentException::class.java) { Policy.validate(endpoint, token, zone) }
        }
    }
    @Test fun clearLoginContextRedactsGroupedAndMixedCodesAcrossApps() {
        for (code in listOf("12 34 56", "A7B2C9", "１２ ３４ ５６")) {
            val result = Policy.redact("example.chat", "Your verification code", code, emptySet())
            assertFalse(result.first.any { it.isDigit() })
            assertFalse(result.second.any { it.isDigit() })
        }
        assertEquals("Meet at 18:30", Policy.redact("example.chat", "Hello", "Meet at 18:30", emptySet()).second)
    }
    @Test fun builtinAndPlatformProtectionCannotBeOverridden() {
        assertTrue(Policy.protected("com.azure.authenticator", false, false, emptySet()))
        assertTrue(Policy.protected("com.chase.sig.android", false, false, emptySet()))
        assertTrue(Policy.protected("example.system", true, false, emptySet()))
        assertTrue(Policy.protected("example.admin", false, true, emptySet()))
        assertFalse(Policy.protected("example.game", false, false, emptySet()))
        assertEquals("Meet at 18:30", Policy.redact("example.chat", "", "Meet at 18:30", emptySet()).second)
        assertEquals("OTP ", Policy.redact("com.azure.authenticator", "", "OTP 123", emptySet()).second)
    }
    @Test fun loopbackHttpIsOnlyAcceptedByDebugBuilds() {
        for (host in listOf("127.0.0.1", "localhost", "10.0.2.2")) {
            if (BuildConfig.DEBUG) Policy.validate("http://$host:8799", "phone", "Europe/London")
            else assertThrows(IllegalArgumentException::class.java) { Policy.validate("http://$host:8799", "phone", "Europe/London") }
        }
    }
    @Test fun noPackageIsImplicitlyRedacted() {
        assertEquals("Code [code]", Policy.redact("example.auth", "", "Code 123456", emptySet()).second)
    }
}
