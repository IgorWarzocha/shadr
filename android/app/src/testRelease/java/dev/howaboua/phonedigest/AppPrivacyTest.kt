package dev.howaboua.phonedigest

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException

class AppPrivacyTest {
    @Test fun exclusionRemovesReceiptIdentityWithoutDroppingOtherAcknowledgements() {
        fun receipt(key: String, id: String) = JSONObject().put("key", key).put("requestId", id).put("status", "applied")
        val entries = JSONArray().put(receipt("0|private.app|7|null|10001", "private"))
            .put(receipt("0|other.app|8|null|10002", "other"))
            .put(receipt("legacy-opaque-key", "legacy"))
        val filtered = AppPrivacy.outcomes(entries, setOf("private.app"))
        assertEquals(2, filtered.length())
        assertEquals("other", filtered.getJSONObject(0).getString("requestId"))
        assertEquals("legacy", filtered.getJSONObject(1).getString("requestId"))
        assertFalse(filtered.toString().contains("private.app"))
        assertEquals(3, AppPrivacy.outcomes(entries, emptySet()).length())
        assertFalse(AppPrivacy.excludesKey("0|private.application|7|null|10001", setOf("private.app")))
    }

    @Test fun changedChoicesRejectCapturedBytesBeforeWriting() {
        var written = false
        assertThrows(IllegalStateException::class.java) { AppPrivacy.write(1, { 2 }) { written = true } }
        assertFalse(written)
        AppPrivacy.write(2, { 2 }) { written = true }
        assertTrue(written)
    }

    @Test fun exclusionSaveCannotConfirmWhileBodyWriteIsInProgress() {
        val executor = Executors.newFixedThreadPool(2)
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val saving = CountDownLatch(1)
        try {
            val write = executor.submit { AppPrivacy.write(1, { 1 }) { writing.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) } }
            assertTrue(writing.await(3, TimeUnit.SECONDS))
            val save = executor.submit { saving.countDown(); synchronized(AppPrivacy.uploadLock) { true } }
            assertTrue(saving.await(3, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { save.get(100, TimeUnit.MILLISECONDS) }
            release.countDown()
            write.get(3, TimeUnit.SECONDS)
            save.get(3, TimeUnit.SECONDS)
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
