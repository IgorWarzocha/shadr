package dev.howaboua.phonedigest

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.time.Instant

class ShadeListener : NotificationListenerService() {
    companion object {
        @Volatile var bound: ShadeListener? = null
            private set
    }
    override fun onListenerConnected() {
        bound = this
        observed()
        Posting.schedule(this, immediate = true)
    }
    override fun onListenerDisconnected() {
        bound = null
        Posting.schedule(this, immediate = true)
        requestRebind(android.content.ComponentName(this, ShadeListener::class.java))
    }
    override fun onDestroy() { if (bound === this) bound = null; super.onDestroy() }
    override fun onNotificationPosted(sbn: StatusBarNotification) { changed() }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { changed() }
    private fun changed() { observed(); Posting.schedule(this) }
    private fun observed() { Store(this).lastListenerAt = Instant.now().toString() }
}
