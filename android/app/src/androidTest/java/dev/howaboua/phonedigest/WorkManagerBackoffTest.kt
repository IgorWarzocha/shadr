package dev.howaboua.phonedigest

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class WorkManagerBackoffTest {
    companion object {
        var queue = FlushQueue()
        val retryExecutions = AtomicInteger()
    }
    private lateinit var manager: WorkManager
    @Before fun setup() {
        queue = FlushQueue()
        retryExecutions.set(0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        manager = WorkManager.getInstance(context)
    }
    @Test fun freshSendRunsWhilePreviousWorkIsWaitingForRetryBackoff() {
        val first = OneTimeWorkRequestBuilder<RetryingFlushWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES).build()
        enqueue(first)
        val waiting = await(first) { it.runAttemptCount == 1 && it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, waiting.runAttemptCount)
        val fresh = OneTimeWorkRequestBuilder<SuccessfulFlushWorker>().build()
        enqueue(fresh)
        await(fresh) { it.state == WorkInfo.State.SUCCEEDED }
        // REPLACE cancels and deletes the old WorkSpec; it does not retain a CANCELLED row.
        assertNull(manager.getWorkInfoById(first.id).get(5, TimeUnit.SECONDS))
        assertEquals(1, retryExecutions.get())
        // No TestDriver clock/delay advancement: the fresh job must not depend on the retry timer.
    }
    private fun enqueue(request: OneTimeWorkRequest) {
        var operation: Operation? = null
        assertTrue(queue.request(request.id.toString()) { policy ->
            operation = manager.enqueueUniqueWork("backoff-regression", policy, request)
        })
        operation!!.result.get(5, TimeUnit.SECONDS)
    }
    private fun await(request: OneTimeWorkRequest, ready: (WorkInfo) -> Boolean): WorkInfo {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val info = manager.getWorkInfoById(request.id).get(5, TimeUnit.SECONDS)
            if (info != null && ready(info)) return info
            Thread.sleep(10)
        }
        throw AssertionError("WorkManager did not reach the required state")
    }
}

class RetryingFlushWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        WorkManagerBackoffTest.retryExecutions.incrementAndGet()
        WorkManagerBackoffTest.queue.started(id.toString())
        WorkManagerBackoffTest.queue.finished(id.toString(), retry = true)
        return Result.retry()
    }
}

class SuccessfulFlushWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        WorkManagerBackoffTest.queue.started(id.toString())
        WorkManagerBackoffTest.queue.finished(id.toString(), retry = false)
        return Result.success()
    }
}
