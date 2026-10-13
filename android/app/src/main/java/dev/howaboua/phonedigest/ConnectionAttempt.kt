package dev.howaboua.phonedigest

object ConnectionDraft {
    fun manualPrefill(previous: ConnectionSettings): Pair<String, String> {
        val host = runCatching { java.net.URI(previous.endpoint).host?.lowercase() }.getOrNull()
        return if (host in setOf("127.0.0.1", "localhost", "10.0.2.2", "::1", "[::1]")) "" to ""
            else previous.endpoint to previous.token
    }
    fun manual(address: String, token: String, previous: ConnectionSettings, zone: String): ConnectionSettings {
        val endpoint = ServerAddress.normalize(address)
        val sameEndpoint = endpoint.trimEnd('/') == previous.endpoint.trimEnd('/')
        return ConnectionSettings(ConnectionMode.infer(endpoint), endpoint, token.trim(), zone,
            if (sameEndpoint) previous.accessId else "", if (sameEndpoint) previous.accessSecret else "")
    }
}

class ConnectionAttempt(val settings: ConnectionSettings) {
    fun authenticate(check: (ConnectionSettings) -> ConnectionResult = { ConnectionTest.check(it) }): ConnectionResult {
        return try {
            settings.validate()
            check(settings)
        } catch (_: IllegalArgumentException) {
            ConnectionResult(false, "These details don't look right. Try a new QR code or check the server address.")
        } catch (_: java.io.IOException) {
            ConnectionResult(false, transportFailureMessage())
        } catch (_: Exception) {
            ConnectionResult(false, "Can't reach your inbox. Is your computer or server on?")
        }
    }

    private fun transportFailureMessage(): String {
        val address = runCatching { java.net.URI(settings.endpoint) }.getOrNull()
        val host = address?.host.orEmpty()
        val tailnetIp = host.matches(Regex("[0-9]+(?:\\.[0-9]+){3}")) && Policy.isTailnet(host)
        val privateHostname = address?.scheme == "http" && host.lowercase().endsWith(".ts.net")
        return if (tailnetIp || privateHostname)
            "Can't reach your server. Is Tailscale on and signed into the same account?"
        else "Can't reach your inbox. Is your computer or server on?"
    }

    fun commit(result: ConnectionResult, stillCurrent: Boolean, save: (ConnectionSettings) -> Unit): Boolean {
        if (!result.connected || !stillCurrent) return false
        settings.validate()
        save(settings)
        return true
    }
}
