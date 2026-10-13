package dev.howaboua.phonedigest

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val store = Store(this)
        val config = store.pushConfig ?: return
        if (!store.configured || message.data["type"] != "sync" || message.from != config.senderId) return
        CommandNotice.show(this)
        Posting.schedule(this, command = true, expedited = message.priority == RemoteMessage.PRIORITY_HIGH)
    }
    override fun onNewToken(token: String) {
        // A delayed callback may belong to an old project. Fetch the current app's token instead.
        PushRegistration.refresh(this)
    }
}
