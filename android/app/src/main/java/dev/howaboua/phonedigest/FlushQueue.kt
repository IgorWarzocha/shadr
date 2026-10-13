package dev.howaboua.phonedigest

import androidx.work.ExistingWorkPolicy

/** Coalesce events into one queued snapshot without delaying it or dropping in-flight changes. */
class FlushQueue {
    private var queued: String? = null
    private var running: String? = null
    private var retryWaiting = false
    @Synchronized fun request(id: String, enqueue: (ExistingWorkPolicy) -> Unit): Boolean {
        if (queued != null && !retryWaiting) return false
        val policy = if (running != null) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        queued = id
        retryWaiting = false
        try { enqueue(policy) } catch (error: Exception) { queued = null; throw error }
        return true
    }
    @Synchronized fun started(id: String) {
        if (queued == id) queued = null
        running = id
        retryWaiting = false
    }
    @Synchronized fun finished(id: String, retry: Boolean): Boolean {
        if (running != id) return false
        running = null
        retryWaiting = retry
        return retry && queued != null
    }
    @Synchronized fun failed(id: String) { if (queued == id) queued = null }
    @Synchronized fun stopped(id: String) {
        if (queued == id) queued = null
        if (running == id) { running = null; retryWaiting = false }
    }
}
