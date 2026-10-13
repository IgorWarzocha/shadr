package dev.howaboua.phonedigest

import org.junit.Assert.*
import org.junit.Test
import androidx.work.ExistingWorkPolicy

class FlushQueueTest {
    @Test fun immediateRequestsCoalesceIntoOneFollowUpAndRetriesDoNotLoseIt() {
        val queue = FlushQueue()
        val scheduled = mutableListOf<String>()
        assertTrue(queue.request("first") { scheduled.add("first") })
        repeat(1000) { assertFalse(queue.request("before-$it") { fail("Unbounded queue") }) }
        queue.started("first")
        assertTrue(queue.request("follow-up") { scheduled.add("follow-up") })
        repeat(1000) { assertFalse(queue.request("during-$it") { fail("Unbounded queue") }) }
        queue.started("first") // A retry is not the queued follow-up.
        queue.failed("first")
        assertFalse(queue.request("extra") { fail("Retry lost follow-up ownership") })
        queue.started("follow-up")
        assertTrue(queue.request("next") { scheduled.add("next") })
        assertEquals(listOf("first", "follow-up", "next"), scheduled)
    }
    @Test fun failedEnqueueCanBeRetriedWithoutClearingSomeOtherQueuedRequest() {
        val queue = FlushQueue()
        assertThrows(IllegalStateException::class.java) { queue.request("broken") { throw IllegalStateException() } }
        assertTrue(queue.request("retry") {})
        queue.failed("broken")
        assertFalse(queue.request("duplicate") {})
        queue.failed("retry")
        assertTrue(queue.request("recovered") {})
    }
    @Test fun freshTriggerReplacesRetryBackoffRatherThanAppendingBehindIt() {
        val queue = FlushQueue()
        queue.request("failed") { assertEquals(ExistingWorkPolicy.REPLACE, it) }
        queue.started("failed")
        assertFalse(queue.finished("failed", retry = true))
        queue.request("fresh") { assertEquals(ExistingWorkPolicy.REPLACE, it) }
        queue.stopped("failed")
        assertFalse(queue.request("duplicate") {})
    }
    @Test fun freshFollowUpQueuedDuringFailedSendIsPromotedImmediately() {
        val queue = FlushQueue()
        queue.request("current") {}
        queue.started("current")
        queue.request("follow-up") { assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, it) }
        assertTrue(queue.finished("current", retry = true))
        queue.request("promoted") { assertEquals(ExistingWorkPolicy.REPLACE, it) }
        queue.stopped("current")
        assertFalse(queue.finished("current", retry = true))
        assertFalse(queue.request("duplicate") {})
    }
}
