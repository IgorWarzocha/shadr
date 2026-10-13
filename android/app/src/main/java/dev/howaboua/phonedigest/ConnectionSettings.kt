package dev.howaboua.phonedigest

import java.net.HttpURLConnection
import java.net.URI
import org.json.JSONObject

enum class ConnectionMode { TAILNET, CLOUDFLARE;
    companion object {
        fun infer(endpoint: String): ConnectionMode = if (Policy.isTailnet(runCatching { URI(endpoint).host }.getOrNull().orEmpty()) || (BuildConfig.DEBUG && runCatching { URI(endpoint).host }.getOrNull() in setOf("localhost", "127.0.0.1", "10.0.2.2"))) TAILNET else CLOUDFLARE
    }
}

object ServerAddress {
    fun normalize(input: String): String {
        val address = input.trim()
        // A dotted hostname with a numeric port is not a URI scheme.
        val hostPort = Regex("^[^:/]+\\.[^:/]+:[0-9]+/?$").matches(address)
        if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(address) && !hostPort) return address
        val host = runCatching { URI("https://$address").host }.getOrNull().orEmpty()
        val scheme = if (Policy.isTailnet(host)) "http" else "https"
        return "$scheme://$address"
    }
}

data class ConnectionSettings(
    val mode: ConnectionMode, val endpoint: String, val token: String, val zone: String,
    val accessId: String = "", val accessSecret: String = ""
) {
    fun validate() {
        Policy.validate(endpoint, token, zone)
        if (mode == ConnectionMode.CLOUDFLARE) {
            require(URI(endpoint).scheme == "https") { "Cloudflare requires HTTPS" }
            require(accessId.isBlank() == accessSecret.isBlank()) { "Supply both Access credentials, or leave both empty" }
            require(!(accessId + accessSecret).any { it == '\r' || it == '\n' }) { "Invalid Access credentials" }
        } else {
            require(ConnectionMode.infer(endpoint) == ConnectionMode.TAILNET) { "Use a tailnet IP address or .ts.net hostname" }
        }
    }
    fun authenticate(connection: HttpURLConnection) {
        connection.setRequestProperty("Authorization", "Bearer $token")
        if (mode == ConnectionMode.CLOUDFLARE) {
            connection.setRequestProperty("CF-Access-Client-Id", accessId)
            connection.setRequestProperty("CF-Access-Client-Secret", accessSecret)
        }
    }
}

data class ConnectionResult(val connected: Boolean, val message: String)

object ConnectionTest {
    fun check(settings: ConnectionSettings, open: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection }): ConnectionResult {
        settings.validate()
        val connection = open(URI(settings.endpoint.trimEnd('/') + "/health"))
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            settings.authenticate(connection)
            return when (connection.responseCode) {
                200 -> verifyPhoneRole(connection)
                401, 403 -> ConnectionResult(false, "Your inbox didn't accept these details. Try a new QR code.")
                in 300..399 -> ConnectionResult(false, "This address didn't connect to your inbox. Try a new QR code.")
                else -> ConnectionResult(false, "Can't reach your inbox. Is your computer or server on?")
            }
        } finally { connection.disconnect() }
    }
    private fun verifyPhoneRole(connection: HttpURLConnection): ConnectionResult {
        val unverified = ConnectionResult(false, "This doesn't look like your inbox. Try a new QR code.")
        return try {
            val body = connection.inputStream.bufferedReader().use { reader ->
                val chars = CharArray(65_537)
                var size = 0
                while (size < chars.size) {
                    val count = reader.read(chars, size, chars.size - size)
                    if (count < 0) break
                    size += count
                }
                if (size > 65_536) return unverified
                String(chars, 0, size)
            }
            when (JSONObject(body).opt("role")) {
                "phone" -> ConnectionResult(true, "Connected to your inbox.")
                "bot" -> ConnectionResult(false, "These details aren't for your phone. Try a new QR code.")
                else -> unverified
            }
        } catch (_: org.json.JSONException) { unverified }
    }
}
