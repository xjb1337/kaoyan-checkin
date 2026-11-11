package com.xjb.kaoyancheckin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DraftSaverTest {
    private val field = DraftField.DayReview("2026-11-10", "g")

    @Test fun typingDebouncesAndFlushBypassesTheDelay() = runTest {
        val writes = mutableListOf<String>()
        val saver = DraftSaver(this, save = { _, value -> writes.add(value); true })
        saver.update(field, "一")
        advanceTimeBy(400)
        saver.update(field, "一二")
        advanceTimeBy(599)
        runCurrent()
        assertTrue(writes.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("一二"), writes)

        saver.update(field, "切页前")
        saver.update(DraftField.WeekReview("2026-11-09"), "另一条草稿")
        saver.flushAll()
        runCurrent()
        assertEquals(setOf("切页前", "另一条草稿"), writes.drop(1).toSet())
        advanceTimeBy(600)
        runCurrent()
        assertEquals(3, writes.size)
    }

    @Test fun failureKeepsTheDraftAndFlushCanRetry() = runTest {
        var attempts = 0
        val saver = DraftSaver(this, save = { _, _ -> ++attempts > 1 })
        saver.update(field, "还没写入")
        saver.flush(field)!!.join()
        assertEquals("还没写入", saver.value(field, "旧值"))
        saver.flush(field)!!.join()
        assertEquals(2, attempts)
        assertEquals("已保存", saver.value(field, "已保存"))
    }

    @Test fun aCompletedOlderWriteDoesNotDiscardNewerTyping() = runTest {
        val releaseFirst = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()
        val saver = DraftSaver(this, save = { _, value ->
            if (value == "旧草稿") releaseFirst.await()
            writes.add(value)
            true
        })
        saver.update(field, "旧草稿")
        val oldWrite = saver.flush(field)!!
        runCurrent()
        saver.update(field, "新草稿")
        releaseFirst.complete(Unit)
        oldWrite.join()
        assertEquals("新草稿", saver.value(field, "旧草稿"))
        saver.flush(field)!!.join()
        assertEquals(listOf("旧草稿", "新草稿"), writes)
        assertEquals("落盘的新值", saver.value(field, "落盘的新值"))
    }

    @Test fun repeatedFlushReusesTheSamePendingWrite() = runTest {
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        val saver = DraftSaver(this, save = { _, _ -> attempts++; release.await(); true })
        saver.update(field, "草稿")
        val first = saver.flush(field)!!
        val second = saver.flush(field)!!
        assertSame(first, second)
        runCurrent()
        release.complete(Unit)
        first.join()
        assertEquals(1, attempts)
    }
}
