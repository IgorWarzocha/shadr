package dev.howaboua.phonedigest

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class ConnectionSettingsTest {
    private val local = ConnectionSettings(ConnectionMode.TAILNET, "http://100.64.0.1:8787", "phone", "Europe/London", "retained", "retained-secret")
    @Test fun credentialsAreConditionalOnMode() {
        local.copy(accessId = "", accessSecret = "").validate()
        assertThrows(IllegalArgumentException::class.java) { local.copy(endpoint = "https://example.com").validate() }
        val cloud = local.copy(mode = ConnectionMode.CLOUDFLARE, endpoint = "https://example.com")
        cloud.validate()
        assertThrows(IllegalArgumentException::class.java) { cloud.copy(accessSecret = "").validate() }
        cloud.copy(accessId = "", accessSecret = "").validate()
        assertThrows(IllegalArgumentException::class.java) { cloud.copy(endpoint = "http://100.64.0.1").validate() }
        assertThrows(IllegalArgumentException::class.java) { local.copy(endpoint = "http://100.64.999.1").validate() }
    }
    @Test fun localModeDoesNotLeakRetainedAccessCredentials() {
        val connection = FakeConnection()
        local.authenticate(connection)
        assertEquals("Bearer phone", connection.getRequestProperty("Authorization"))
        assertNull(connection.getRequestProperty("CF-Access-Client-Secret"))
        local.copy(mode = ConnectionMode.CLOUDFLARE).authenticate(connection)
        assertEquals("retained-secret", connection.getRequestProperty("CF-Access-Client-Secret"))
    }
    @Test fun testOnlyReadsAuthenticatedHealthAndDisconnects() {
        val connection = FakeConnection()
        val result = ConnectionTest.check(local) { uri ->
            assertEquals("/health", uri.path)
            connection
        }
        assertEquals("GET", connection.requestMethod)
        assertFalse(connection.doOutput)
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
        assertTrue(result.message.startsWith("Connected"))
        connection.code = 302
        assertFalse(ConnectionTest.check(local) { connection }.connected)
    }
    @Test fun successRequiresBoundedJsonWithAuthenticatedPhoneRole() {
        val connection = FakeConnection()
        connection.body = """{"role":"bot"}"""
        assertFalse(ConnectionTest.check(local) { connection }.connected)
        for (body in listOf("<html>Login</html>", "{}", """{"role":true}""", """{"role":"unknown"}""", "x".repeat(65_537))) {
            connection.body = body
            assertFalse(ConnectionTest.check(local) { connection }.connected)
            assertTrue(connection.disconnected)
        }
    }
    @Test fun modeInferencePrioritizesActualTailnetRange() {
        assertEquals(ConnectionMode.TAILNET, ConnectionMode.infer("https://phone.example.ts.net"))
        assertEquals(ConnectionMode.TAILNET, ConnectionMode.infer("http://100.127.255.255"))
        assertEquals(ConnectionMode.CLOUDFLARE, ConnectionMode.infer("https://100.128.0.1"))
        assertEquals(ConnectionMode.CLOUDFLARE, ConnectionMode.infer("https://example.com"))
    }
    @Test fun schemelessAddressesUseHttpOnlyForRecognisedTailnetHosts() {
        for ((input, expected) in listOf(
            " 100.64.0.1:8787 " to "http://100.64.0.1:8787",
            "100.127.255.255" to "http://100.127.255.255",
            "phone.example.ts.net:8787" to "http://phone.example.ts.net:8787",
            "100.63.255.255:8787" to "https://100.63.255.255:8787",
            "100.128.0.1" to "https://100.128.0.1",
            "example.com:8787" to "https://example.com:8787",
            "phone.ts.net.example.com" to "https://phone.ts.net.example.com",
            "example.com" to "https://example.com"
        )) {
            val normalized = ServerAddress.normalize(input)
            assertEquals(expected, normalized)
            assertEquals(normalized, ServerAddress.normalize(normalized))
            local.copy(mode = ConnectionMode.infer(normalized), endpoint = normalized, accessId = "", accessSecret = "").validate()
        }
    }
    @Test fun explicitSchemesArePreservedAndMalformedAddressesStillFail() {
        for (address in listOf("https://100.64.0.1:8787", "http://example.com", "ftp://100.64.0.1", "https:123")) {
            assertEquals(address, ServerAddress.normalize(address))
        }
        for (input in listOf("", "100.64.999.1:8787", "phone..ts.net", ".ts.net", "//100.64.0.1:8787", "100.64.0.1:bad", "example.com/path", "user@example.com", "example.com?x=1", "example.com#fragment", "http://example.com", "ftp://100.64.0.1", "https:123")) {
            val normalized = ServerAddress.normalize(input)
            assertThrows(input, IllegalArgumentException::class.java) {
                local.copy(mode = ConnectionMode.infer(normalized), endpoint = normalized).validate()
            }
        }
    }
    private class FakeConnection : HttpURLConnection(URL("http://100.64.0.1")) {
        var code = 200
        var body = """{"role":"phone"}"""
        override fun getInputStream() = body.byteInputStream()
        var disconnected = false
        override fun getResponseCode() = code
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
    }

}
