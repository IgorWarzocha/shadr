package dev.howaboua.phonedigest

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.*

class DigestApp : Application() {
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { Posting.schedule(this@DigestApp) }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) Posting.schedule(this@DigestApp)
        }
    }
    override fun onCreate() {
        super.onCreate()
        val store = Store(this)
        PushRegistration.initialize(this, store.pushConfig)
        val work = WorkManager.getInstance(this)
        work.cancelUniqueWork("heartbeat")
        work.cancelUniqueWork("changes")
        work.cancelUniqueWork("confirmation")
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
        PushRegistration.refresh(this)
        Posting.schedule(this)
    }
}

object Posting {
    val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    val flight = Any()
    private val normalQueue = FlushQueue()
    private val commandQueue = FlushQueue()
    fun queue(command: Boolean) = if (command) commandQueue else normalQueue
    fun schedule(context: Context, command: Boolean = false, expedited: Boolean = false) {
        val builder = OneTimeWorkRequestBuilder<PostWorker>().setConstraints(connected)
            .setInputData(workDataOf("command" to command))
        if (expedited) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        val request = builder.build()
        val id = request.id.toString()
        val queue = queue(command)
        queue.request(id) { policy ->
            val operation = WorkManager.getInstance(context).enqueueUniqueWork(if (command) "command_sync" else "flush", policy, request)
            operation.result.addListener({
                try { operation.result.get() } catch (_: Exception) { queue.failed(id) }
            }, java.util.concurrent.Executor { it.run() })
        }
    }
}

class PostWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun getForegroundInfo() = ForegroundInfo(CommandNotice.ID, CommandNotice.notification(applicationContext))
    override fun doWork(): Result = synchronized(Posting.flight) {
        val command = inputData.getBoolean("command", false)
        val queue = Posting.queue(command)
        queue.started(id.toString())
        var retry = false
        try {
            val store = Store(applicationContext)
            if (!store.configured || isStopped) return@synchronized Result.success()
            if (command) CommandNotice.show(applicationContext)
            val session = store.session()
            try {
                val health = ConnectionTest.check(session.settings)
                check(health.connected) { "Inbox authentication failed" }
                store.ifCurrent(session) { store.updatePushConfig(health.pushConfig) }
                PushRegistration.refresh(applicationContext)
                val exchange = Exchange(applicationContext, store) { !isStopped }
                exchange.send(session)
                exchange.finishPending(session)
                if (store.ifCurrent(session) {} && store.outcomes().length() > 0) exchange.send(session)
                if (command) CommandNotice.show(applicationContext, if (store.paused) "Bot clearing is paused" else "Bot request processed")
                Result.success()
            } catch (_: Exception) {
                if (!isStopped) store.ifCurrent(session) { store.postFailed() }
                retry = !isStopped && runAttemptCount < 2
                if (command) CommandNotice.show(applicationContext, "Couldn't process the request. Check your inbox connection.")
                if (retry) Result.retry() else Result.failure()
            }
        } finally {
            if (queue.finished(id.toString(), retry)) Posting.schedule(applicationContext, command, command)
        }
    }
    override fun onStopped() {
        Posting.queue(inputData.getBoolean("command", false)).stopped(id.toString())
        super.onStopped()
    }
}
