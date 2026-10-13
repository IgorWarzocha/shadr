package dev.howaboua.phonedigest

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class DigestApp : Application() {
    private var connectivity: ConnectivityManager? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { Posting.schedule(this@DigestApp, true) }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) Posting.schedule(this@DigestApp, true)
        }
    }
    override fun onCreate() {
        super.onCreate()
        Store(this)
        val heartbeat = PeriodicWorkRequestBuilder<PostWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Posting.connected).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("heartbeat", ExistingPeriodicWorkPolicy.KEEP, heartbeat)
        connectivity = getSystemService(ConnectivityManager::class.java)
        // Observe VPN as well as the default route. Android does not expose the VPN owner's package.
        connectivity!!.registerNetworkCallback(NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
        Posting.schedule(this, true)
    }
}

object Posting {
    val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    val flight = Any()
    fun schedule(context: Context, immediate: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<PostWorker>().setConstraints(connected)
            .setInitialDelay(if (immediate) 0 else 60, TimeUnit.SECONDS).build()
        // KEEP fixes the deadline at the first change, rather than postponing forever under traffic.
        WorkManager.getInstance(context).enqueueUniqueWork(if (immediate) "flush" else "changes", ExistingWorkPolicy.KEEP, request)
    }
}

class PostWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = synchronized(Posting.flight) {
        val store = Store(applicationContext)
        if (!store.configured) {
            return@synchronized Result.success()
        }
        val session = store.session()
        try {
            Exchange(applicationContext, store).send(session)
            Result.success()
        } catch (e: Exception) {
            // Never record the exception message: it can contain endpoint credentials or previews.
            store.ifCurrent(session) { store.postFailed() }
            Result.retry()
        }
    }
}
