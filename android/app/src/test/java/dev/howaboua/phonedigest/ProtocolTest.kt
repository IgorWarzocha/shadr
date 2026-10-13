package dev.howaboua.phonedigest

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun snapshotReportsOsGrantIndependentlyOfTemporaryBinding() {
        val starting = Protocol.snapshotHealth(true, false, null, "Europe/London")
        assertTrue(starting.getBoolean("notificationAccessGranted"))
        assertFalse(starting.getBoolean("listenerBound"))
        assertTrue(starting.isNull("lastListenerAt"))
        assertEquals("Europe/London", starting.getString("timeZone"))
        val revoked = Protocol.snapshotHealth(false, true, "2026-01-01T00:00:00Z", "Europe/Paris")
        assertFalse(revoked.getBoolean("notificationAccessGranted"))
        assertFalse(revoked.getBoolean("listenerBound"))
        val ready = Protocol.snapshotHealth(true, true, "2026-01-01T00:00:00Z", "Europe/Paris")
        assertTrue(ready.getBoolean("listenerBound"))
    }
    @Test fun parsesCommandsWithMatchingOpaqueRequestIds() {
        val response = Protocol.response(JSONObject("""{"dismiss":["key"],"wouldDismiss":[],"dryRun":false,"requestIds":{"key":"request-id"}}"""))
        assertEquals(listOf(DismissCommand("key", "request-id")), response.dismiss)
        assertFalse(response.dryRun)
        assertEquals(response.dismiss, Protocol.decode(Protocol.encode(response.dismiss)))
    }
    @Test fun rejectsUncorrelatedMalformedAndDuplicateCommands() {
        for (text in listOf(
            """{"dismiss":["key"],"wouldDismiss":[],"dryRun":false,"requestIds":{}}""",
            """{"dismiss":["key"],"wouldDismiss":[],"dryRun":false,"requestIds":{"key":""}}""",
            """{"dismiss":["key"],"wouldDismiss":[],"dryRun":false,"requestIds":{"key":123}}""",
            """{"dismiss":["key","key"],"wouldDismiss":[],"dryRun":false,"requestIds":{"key":"id"}}""",
            """{"dismiss":["a","b"],"wouldDismiss":[],"dryRun":false,"requestIds":{"a":"same","b":"same"}}""",
            """{"dismiss":[],"wouldDismiss":[],"dryRun":"false","requestIds":{}}"""
        )) {
            assertThrows(Exception::class.java) { Protocol.response(JSONObject(text)) }
        }
        assertThrows(IllegalArgumentException::class.java) { DismissCommand("key", "x".repeat(129)) }
    }
    @Test fun wouldDismissNeverBecomesAnExecutableCommand() {
        val reply = Protocol.response(JSONObject("""{"dismiss":[],"wouldDismiss":["key"],"dryRun":true,"requestIds":{"key":"id"}}"""))
        assertTrue(reply.dismiss.isEmpty())
        assertEquals(listOf(DismissCommand("key", "id")), reply.wouldDismiss)
    }
    @Test fun legacyDryRunFlagDoesNotVetoCorrelatedDismissCommands() {
        val reply = Protocol.response(JSONObject("""{"dismiss":["key"],"wouldDismiss":[],"dryRun":true,"requestIds":{"key":"id"}}"""))
        assertEquals(listOf(DismissCommand("key", "id")), reply.dismiss)
        assertTrue(reply.dryRun)
    }
    @Test fun identityCannotConfuseReusedKeysAndOpaqueIds() {
        assertNotEquals(DismissCommand("c", "ab").identity, DismissCommand("bc", "a").identity)
        assertNotEquals(DismissCommand("key", "old").identity, DismissCommand("key", "new").identity)
    }
}
