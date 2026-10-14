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
import android.os.SystemClock
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class Exchange(private val context: Context, private val store: Store, private val active: () -> Boolean = { true }) {
    companion object {
        private val deadlines = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    }
    private data class Capture(val payload: ByteArray, val listener: ShadeListener?, val count: Int, val outcomes: JSONArray, val pushToken: String?, val privacyRevision: Long)
    fun send(session: ConnectionSession = store.session()) {
        if (sendAttempt(session, recoverConflict = true)) sendAttempt(session, recoverConflict = false)
    }
    private fun sendAttempt(session: ConnectionSession, recoverConflict: Boolean): Boolean {
        val capture = synchronized(Store.stateLock) {
            check(active()) { "Send stopped" }
            if (!store.ifCurrent(session) {}) return false
            check(store.configured) { "Inbox connection required" }
            session.settings.validate()
            val accessGranted = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
            val listener = if (accessGranted) ShadeListener.bound else null
            if (listener != null) resolvePending(listener)
            val excluded = store.excludedApps
            val shade = listener?.activeNotifications?.filterNot { it.packageName in excluded || CommandNotice.isOperational(context, it) } ?: emptyList()
            val isBound = listener != null && ShadeListener.bound === listener
            check(shade.size <= 1000) { "Shade exceeds inbox limit" }
            if (isBound) store.lastListenerAt = Instant.now().toString()
            val lastListenerAt = store.lastListenerAt
            val outcomes = AppPrivacy.outcomes(store.outcomes(), excluded)
            val pushToken = if (BackgroundAccess.notificationsEnabled(context)) store.pushToken else null
            val body = Protocol.snapshotHealth(accessGranted, isBound, lastListenerAt, store.zone)
                .put("sequence", store.reserveSequence()).put("capturedAt", Instant.now().toString())
                .put("notifications", JSONArray(if (isBound) shade.map(::notification) else emptyList<JSONObject>()))
                .put("outcomes", outcomes)
                .put("pushToken", pushToken ?: JSONObject.NULL)
            check(outcomes.length() <= 1000) { "Acknowledgements exceed inbox limit" }
            val payload = body.toString().toByteArray(Charsets.UTF_8)
            check(payload.size <= 2_000_000) { "Shade exceeds inbox byte limit" }
            Capture(payload, listener, shade.size, outcomes, pushToken, store.privacyRevision)
        }
        val uri = URI(session.settings.endpoint.trimEnd('/') + "/notifications")
        val connection = uri.toURL().openConnection() as HttpURLConnection
        val deadline = deadlines.schedule({ connection.disconnect() }, 20, TimeUnit.SECONDS)
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            // Do not let HttpURLConnection defer a buffered body until after uploadLock is released.
            connection.setFixedLengthStreamingMode(capture.payload.size)
            connection.setRequestProperty("Content-Type", "application/json")
            session.settings.authenticate(connection)
            AppPrivacy.write(capture.privacyRevision, { store.privacyRevision }) {
                check(active()) { "Send stopped" }
                connection.outputStream.use { it.write(capture.payload) }
            }
            val code = connection.responseCode
            if (code == 409 && recoverConflict) {
                recoverSequence(session)
                // The connection closes in finally before a new shade and sequence are captured.
                return true
            }
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
                if (!active()) return@ifCurrent
                val settled = mutableSetOf<Pair<String, String>>()
                for (receipts in listOf(capture.outcomes, store.outcomes())) {
                    for (index in 0 until receipts.length()) {
                        val receipt = receipts.getJSONObject(index)
                        settled.add(receipt.getString("requestId") to receipt.getString("key"))
                    }
                }
                val incoming = reply.dismiss.filterNot { it.requestId to it.key in settled || AppPrivacy.excludesKey(it.key, store.excludedApps) }
                store.pending = (store.pending + incoming).distinct()
                // Confirmation can finish while HTTP is in flight; clear only the sent receipts.
                store.removeSentOutcomes(capture.outcomes)
                if (capture.privacyRevision == store.privacyRevision) store.posted(capture.count)
                else Posting.schedule(context)
                store.pushSent(capture.pushToken)
                if (ShadeListener.bound === capture.listener && capture.listener != null) resolvePending(capture.listener)
            }
            if (!applied) Posting.schedule(context)
            return false
        } finally { deadline.cancel(false); connection.disconnect() }
    }

    private fun recoverSequence(session: ConnectionSession) {
        val connection = URI(session.settings.endpoint.trimEnd('/') + "/health").toURL().openConnection() as HttpURLConnection
        val deadline = deadlines.schedule({ connection.disconnect() }, 20, TimeUnit.SECONDS)
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
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
        } finally { deadline.cancel(false); connection.disconnect() }
    }

    fun confirmPending(session: ConnectionSession) = synchronized(Store.stateLock) {
        if (!store.ifCurrent(session) {}) return@synchronized
        if (!active() || !store.configured) return@synchronized
        val listener = ShadeListener.bound
        if (listener != null && context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)) resolvePending(listener)
    }

    private fun resolvePending(listener: ShadeListener) {
        val pending = store.pending
        for (command in pending) {
            val key = command.key
            check(ShadeListener.bound === listener && context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)) { "Notification access unavailable" }
            check(active()) { "Send stopped" }
            val current = listener.activeNotifications.firstOrNull { it.key == key }
            if (current?.packageName in store.excludedApps || AppPrivacy.excludesKey(key, store.excludedApps)) {
                store.pending = store.pending.filterNot { it == command }
                continue
            }
            val now = SystemClock.elapsedRealtime()
            val attempt = store.clearAttempt(command)?.takeIf { it.startedAt <= now }
            val age = if (attempt == null) 0 else now - attempt.startedAt
            val status = when (Policy.clearingAction(current != null, command.identity in store.attempted, store.paused, age, attempt?.tries ?: 0)) {
                ClearingAction.ACK_APPLIED -> "applied"
                ClearingAction.ACK_NOT_FOUND -> "not_found"
                ClearingAction.ACK_FAILED -> "failed"
                ClearingAction.WAIT -> {
                    continue
                }
                ClearingAction.CANCEL -> {
                    store.recordClearAttempt(command, now)
                    listener.cancelNotification(key)
                    // cancelNotification is asynchronous. Only acknowledge observed absence.
                    if (listener.activeNotifications.any { it.key == key }) {
                        continue
                    }
                    "applied"
                }
            }
            store.acknowledge(command, status)
        }
        // Short command work owns the confirmation window; no timer or persistent service is needed.
    }
    fun finishPending(session: ConnectionSession) {
        val deadline = SystemClock.elapsedRealtime() + Policy.CLEAR_CONFIRM_MILLIS + 250
        while (active() && SystemClock.elapsedRealtime() < deadline) {
            if (!store.ifCurrent(session) {}) return
            confirmPending(session)
            val waiting = synchronized(Store.stateLock) { store.pending.any { store.clearAttempt(it) != null } }
            if (!waiting) return
            Thread.sleep(250)
        }
        confirmPending(session)
    }

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
        val redacted = Policy.redact(sbn.packageName, title, text, store.extraAuthenticators)
        val appName = try { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { sbn.packageName }
        check(sbn.key.length <= 1024 && sbn.packageName.length <= 255 && appName.length <= 512 && redacted.first.length <= 8192 && redacted.second.length <= 32768) { "Notification exceeds inbox field limit" }
        return JSONObject().put("key", sbn.key).put("package", sbn.packageName)
            .put("appName", appName).put("title", redacted.first).put("text", redacted.second)
    }
}
