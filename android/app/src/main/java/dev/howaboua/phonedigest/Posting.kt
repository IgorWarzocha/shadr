package dev.howaboua.phonedigest

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class DigestApp : Application() {
    private var connectivity: ConnectivityManager? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { Posting.schedule(this@DigestApp) }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) Posting.schedule(this@DigestApp)
        }
    }
    override fun onCreate() {
        super.onCreate()
        Store(this)
        WorkManager.getInstance(this).cancelUniqueWork("changes")
        val heartbeat = PeriodicWorkRequestBuilder<PostWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Posting.connected).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("heartbeat", ExistingPeriodicWorkPolicy.KEEP, heartbeat)
        connectivity = getSystemService(ConnectivityManager::class.java)
        // Observe VPN as well as the default route. Android does not expose the VPN owner's package.
        connectivity!!.registerNetworkCallback(NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
        Posting.schedule(this)
    }
}

object Posting {
    val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    val flight = Any()
    private val queue = FlushQueue()
    fun started(id: String) = queue.started(id)
    fun finished(id: String, retry: Boolean) = queue.finished(id, retry)
    fun stopped(id: String) = queue.stopped(id)
    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<PostWorker>().setConstraints(connected)
            .setInputData(workDataOf("eventFlush" to true)).build()
        // An event during an in-flight snapshot must cause another capture, not be dropped by KEEP.
        val id = request.id.toString()
        queue.request(id) { policy ->
            val operation = WorkManager.getInstance(context).enqueueUniqueWork("flush", policy, request)
            operation.result.addListener({
                try { operation.result.get() } catch (_: Exception) { queue.failed(id) }
            }, java.util.concurrent.Executor { it.run() })
        }
    }
}

class PostWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = synchronized(Posting.flight) {
        val eventFlush = inputData.getBoolean("eventFlush", false)
        if (eventFlush) Posting.started(id.toString())
        var retry = false
        try {
            val store = Store(applicationContext)
            if (!store.configured || isStopped) return@synchronized Result.success()
            val session = store.session()
            try {
                Exchange(applicationContext, store) { !isStopped }.send(session)
            } catch (_: Exception) {
                if (!isStopped) {
                    // Never record exception text: it can contain credentials or previews.
                    store.ifCurrent(session) { store.postFailed() }
                    retry = true
                }
            }
            if (retry) Result.retry() else Result.success()
        } catch (_: Exception) {
            retry = !isStopped
            if (retry) Result.retry() else Result.success()
        } finally {
            if (eventFlush && Posting.finished(id.toString(), retry)) Posting.schedule(applicationContext)
        }
    }
    override fun onStopped() {
        if (inputData.getBoolean("eventFlush", false)) Posting.stopped(id.toString())
        super.onStopped()
    }
}
