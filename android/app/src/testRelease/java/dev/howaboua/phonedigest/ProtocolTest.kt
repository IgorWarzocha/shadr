package dev.howaboua.phonedigest

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtocolTest {
    @Test fun rejectsUncorrelatedDismissCommands() {
        val reply = JSONObject("""{"dismiss":["key"],"wouldDismiss":[],"dryRun":false,"requestIds":{}}""")
        assertThrows(JSONException::class.java) { Protocol.response(reply) }
    }
}
