package dev.howaboua.phonedigest

import org.json.JSONArray
import org.json.JSONObject

data class DismissCommand(val key: String, val requestId: String) {
    init {
        require(key.isNotEmpty() && key.length <= 1024) { "Invalid dismissal key" }
        require(requestId.isNotBlank() && requestId.length <= 128) { "Invalid dismissal request ID" }
    }
    val identity: String get() = "${requestId.length}:$requestId$key"
}

data class PhoneReply(val dismiss: List<DismissCommand>, val wouldDismiss: List<DismissCommand>, val dryRun: Boolean)

object Protocol {
    fun snapshotHealth(notificationAccessGranted: Boolean, listenerBound: Boolean, lastListenerAt: String?, timeZone: String): JSONObject =
        JSONObject().put("notificationAccessGranted", notificationAccessGranted)
            .put("listenerBound", notificationAccessGranted && listenerBound)
            .put("lastListenerAt", lastListenerAt ?: JSONObject.NULL).put("timeZone", timeZone)

    fun response(json: JSONObject): PhoneReply {
        val ids = json.getJSONObject("requestIds")
        val dismiss = commands(json.getJSONArray("dismiss"), ids)
        val wouldDismiss = commands(json.getJSONArray("wouldDismiss"), ids)
        val dryRun = json.get("dryRun")
        require(dryRun is Boolean) { "Invalid dryRun" }
        val all = (dismiss + wouldDismiss).distinct()
        require(all.map { it.requestId }.distinct().size == all.size) { "Request ID reused for different keys" }
        return PhoneReply(dismiss, wouldDismiss, dryRun)
    }
    private fun commands(keys: JSONArray, ids: JSONObject): List<DismissCommand> {
        require(keys.length() <= 1000) { "Too many dismissal keys" }
        val result = (0 until keys.length()).map {
            val key = keys.get(it)
            require(key is String) { "Invalid dismissal key" }
            val id = ids.get(key)
            require(id is String) { "Invalid request ID" }
            DismissCommand(key, id)
        }
        require(result.distinct().size == result.size) { "Duplicate dismissal key" }
        return result
    }
    fun encode(commands: List<DismissCommand>): JSONArray = JSONArray(commands.map {
        JSONObject().put("key", it.key).put("requestId", it.requestId)
    })
    fun decode(json: JSONArray): List<DismissCommand> = (0 until json.length()).map {
        val command = json.getJSONObject(it)
        DismissCommand(command.getString("key"), command.getString("requestId"))
    }
}
