package dev.howaboua.phonedigest

import org.json.JSONTokener

object PairingPayload {
    private val jsonString = "\"(?:[^\"\\\\\\x00-\\x1f]|\\\\(?:[\"\\\\/bfnrt]|u[0-9a-fA-F]{4}))*\""
    private val space = "[ \\t\\r\\n]*"
    private val member = "$jsonString$space:$space(?:$jsonString|1)"
    private val flatObject = Regex("$space\\{$space$member(?:$space,$space$member)*$space\\}$space")
    fun parse(raw: String, zone: String): ConnectionSettings {
        try {
            require(raw.toByteArray(Charsets.UTF_8).size <= 2048)
            // Android's JSONTokener alone accepts non-JSON quoting and escapes.
            require(flatObject.matches(raw))
            val input = JSONTokener(raw)
            require(input.nextClean() == '{')
            val values = mutableMapOf<String, Any>()
            while (true) {
                require(input.nextClean() == '"')
                val key = input.nextString('"')
                require(key !in values && key in setOf("type", "version", "endpoint", "phoneToken", "accessClientId", "accessClientSecret"))
                require(input.nextClean() == ':')
                val first = input.nextClean()
                val value: Any = if (first == '"') input.nextString('"') else {
                    require(key == "version" && first == '1')
                    1
                }
                values[key] = value
                when (input.nextClean()) {
                    '}' -> break
                    ',' -> Unit
                    else -> throw IllegalArgumentException()
                }
            }
            require(input.nextClean() == '\u0000')
            require(values["type"] == "phone-inbox" && values["version"] == 1)
            fun string(key: String, max: Int): String {
                val value = values[key] as? String ?: throw IllegalArgumentException()
                require(value.isNotBlank() && value.length <= max && value.none { it.isISOControl() })
                return value
            }
            val endpoint = string("endpoint", 2048)
            val token = string("phoneToken", 1024)
            require(("accessClientId" in values) == ("accessClientSecret" in values))
            val accessId = if ("accessClientId" in values) string("accessClientId", 1024) else ""
            val accessSecret = if ("accessClientSecret" in values) string("accessClientSecret", 2048) else ""
            val settings = ConnectionSettings(ConnectionMode.infer(endpoint), endpoint, token, zone, accessId, accessSecret)
            require(accessId.isEmpty() || settings.mode == ConnectionMode.CLOUDFLARE)
            settings.validate()
            return settings
        } catch (_: Exception) {
            // Never echo QR contents or parser exceptions containing credentials.
            throw IllegalArgumentException("Invalid pairing QR. Generate a new shadr pairing code.")
        }
    }
}
