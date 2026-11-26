package com.xjb.kaoyancheckin

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun documentStoreSeparatesRecoveryFromMainDocument() = runBlocking {
        var mainDoc: String? = null
        var recoveryDoc: String? = null
        val store = DocumentStore(
            readRaw = { mainDoc },
            writeRaw = { mainDoc = it },
            readRecovery = { recoveryDoc },
            writeWithRecovery = { main, recovery -> mainDoc = main; recoveryDoc = recovery },
        )
        store.update { it.put("_recovery", """{"days":{}}""").put("bk", 42) }
        assertFalse(JSONObject(checkNotNull(store.read())).has("_recovery"))
        assertEquals("""{"days":{}}""", store.readRecovery())
    }

    @Test fun failedRecoveryWriteRollsBackTheMainDocument() = runBlocking {
        val store = CheckinRepository.get(app)
        store.update { it.put("bk", 1) }
        SQLiteDatabase.openDatabase(app.getDatabasePath("checkin.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("""
                CREATE TRIGGER reject_recovery BEFORE INSERT ON document
                WHEN NEW.id = 2 BEGIN SELECT RAISE(ABORT, 'test recovery write failure'); END
            """.trimIndent())
        }
        val before = store.read()
        val result = runCatching {
            store.update { it.put("bk", 2).put("_recovery", CheckinCore.backupJSON(it)) }
        }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("test recovery write failure"))
        assertEquals(before, store.read())
        assertEquals(null, store.readRecovery())
    }
}
