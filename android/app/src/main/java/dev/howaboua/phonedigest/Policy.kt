package dev.howaboua.phonedigest

import java.net.URI
import java.time.ZoneId

object Policy {
    const val WEEK = 7L * 24 * 60 * 60 * 1000
    const val MAX_SEQUENCE = 9_007_199_254_740_991L
    private val login = Regex("(?i)(?:(?:verification|security|authentication|one[ -]?time|login|log[ -]?in|sign[ -]?in)\\s+(?:code|password|pin)|\\botp\\b|(?:code|passcode).{0,40}(?:to log[ -]?in|to sign[ -]?in|to authenticate))")
    private val code = Regex("(?<!\\p{N})(?:\\p{N}{4,8}|\\p{N}{2,4}[ -]\\p{N}{2,4})(?!\\p{N})")
    @Suppress("UNUSED_PARAMETER") // Retained SMS overrides protect existing configuration.
    fun redact(pkg: String, title: String, text: String, extraAuthenticators: Set<String>, smsPackages: Set<String> = emptySet()): Pair<String, String> {
        val authenticator = pkg in extraAuthenticators || pkg in builtinAuthenticators || login.containsMatchIn("$title $text")
        fun clean(value: String) = if (authenticator) value.replace(Regex("\\p{N}"), "") else value.replace(code, "[code]")
        return clean(title) to clean(text)
    }
    val builtinAuthenticators = setOf("com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy", "com.yubico.yubioath", "com.beemdevelopment.aegis", "org.fedorahosted.freeotp", "com.twofasapp", "com.duosecurity.duomobile")
    val builtinProtected = builtinAuthenticators + setOf("com.chase.sig.android", "com.infonow.bofa", "com.wf.wellsfargomobile", "com.citi.citimobile", "com.barclays.android.barclaysmobilebanking", "com.hsbc.hsbcukmobilebanking", "com.revolut.revolut", "com.monzo.android", "com.starlingbank.android", "com.paypal.android.p2pmobile")
    fun protected(pkg: String, system: Boolean, admin: Boolean, overrides: Set<String>): Boolean =
        system || admin || pkg in builtinProtected || pkg in overrides
    fun liveAllowed(enabled: Boolean, installedAt: Long, now: Long): Boolean =
        enabled && installedAt > 0 && now >= installedAt && now - installedAt >= WEEK
    fun <T> liveQueue(keys: List<T>, serverDryRun: Boolean, localLive: Boolean): List<T> =
        if (serverDryRun || !localLive) emptyList() else keys
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
