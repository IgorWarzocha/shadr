package dev.howaboua.phonedigest

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class ConnectionAttemptTest {
    private val old = ConnectionSettings(ConnectionMode.CLOUDFLARE, "https://old.example", "old-phone", "Europe/London", "private-id", "private-secret")
    private val qr = """{"type":"phone-inbox","version":1,"endpoint":"https://new.example","phoneToken":"new-phone"}"""

    @Test fun automaticQrOnlyCommitsAfterVerifiedPhoneResponse() {
        val settings = PairingPayload.parse(qr, "Europe/London")
        for (role in listOf("phone", "bot", "unknown")) {
            var saved = old
            val attempt = ConnectionAttempt(settings)
            val result = attempt.authenticate { ConnectionTest.check(it) { HealthConnection(role) } }
            assertEquals(role == "phone", attempt.commit(result, true) { saved = it })
            assertEquals(if (role == "phone") settings else old, saved)
        }
    }
    @Test fun failedUnreachableInvalidOrSupersededQrLeavesExistingConnectionAlone() {
        val settings = PairingPayload.parse(qr, "Europe/London")
        var saved = old
        val attempt = ConnectionAttempt(settings)
        for (result in listOf(
            attempt.authenticate { throw java.io.IOException("private-secret") },
            attempt.authenticate { ConnectionResult(false, "Not accepted") }
        )) {
            assertFalse(attempt.commit(result, true) { saved = it })
            assertFalse(result.message.contains("private-secret"))
        }
        assertFalse(attempt.commit(attempt.authenticate { ConnectionResult(true, "Connected") }, false) { saved = it })
        var checked = false
        val invalid = ConnectionAttempt(settings.copy(endpoint = "http://new.example"))
        assertFalse(invalid.authenticate { checked = true; ConnectionResult(true, "Connected") }.connected)
        assertFalse(checked)
        assertEquals(old, saved)
    }
    @Test fun hiddenAccessCredentialsStayOnExactExistingEndpointOnly() {
        val unchanged = ConnectionDraft.manual("old.example/", "new-phone", old, old.zone)
        assertEquals(old.accessId, unchanged.accessId)
        assertEquals(old.accessSecret, unchanged.accessSecret)
        for (endpoint in listOf("https://new.example", "https://old.example:8443", "http://old.example", "http://100.64.0.1:8787", "https://old.example/path", "https://old.example.evil.example")) {
            val draft = ConnectionDraft.manual(endpoint, "new-phone", old, old.zone)
            assertEquals("", draft.accessId)
            assertEquals("", draft.accessSecret)
            val request = HealthConnection("phone")
            draft.authenticate(request)
            assertTrue(request.getRequestProperty("CF-Access-Client-Secret").isNullOrEmpty())
        }
        assertEquals("", PairingPayload.parse(qr, old.zone).accessSecret)
    }
    @Test fun transportFailureSuggestsTailscaleOnlyForValidatedPrivateTailnetAddresses() {
        for (endpoint in listOf("http://100.64.0.1:8787", "http://100.127.255.255:8787", "https://100.64.0.1", "https://100.127.255.255", "http://phone.example.ts.net", "http://PHONE.EXAMPLE.TS.NET:8787")) {
            val attempt = ConnectionAttempt(old.copy(mode = ConnectionMode.TAILNET, endpoint = endpoint, accessId = "", accessSecret = ""))
            val result = attempt.authenticate { throw java.net.ConnectException("private address") }
            assertEquals("Can't reach your server. Is Tailscale on and signed into the same account?", result.message)
            val reached = attempt.authenticate { ConnectionResult(false, "Your inbox didn't accept these details. Try a new QR code.") }
            assertEquals("Your inbox didn't accept these details. Try a new QR code.", reached.message)
            val serverError = attempt.authenticate { ConnectionResult(false, "Can't reach your inbox. Is your computer or server on?") }
            assertEquals("Can't reach your inbox. Is your computer or server on?", serverError.message)
            for (code in listOf(401, 403, 500)) {
                val reply = attempt.authenticate { ConnectionTest.check(it) { HealthConnection("phone", code) } }
                assertFalse(reply.connected)
                assertFalse(reply.message.contains("Tailscale"))
            }
            assertFalse(attempt.authenticate { throw IllegalStateException() }.message.contains("Tailscale"))
        }
        for (endpoint in listOf("https://example.com", "https://100.128.0.1", "https://100.63.255.255", "https://phone.example.ts.net", "https://phone.example.ts.net:8443", "https://phone.example.ts.net:10000") +
            if (BuildConfig.DEBUG) listOf("http://127.0.0.1:8799") else emptyList()) {
            val attempt = ConnectionAttempt(old.copy(mode = ConnectionMode.infer(endpoint), endpoint = endpoint, accessId = "", accessSecret = ""))
            val result = attempt.authenticate { throw java.net.SocketTimeoutException() }
            assertEquals("Can't reach your inbox. Is your computer or server on?", result.message)
        }
        for (endpoint in listOf("http://100.128.0.1", "http://100.63.255.255", "http://100.64.999.1")) {
            var contacted = false
            val attempt = ConnectionAttempt(old.copy(mode = ConnectionMode.infer(endpoint), endpoint = endpoint, accessId = "", accessSecret = ""))
            val result = attempt.authenticate { contacted = true; throw java.net.ConnectException() }
            assertFalse(contacted)
            assertFalse(result.message.contains("Tailscale"))
        }
    }
    @Test fun manualPrefillRetainsRealSettingsButDoesNotExposeLocalCaptureFixtures() {
        assertEquals(old.endpoint to old.token, ConnectionDraft.manualPrefill(old))
        val private = old.copy(endpoint = "http://100.64.0.1:8787")
        assertEquals(private.endpoint to private.token, ConnectionDraft.manualPrefill(private))
        assertEquals("" to "", ConnectionDraft.manualPrefill(old.copy(endpoint = "", token = "")))
        for (endpoint in listOf("http://127.0.0.1:8799", "http://localhost:8799", "http://LOCALHOST:8799", "http://10.0.2.2:8799", "https://[::1]:8799")) {
            assertEquals("" to "", ConnectionDraft.manualPrefill(old.copy(endpoint = endpoint, token = "capture-fixture")))
        }
    }
    @Test fun publicHttpsFunnelWorksFromQrAndManualWithBearerOnlyAndNoPhoneVpnAdvice() {
        val endpoint = "https://relay.example.ts.net:10000"
        val payload = """{"type":"phone-inbox","version":1,"endpoint":"$endpoint","phoneToken":"public-phone"}"""
        val imported = PairingPayload.parse(payload, "Europe/London")
        val manual = ConnectionDraft.manual(endpoint, "public-phone", old, "Europe/London")
        for (settings in listOf(imported, manual)) {
            assertEquals(endpoint, settings.endpoint)
            assertEquals("", settings.accessId)
            assertEquals("", settings.accessSecret)
            val attempt = ConnectionAttempt(settings)
            val connection = HealthConnection("phone")
            val result = attempt.authenticate {
                ConnectionTest.check(it) { uri ->
                    assertEquals("https", uri.scheme)
                    assertEquals("relay.example.ts.net", uri.host)
                    assertEquals(10000, uri.port)
                    assertEquals("/health", uri.path)
                    connection
                }
            }
            assertTrue(result.connected)
            assertEquals("Bearer public-phone", connection.getRequestProperty("Authorization"))
            assertNull(connection.getRequestProperty("CF-Access-Client-Id"))
            assertNull(connection.getRequestProperty("CF-Access-Client-Secret"))
            var saved: ConnectionSettings? = null
            assertTrue(attempt.commit(result, true) { saved = it })
            assertEquals(settings, saved)
            val unreachable = attempt.authenticate { throw java.net.ConnectException() }
            assertFalse(unreachable.message.contains("Tailscale"))
            assertEquals("Can't reach your inbox. Is your computer or server on?", unreachable.message)
        }
    }
    private class HealthConnection(private val role: String, private val code: Int = 200) : HttpURLConnection(URL("https://example.com/health")) {
        override fun getResponseCode() = code
        override fun getInputStream() = """{"role":"$role"}""".byteInputStream()
        override fun connect() {} ; override fun disconnect() {} ; override fun usingProxy() = false
    }
}
