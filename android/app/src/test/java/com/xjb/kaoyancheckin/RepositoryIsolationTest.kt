package com.xjb.kaoyancheckin

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryIsolationTest {
    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        CheckinRepository.reset()
        app.deleteDatabase("checkin.db")
    }

    @After fun tearDown() {
        CheckinRepository.reset()
        app.deleteDatabase("checkin.db")
    }

    @Test fun resetRejectsAnOldStoreEvenIfItsDatabaseWasNeverOpened() = runBlocking {
        val old = CheckinRepository.get(app)
        CheckinRepository.reset()
        app.deleteDatabase("checkin.db")
        val current = CheckinRepository.get(app)
        current.update { it.put("bk", 2) }

        val result = runCatching { old.update { it.put("bk", 1) } }
        val saved = JSONObject(checkNotNull(current.read()))

        assertTrue("retired write=$result; new database bk=${saved.getLong("bk")}", result.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { old.read() }.exceptionOrNull() is IllegalStateException)
        assertEquals(2L, saved.getLong("bk"))
    }

    @Test fun resetRejectsAnUpdateAlreadyBetweenReadAndWrite() = runBlocking {
        val old = CheckinRepository.get(app)
        old.update { it.put("bk", 1) }
        val readCompleted = CountDownLatch(1)
        val continueWrite = CountDownLatch(1)
        val pending = async(Dispatchers.IO) {
            runCatching {
                old.update {
                    readCompleted.countDown()
                    check(continueWrite.await(15, TimeUnit.SECONDS))
                    it.put("bk", 3)
                }
            }
        }
        try {
            check(readCompleted.await(15, TimeUnit.SECONDS))
            CheckinRepository.reset()
            app.deleteDatabase("checkin.db")
            val current = CheckinRepository.get(app)
            current.update { it.put("bk", 2) }
            continueWrite.countDown()
            val result = pending.await()
            val saved = JSONObject(checkNotNull(current.read()))

            assertTrue("in-flight retired write=$result; new database bk=${saved.getLong("bk")}", result.exceptionOrNull() is IllegalStateException)
            assertEquals(2L, saved.getLong("bk"))
        } finally {
            continueWrite.countDown()
            pending.join()
        }
    }
}
