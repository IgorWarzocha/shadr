package dev.howaboua.phonedigest

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

object PushRegistration {
    private val lock = Any()
    private var fetching: Pair<Long, PushConfig>? = null
    fun initialize(context: Context, config: PushConfig?): FirebaseApp? = synchronized(lock) {
        val current = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
        if (config == null) { current?.delete(); return@synchronized null }
        if (current?.options == config.options()) return@synchronized current
        current?.delete()
        FirebaseApp.initializeApp(context, config.options()).apply { setDataCollectionDefaultEnabled(false) }
    }
    fun refresh(context: Context) {
        val store = Store(context)
        val session = store.session()
        val config = store.pushConfig ?: run { initialize(context, null); return }
        val app = initialize(context, config) ?: return
        val key = session.revision to config
        synchronized(lock) { if (fetching == key) return; fetching = key }
        try {
            val messaging = FirebaseMessaging.getInstance()
            messaging.isAutoInitEnabled = true
            messaging.token.addOnCompleteListener { task ->
                synchronized(lock) { if (fetching == key) fetching = null }
                store.ifCurrent(session) {
                    if (store.pushConfig != config || FirebaseApp.getApps(context).none { it === app }) return@ifCurrent
                    if (task.isSuccessful) {
                        val changed = store.pushToken != task.result
                        store.savePushToken(task.result)
                        if (changed) Posting.schedule(context)
                    } else store.pushFailed()
                }
            }
        } catch (_: Exception) {
            synchronized(lock) { if (fetching == key) fetching = null }
            store.ifCurrent(session) { if (store.pushConfig == config) store.pushFailed() }
        }
    }
}
