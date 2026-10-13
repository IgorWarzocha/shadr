package dev.howaboua.phonedigest

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.io.ByteArrayOutputStream
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class Exchange(private val context: Context, private val store: Store) {
    private data class Capture(val payload: ByteArray, val listener: ShadeListener?, val count: Int)
    fun send(session: ConnectionSession = store.session()) {
        val capture = synchronized(Store.stateLock) {
            if (!store.ifCurrent(session) {}) return
            check(store.configured) { "Inbox connection required" }
            session.settings.validate()
            val accessGranted = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
            val listener = if (accessGranted) ShadeListener.bound else null
            if (listener != null) resolvePending(listener)
            val shade = listener?.activeNotifications?.toList() ?: emptyList()
            val isBound = listener != null && ShadeListener.bound === listener
            check(shade.size <= 1000) { "Shade exceeds inbox limit" }
            if (isBound) store.lastListenerAt = Instant.now().toString()
            val lastListenerAt = store.lastListenerAt
            val body = Protocol.snapshotHealth(accessGranted, isBound, lastListenerAt, store.zone)
                .put("sequence", store.reserveSequence()).put("capturedAt", Instant.now().toString())
                .put("notifications", JSONArray(if (isBound) shade.map(::notification) else emptyList<JSONObject>()))
                .put("outcomes", store.outcomes())
            check(store.outcomes().length() <= 1000) { "Acknowledgements exceed inbox limit" }
            val payload = body.toString().toByteArray(Charsets.UTF_8)
            check(payload.size <= 2_000_000) { "Shade exceeds inbox byte limit" }
            Capture(payload, listener, shade.size)
        }
        val uri = URI(session.settings.endpoint.trimEnd('/') + "/notifications")
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            session.settings.authenticate(connection)
            connection.outputStream.use { it.write(capture.payload) }
            val code = connection.responseCode
            if (code == 409) recoverSequence(session)
            check(code in 200..299) { "HTTP failure" }
            val response = connection.inputStream.use { stream ->
                val bytes = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = stream.read(chunk)
                    if (count < 0) break
                    check(bytes.size() + count <= 1_048_576) { "Response too large" }
                    bytes.write(chunk, 0, count)
                }
                JSONObject(bytes.toString("UTF-8"))
            }
            val reply = Protocol.response(response)
            // Persist commands before forgetting acknowledgements. A crash may resend an acknowledgement,
            // but never loses it. Recovery rechecks the shade before any cancellation.
            val applied = store.ifCurrent(session) {
                store.serverLive = !reply.dryRun
                val incoming = Policy.liveQueue(reply.dismiss, reply.dryRun, store.liveAllowed)
                store.pending = (store.pending + incoming).distinct()
                store.clearOutcomes()
                store.posted(capture.count)
                if (ShadeListener.bound === capture.listener && capture.listener != null) resolvePending(capture.listener)
            }
            if (!applied) Posting.schedule(context)
        } finally { connection.disconnect() }
    }

    private fun recoverSequence(session: ConnectionSession) {
        val connection = URI(session.settings.endpoint.trimEnd('/') + "/health").toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            session.settings.authenticate(connection)
            check(connection.responseCode == 200) { "Sequence recovery failed" }
            val text = connection.inputStream.bufferedReader().use { reader ->
                val chars = CharArray(65_537)
                var size = 0
                while (size < chars.size) {
                    val read = reader.read(chars, size, chars.size - size)
                    if (read < 0) break
                    size += read
                }
                check(size <= 65_536) { "Health response too large" }
                String(chars, 0, size)
            }
            val sequence = JSONObject(text).get("sequence")
            require(sequence is Number && sequence.toLong() in 0..Policy.MAX_SEQUENCE && sequence.toDouble() == sequence.toLong().toDouble()) { "Invalid server sequence" }
            store.ifCurrent(session) { store.raiseSequence(sequence.toLong()) }
        } finally { connection.disconnect() }
    }

    private fun resolvePending(listener: ShadeListener) {
        val pending = store.pending
        val remaining = pending.toMutableList()
        for (command in pending) {
            val key = command.key
            check(ShadeListener.bound === listener && context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)) { "Notification access unavailable" }
            val current = listener.activeNotifications.firstOrNull { it.key == key }
            val status = when {
                current == null -> if (command.identity in store.attempted) "dismissed" else "missing"
                isProtected(current.packageName) || current.packageName !in store.noise -> "protected"
                else -> {
                    if (!store.liveAllowed || !store.serverLive) continue
                    store.attempted = store.attempted + command.identity
                    listener.cancelNotification(key)
                    // cancelNotification is asynchronous. Only acknowledge observed absence.
                    if (listener.activeNotifications.any { it.key == key }) continue
                    "dismissed"
                }
            }
            store.acknowledge(command, status)
            remaining.remove(command)
            store.pending = remaining
            store.attempted = store.attempted - command.identity
        }
        if (pending.isNotEmpty()) Posting.schedule(context)
    }

    private fun isProtected(pkg: String) = AppProtection(context, store).contains(pkg)

    private fun notification(sbn: StatusBarNotification): JSONObject {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification)?.messages
            ?.joinToString("\n") { message ->
                val sender = message.person?.name
                "${sender ?: ""}: ${message.text}"
            }.orEmpty()
        val text = listOf(
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n"), messages
        ).filterNotNull().filter { it.isNotEmpty() }.distinct().joinToString("\n")
        val redacted = Policy.redact(sbn.packageName, title, text, store.extraAuthenticators, store.smsPackages)
        val appName = try { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { sbn.packageName }
        check(sbn.key.length <= 1024 && sbn.packageName.length <= 255 && appName.length <= 512 && redacted.first.length <= 8192 && redacted.second.length <= 32768) { "Notification exceeds inbox field limit" }
        return JSONObject().put("key", sbn.key).put("package", sbn.packageName)
            .put("appName", appName).put("title", redacted.first).put("text", redacted.second)
    }
}
