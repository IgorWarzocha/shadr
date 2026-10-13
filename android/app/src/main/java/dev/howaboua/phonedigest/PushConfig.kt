package dev.howaboua.phonedigest

import org.json.JSONObject
import com.google.firebase.FirebaseOptions

data class PushConfig(val projectId: String, val applicationId: String, val apiKey: String, val senderId: String) {
    fun json() = JSONObject().put("projectId", projectId).put("applicationId", applicationId).put("apiKey", apiKey).put("senderId", senderId)
    fun options() = FirebaseOptions.Builder().setProjectId(projectId).setApplicationId(applicationId).setApiKey(apiKey).setGcmSenderId(senderId).build()
    companion object {
        fun parse(value: JSONObject): PushConfig {
            fun field(name: String): String {
                val text = value.get(name)
                require(text is String && text.isNotBlank() && text.length <= 256 && text.none { it.isWhitespace() })
                return text
            }
            val config = PushConfig(field("projectId"), field("applicationId"), field("apiKey"), field("senderId"))
            require(config.senderId.matches(Regex("[0-9]+")) && config.applicationId.startsWith("1:${config.senderId}:android:"))
            require(config.apiKey.matches(Regex("A[\\w-]{38}")))
            return config
        }
    }
}
