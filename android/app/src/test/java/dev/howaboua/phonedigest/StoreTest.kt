package dev.howaboua.phonedigest

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class StoreTest {
    @Test fun upgradePreservesConfigurationWithDefaultProtection() {
        val prefs = MemoryPreferences()
        val old = Store(prefs)
        old.endpoint = "http://100.64.0.1"
        old.token = "saved-token"
        old.extraAuthenticators = setOf("example.auth")
        old.extraProtected = setOf("example.bank")
        old.liveEnabled = true
        assertTrue(Store(prefs).configured)
        assertFalse(Store(prefs).liveAllowed)
        prefs.failCommits = true
        assertThrows(IllegalStateException::class.java) { old.saveSettings(old.connection, old.extraProtected, old.extraAuthenticators, emptySet(), emptySet(), false) }
        assertTrue(Store(prefs).configured)
        prefs.failCommits = false
        old.saveSettings(old.connection, old.extraProtected, old.extraAuthenticators, emptySet(), emptySet(), false)
        assertTrue(Store(prefs).configured)
        assertEquals(setOf("example.auth"), Store(prefs).extraAuthenticators)
        assertEquals(setOf("example.bank"), Store(prefs).extraProtected)
    }
    @Test fun unsafeExistingNoiseIsExcludedAndStatusIsStructured() {
        val prefs = MemoryPreferences()
        val store = Store(prefs)
        store.noise = setOf("com.azure.authenticator", "example.game", "example.bank")
        store.extraProtected = setOf("example.bank")
        assertEquals(setOf("example.game"), Store(prefs).noise)
        assertEquals(0L, store.lastPost)
        store.posted(7)
        assertEquals(7, Store(prefs).itemCount)
        assertTrue(Store(prefs).lastPost > 0)
    }
    @Test fun setupOnlyAdvancesWithRealConfigurationAndAccess() {
        assertEquals(SetupStep.CONNECT, SetupFlow.initial(false, NotificationAccess.REQUIRED))
        assertEquals(SetupStep.CONNECT, SetupFlow.initial(false, NotificationAccess.CONNECTED))
        assertEquals(SetupStep.ALLOW, SetupFlow.initial(true, NotificationAccess.REQUIRED))
        assertEquals(SetupStep.MAIN, SetupFlow.initial(true, NotificationAccess.CONNECTED))
        assertEquals(SetupStep.ALLOW, SetupFlow.connected(NotificationAccess.REQUIRED))
        assertEquals(SetupStep.DONE, SetupFlow.connected(NotificationAccess.CONNECTED))
    }
    @Test fun grantedAccessWithUnboundListenerColdStartsOnMain() {
        val coldLaunch = SetupFlow.access(granted = true, bound = false)
        assertEquals(NotificationAccess.WAITING_FOR_LISTENER, coldLaunch)
        assertEquals(SetupStep.MAIN, SetupFlow.initial(true, coldLaunch))
        assertEquals(SetupStep.CONNECT, SetupFlow.initial(false, coldLaunch))
        assertEquals(SetupStep.DONE, SetupFlow.connected(coldLaunch))
        assertEquals(NotificationAccess.CONNECTED, SetupFlow.access(granted = true, bound = true))
        assertEquals(NotificationAccess.REQUIRED, SetupFlow.access(granted = false, bound = true))
    }
    @Test fun upgradeInfersModeWithoutErasingValues() {
        val prefs = MemoryPreferences()
        val store = Store(prefs)
        store.endpoint = "https://inbox.example"
        store.accessId = "saved-id"
        store.accessSecret = "saved-secret"
        store.extraProtected = setOf("example.bank")
        assertEquals(ConnectionMode.CLOUDFLARE, Store(prefs).mode)
        val local = ConnectionSettings(ConnectionMode.TAILNET, "http://100.64.0.1", "token", "Europe/London", store.accessId, store.accessSecret)
        store.saveSettings(local, store.extraProtected, emptySet(), emptySet(), emptySet(), false)
        assertEquals(ConnectionMode.TAILNET, Store(prefs).mode)
        assertEquals("saved-secret", Store(prefs).accessSecret)
        assertEquals(setOf("example.bank"), Store(prefs).extraProtected)
    }
    @Test fun invalidDraftAndFailedSaveLeavePreviousSettingsIntact() {
        val prefs = MemoryPreferences()
        val store = Store(prefs)
        val local = ConnectionSettings(ConnectionMode.TAILNET, "http://100.64.0.1", "token", "Europe/London")
        store.saveSettings(local, emptySet(), emptySet(), emptySet(), emptySet(), false)
        assertThrows(IllegalArgumentException::class.java) { store.saveSettings(local.copy(token = ""), emptySet(), emptySet(), emptySet(), emptySet(), false) }
        assertThrows(IllegalArgumentException::class.java) { store.saveSettings(local, setOf("example.bank"), emptySet(), emptySet(), setOf("example.bank"), false) }
        prefs.failCommits = true
        assertThrows(IllegalStateException::class.java) { store.saveSettings(local.copy(token = "changed"), emptySet(), emptySet(), emptySet(), emptySet(), false) }
        assertEquals("token", Store(prefs).token)
        assertTrue(Store(prefs).noise.isEmpty())
    }

    @Test fun changingInboxDiscardsCommandsAndRejectsSupersededResponse() {
        val store = Store(MemoryPreferences())
        val a = ConnectionSettings(ConnectionMode.CLOUDFLARE, "https://a.example", "a-phone", "Europe/London", "a-access", "a-secret")
        store.saveSettings(a, emptySet(), emptySet(), emptySet(), emptySet(), false)
        val oldSession = store.session()
        store.pending = listOf(DismissCommand("old", "old-request"))
        store.attempted = setOf("old-attempt")
        store.acknowledge(DismissCommand("old", "old-request"), "dismissed")
        store.serverLive = true
        store.posted(5)
        store.saveSettings(a.copy(endpoint = "https://b.example", token = "b-phone"), emptySet(), emptySet(), emptySet(), emptySet(), false)
        assertTrue(store.pending.isEmpty())
        assertTrue(store.attempted.isEmpty())
        assertEquals(0, store.outcomes().length())
        assertFalse(store.serverLive)
        assertEquals(0L, store.lastPost)
        var cancellationCalled = false
        assertFalse(store.ifCurrent(oldSession) {
            store.pending = listOf(DismissCommand("new", "bad-old-response"))
            store.serverLive = true
            cancellationCalled = true
        })
        assertFalse(cancellationCalled)
        assertTrue(store.pending.isEmpty())
        assertFalse(store.serverLive)
        val request = object : java.net.HttpURLConnection(java.net.URL(oldSession.settings.endpoint)) {
            override fun connect() {} ; override fun disconnect() {} ; override fun usingProxy() = false
        }
        oldSession.settings.authenticate(request)
        assertEquals("Bearer a-phone", request.getRequestProperty("Authorization"))
        assertEquals("a-secret", request.getRequestProperty("CF-Access-Client-Secret"))
        assertEquals("a.example", request.url.host)
    }
    @Test fun onlyConnectionIdentityChangesInvalidateSession() {
        val store = Store(MemoryPreferences())
        val a = ConnectionSettings(ConnectionMode.CLOUDFLARE, "https://a.example", "phone", "Europe/London")
        store.saveSettings(a, emptySet(), emptySet(), emptySet(), emptySet(), false)
        val session = store.session()
        store.pending = listOf(DismissCommand("old", "request"))
        store.saveSettings(a.copy(zone = "Europe/Paris"), emptySet(), emptySet(), emptySet(), emptySet(), false)
        assertTrue(store.ifCurrent(session) {})
        assertEquals(1, store.pending.size)
        store.saveSettings(a.copy(token = "new-phone"), emptySet(), emptySet(), emptySet(), emptySet(), false)
        assertFalse(store.ifCurrent(session) {})
        assertTrue(store.pending.isEmpty())
    }

    @Test fun systemTimeZoneReplacesOldOverrideAndTracksPhoneChanges() {
        val previous = java.util.TimeZone.getDefault()
        try {
            val prefs = MemoryPreferences()
            prefs.edit().putString("zone", "Asia/Tokyo").commit()
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/London"))
            val store = Store(prefs)
            assertEquals("Europe/London", store.zone)
            assertFalse(prefs.contains("zone"))
            val settings = ConnectionSettings(ConnectionMode.CLOUDFLARE, "https://example.com", "phone", "Asia/Tokyo")
            store.saveSettings(settings, emptySet(), emptySet(), emptySet(), emptySet(), false)
            assertFalse(prefs.contains("zone"))
            assertEquals("Europe/London", store.connection.zone)
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Paris"))
            assertEquals("Europe/Paris", store.connection.zone)
        } finally { java.util.TimeZone.setDefault(previous) }
    }

    @Test fun requestsReserveNewDurableSequencesAcrossRestarts() {
        val prefs = MemoryPreferences()
        assertEquals(1L, Store(prefs).reserveSequence())
        assertEquals(2L, Store(prefs).reserveSequence())
        Store(prefs).raiseSequence(100)
        assertEquals(101L, Store(prefs).reserveSequence())
        Store(prefs).raiseSequence(1)
        assertEquals(102L, Store(prefs).reserveSequence())
    }
    @Test fun failedCommitCannotSendAnUnreservedSequence() {
        val prefs = MemoryPreferences()
        val store = Store(prefs)
        prefs.failCommits = true
        assertThrows(IllegalStateException::class.java) { store.reserveSequence() }
        prefs.failCommits = false
        assertEquals(1L, Store(prefs).reserveSequence())
    }
    @Test fun replayedAcknowledgementsStayUniqueAndSurviveRestart() {
        val prefs = MemoryPreferences()
        Store(prefs).acknowledge(DismissCommand("a", "request-a"), "dismissed")
        Store(prefs).acknowledge(DismissCommand("b", "request-b"), "protected")
        Store(prefs).acknowledge(DismissCommand("a", "request-a"), "dismissed")
        val outcomes = Store(prefs).outcomes()
        assertEquals(2, outcomes.length())
        assertEquals(setOf("a", "b"), (0 until outcomes.length()).map { outcomes.getJSONObject(it).getString("key") }.toSet())
        Store(prefs).clearOutcomes()
        assertEquals(0, Store(prefs).outcomes().length())
    }
    @Test fun failedAcknowledgementAndClearDoNotLoseExistingEntries() {
        val prefs = MemoryPreferences()
        val store = Store(prefs)
        store.acknowledge(DismissCommand("a", "request-a"), "dismissed")
        prefs.failCommits = true
        assertThrows(IllegalStateException::class.java) { store.acknowledge(DismissCommand("b", "request-b"), "missing") }
        assertThrows(IllegalStateException::class.java) { store.clearOutcomes() }
        assertEquals("a", Store(prefs).outcomes().getJSONObject(0).getString("key"))
        assertEquals(1, Store(prefs).outcomes().length())
    }
    @Test fun pendingIntentAndGatesSurviveProcessRestartWithoutAutoEnabling() {
        val prefs = MemoryPreferences()
        val first = Store(prefs)
        val command = DismissCommand("key", "request-id")
        first.pending = listOf(command)
        first.attempted = setOf(command.identity)
        first.serverLive = true
        val restarted = Store(prefs)
        assertEquals(listOf(command), restarted.pending)
        assertEquals(setOf(command.identity), restarted.attempted)
        assertTrue(restarted.serverLive)
        assertFalse(restarted.liveEnabled)
        assertFalse(restarted.liveAllowed)
        assertEquals(first.installedAt, restarted.installedAt)
    }
    @Test fun separateLifecyclesOfSameKeyRetainBothAcknowledgements() {
        val prefs = MemoryPreferences()
        val store = Store(prefs)
        store.acknowledge(DismissCommand("key", "old-request"), "dismissed")
        store.acknowledge(DismissCommand("key", "new-request"), "missing")
        assertEquals(2, Store(prefs).outcomes().length())
        assertEquals(setOf("old-request", "new-request"), (0..1).map { store.outcomes().getJSONObject(it).getString("requestId") }.toSet())
    }
}

private class MemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    var failCommits = false
    override fun contains(key: String) = values.containsKey(key)
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<*>)?.map { it as String }?.toMutableSet() ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (failCommits) return false
            if (clear) values.clear()
            for ((key, value) in changes) if (value == null) values.remove(key) else values[key] = value
            return true
        }
        override fun apply() { check(commit()) }
    }
}
