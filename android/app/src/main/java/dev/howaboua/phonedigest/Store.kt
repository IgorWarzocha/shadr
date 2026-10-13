package dev.howaboua.phonedigest

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

data class ConnectionSession(val settings: ConnectionSettings, val revision: Long)

enum class PostState { IDLE, POSTED, FAILED }

class Store(private val prefs: SharedPreferences) {
    companion object { internal val stateLock = Any() }
    fun session(): ConnectionSession = synchronized(stateLock) { ConnectionSession(connection, prefs.getLong("connectionRevision", 0)) }
    fun ifCurrent(session: ConnectionSession, action: () -> Unit): Boolean = synchronized(stateLock) {
        if (session.revision != prefs.getLong("connectionRevision", 0)) false else { action(); true }
    }
    private fun resetConnection(edit: SharedPreferences.Editor): SharedPreferences.Editor = edit
        .putLong("connectionRevision", prefs.getLong("connectionRevision", 0) + 1)
        .putString("pending", "[]").putString("outcomes", "[]").putStringSet("attempted", emptySet())
        .putBoolean("serverLive", false).putLong("lastPost", 0).putInt("itemCount", 0).putString("postState", "IDLE")
    private fun saveIdentity(key: String, value: String) = synchronized(stateLock) {
        if (prefs.getString(key, "") != value) check(resetConnection(prefs.edit().putString(key, value)).commit())
    }
    constructor(context: Context) : this(context.getSharedPreferences("inbox", Context.MODE_PRIVATE)) {
        synchronized(stateLock) {
            val allowed = AppProtection(context, this).allowedNoise()
            if (allowed != noise) noise = allowed
        }
    }
    init { synchronized(stateLock) {
        if (!prefs.contains("installedAt")) check(prefs.edit().putLong("installedAt", System.currentTimeMillis()).commit())
        if (prefs.contains("zone")) check(prefs.edit().remove("zone").commit())
        val oldNoise = prefs.getStringSet("noise", emptySet())!!.toSet()
        val safeNoise = oldNoise - Policy.builtinProtected - extraProtected - extraAuthenticators - smsPackages
        if (oldNoise != safeNoise) check(prefs.edit().putStringSet("noise", safeNoise).commit())
    } }
    var endpoint: String
        get() = prefs.getString("endpoint", "")!!
        set(value) { saveIdentity("endpoint", value) }
    val mode: ConnectionMode get() = ConnectionMode.infer(endpoint)
    val connection: ConnectionSettings get() = ConnectionSettings(mode, endpoint, token, zone, accessId, accessSecret)
    val configured: Boolean get() = endpoint.isNotBlank() && token.isNotBlank()
    var smsPackages: Set<String>
        get() = prefs.getStringSet("smsPackages", emptySet())!!.toSet()
        set(value) { synchronized(stateLock) { check(prefs.edit().putStringSet("smsPackages", value).commit()) } }
    fun saveSettings(settings: ConnectionSettings, protected: Set<String>, authenticators: Set<String>, sms: Set<String>, noise: Set<String>, live: Boolean) = synchronized(stateLock) {
        settings.validate()
        require(noise.intersect(protected + authenticators + sms + Policy.builtinProtected).isEmpty()) { "Protected or code-redaction packages cannot be noise" }
        val edit = prefs.edit().putString("endpoint", settings.endpoint.trimEnd('/')).putString("token", settings.token)
            .remove("zone").putString("accessId", settings.accessId)
            .putString("accessSecret", settings.accessSecret).putStringSet("protected", protected)
            .putStringSet("authenticators", authenticators).putStringSet("smsPackages", sms)
            .putStringSet("noise", noise).putBoolean("live", live)
        if (connection.copy(zone = "", endpoint = endpoint.trimEnd('/')) != settings.copy(zone = "", endpoint = settings.endpoint.trimEnd('/'))) resetConnection(edit)
        check(edit.commit()) { "Could not save settings" }
    }
    var token: String
        get() = prefs.getString("token", "")!!
        set(value) { saveIdentity("token", value) }
    val zone: String get() = ZoneId.systemDefault().id
    var accessId: String
        get() = prefs.getString("accessId", "")!!
        set(value) { saveIdentity("accessId", value) }
    var accessSecret: String
        get() = prefs.getString("accessSecret", "")!!
        set(value) { saveIdentity("accessSecret", value) }
    var extraProtected: Set<String>
        get() = prefs.getStringSet("protected", emptySet())!!.toSet()
        set(value) { synchronized(stateLock) { check(prefs.edit().putStringSet("protected", value).commit()) } }
    var extraAuthenticators: Set<String>
        get() = prefs.getStringSet("authenticators", emptySet())!!.toSet()
        set(value) { synchronized(stateLock) { check(prefs.edit().putStringSet("authenticators", value).commit()) } }
    var noise: Set<String>
        get() = prefs.getStringSet("noise", emptySet())!!.toSet() - Policy.builtinProtected - extraProtected - extraAuthenticators - smsPackages
        set(value) { synchronized(stateLock) { check(prefs.edit().putStringSet("noise", value).commit()) } }
    var liveEnabled: Boolean
        get() = prefs.getBoolean("live", false)
        set(value) { synchronized(stateLock) { check(prefs.edit().putBoolean("live", value).commit()) } }
    val installedAt: Long get() = prefs.getLong("installedAt", 0)
    val liveAllowed: Boolean get() = configured && Policy.liveAllowed(liveEnabled, installedAt, System.currentTimeMillis())
    var lastListenerAt: String?
        get() = prefs.getString("lastListenerAt", null)
        set(value) { check(prefs.edit().putString("lastListenerAt", value).commit()) }
    val postState: PostState get() = PostState.valueOf(prefs.getString("postState", "IDLE")!!)
    fun postFailed() { save("postState", "FAILED") }
    val lastPost: Long get() = prefs.getLong("lastPost", 0)
    val itemCount: Int get() = prefs.getInt("itemCount", 0)
    fun posted(count: Int) { check(prefs.edit().putLong("lastPost", System.currentTimeMillis()).putInt("itemCount", count).putString("postState", "POSTED").commit()) }
    fun reserveSequence(): Long {
        val previous = prefs.getLong("sequence", 0)
        val next = Policy.nextSequence(previous)
        check(prefs.edit().putLong("sequence", next).commit())
        return next
    }
    fun outcomes(): JSONArray = JSONArray(prefs.getString("outcomes", "[]"))
    fun acknowledge(command: DismissCommand, status: String) {
        require(status in setOf("dismissed", "missing", "protected"))
        val old = outcomes()
        val entries = JSONArray()
        for (index in 0 until old.length()) {
            val outcome = old.getJSONObject(index)
            if (outcome.getString("requestId") != command.requestId) entries.put(outcome)
        }
        entries.put(JSONObject().put("key", command.key).put("requestId", command.requestId).put("status", status))
        check(prefs.edit().putString("outcomes", entries.toString()).commit())
    }
    fun raiseSequence(serverSequence: Long) {
        require(serverSequence in 0..Policy.MAX_SEQUENCE)
        check(prefs.edit().putLong("sequence", maxOf(prefs.getLong("sequence", 0), serverSequence)).commit())
    }
    fun clearOutcomes() { save("outcomes", "[]") }
    var pending: List<DismissCommand>
        get() = Protocol.decode(JSONArray(prefs.getString("pending", "[]")))
        set(value) { save("pending", Protocol.encode(value).toString()) }
    var attempted: Set<String>
        get() = prefs.getStringSet("attempted", emptySet())!!.toSet()
        set(value) { check(prefs.edit().putStringSet("attempted", value).commit()) }
    var serverLive: Boolean
        get() = prefs.getBoolean("serverLive", false)
        set(value) { check(prefs.edit().putBoolean("serverLive", value).commit()) }
    private fun save(key: String, value: String) { check(prefs.edit().putString(key, value).commit()) }
}
