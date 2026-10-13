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
        .putLong("lastPost", 0).putInt("itemCount", 0).putString("postState", "IDLE")
    private fun saveIdentity(key: String, value: String) = synchronized(stateLock) {
        if (prefs.getString(key, "") != value) check(resetConnection(prefs.edit().putString(key, value)).commit())
    }
    constructor(context: Context) : this(context.getSharedPreferences("inbox", Context.MODE_PRIVATE))
    init { synchronized(stateLock) {
        val obsolete = listOf("live", "allowClearing", "installedAt", "zone", "noise", "protected", "smsPackages", "serverLive")
        if (obsolete.any(prefs::contains)) {
            val edit = prefs.edit()
            obsolete.forEach(edit::remove)
            check(edit.commit())
        }
    } }
    var endpoint: String
        get() = prefs.getString("endpoint", "")!!
        set(value) { saveIdentity("endpoint", value) }
    val mode: ConnectionMode get() = ConnectionMode.infer(endpoint)
    val connection: ConnectionSettings get() = ConnectionSettings(mode, endpoint, token, zone, accessId, accessSecret)
    val configured: Boolean get() = endpoint.isNotBlank() && token.isNotBlank()
    fun saveSettings(settings: ConnectionSettings) = synchronized(stateLock) {
        settings.validate()
        val edit = prefs.edit().putString("endpoint", settings.endpoint.trimEnd('/')).putString("token", settings.token)
            .putString("accessId", settings.accessId).putString("accessSecret", settings.accessSecret)
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
    var extraAuthenticators: Set<String>
        get() = prefs.getStringSet("authenticators", emptySet())!!.toSet()
        set(value) { synchronized(stateLock) { check(prefs.edit().putStringSet("authenticators", value).commit()) } }
    var paused: Boolean
        get() = prefs.getBoolean("paused", false)
        set(value) { synchronized(stateLock) { check(prefs.edit().putBoolean("paused", value).commit()) } }
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
        require(status in setOf("dismissed", "missing"))
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
    private fun save(key: String, value: String) { check(prefs.edit().putString(key, value).commit()) }
}
