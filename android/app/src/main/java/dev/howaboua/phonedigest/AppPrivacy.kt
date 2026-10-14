package dev.howaboua.phonedigest

import org.json.JSONArray

/** Android notification keys contain user|package|id|tag|uid. Never send excluded keys as receipts. */
object AppPrivacy {
    // Always acquire before Store.stateLock. An already-started write precedes the confirmed save.
    internal val uploadLock = Any()
    fun write(expectedRevision: Long, currentRevision: () -> Long, body: () -> Unit) = synchronized(uploadLock) {
        check(expectedRevision == currentRevision()) { "App privacy choices changed" }
        body()
    }
    fun excludesKey(key: String, packages: Set<String>): Boolean = key.split('|').getOrNull(1) in packages
    fun outcomes(entries: JSONArray, packages: Set<String>): JSONArray {
        val kept = JSONArray()
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            if (!excludesKey(entry.getString("key"), packages)) kept.put(entry)
        }
        return kept
    }
}
