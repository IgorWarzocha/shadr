package dev.howaboua.phonedigest

import java.net.URI
import java.time.ZoneId

enum class ClearingAction { ACK_MISSING, ACK_DISMISSED, WAIT, CANCEL }

object Policy {
    const val MAX_SEQUENCE = 9_007_199_254_740_991L
    private val login = Regex("(?i)(?:\\b(?:otp|2fa|mfa|code|passcode|pin|verification|authentication|authenticator|password)\\b|one[ -]?time|two[ -]?factor|log[ -]?in|sign[ -]?in)")
    private val code = Regex("(?<!\\p{N})(?:\\p{N}{4,8}|\\p{N}{2,4}[ -]\\p{N}{2,4})(?!\\p{N})")
    fun redact(pkg: String, title: String, text: String, extraAuthenticators: Set<String>): Pair<String, String> {
        val content = "$title $text"
        val sensitive = pkg in extraAuthenticators || pkg in builtinAuthenticators || login.containsMatchIn(content) || code.containsMatchIn(content)
        return if (sensitive) "" to "" else title to text
    }
    val builtinAuthenticators = setOf("com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy", "com.yubico.yubioath", "com.beemdevelopment.aegis", "org.fedorahosted.freeotp", "com.twofasapp", "com.duosecurity.duomobile")
    fun clearingAction(present: Boolean, attempted: Boolean, paused: Boolean) = when {
        !present -> if (attempted) ClearingAction.ACK_DISMISSED else ClearingAction.ACK_MISSING
        paused -> ClearingAction.WAIT
        else -> ClearingAction.CANCEL
    }
    fun nextSequence(previous: Long): Long {
        check(previous in 0 until MAX_SEQUENCE) { "Sequence exhausted or invalid" }
        return previous + 1
    }

    fun isTailnet(host: String): Boolean {
        if (host.lowercase().endsWith(".ts.net")) return true
        val parts = host.split('.').map { it.toIntOrNull() }
        return parts.size == 4 && parts.all { it != null && it in 0..255 } && parts[0] == 100 && parts[1] in 64..127
    }

    fun validate(endpoint: String, token: String, zone: String) {
        val uri = try { URI(endpoint) } catch (_: java.net.URISyntaxException) { throw IllegalArgumentException("Use a valid inbox address") }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && uri.host != null && uri.path in listOf("", "/")) { "Use an inbox address without credentials, path, query or fragment" }
        val tailnet = isTailnet(uri.host)
        require(uri.scheme == "https" || (uri.scheme == "http" && (tailnet || (BuildConfig.DEBUG && uri.host in setOf("127.0.0.1", "localhost", "10.0.2.2"))))) { "HTTPS required outside the tailnet" }
        require(token.isNotBlank() && !token.any { it.isWhitespace() }) { "Phone token required" }
        require(zone in ZoneId.getAvailableZoneIds()) { "Choose an IANA time zone" }
    }
}
