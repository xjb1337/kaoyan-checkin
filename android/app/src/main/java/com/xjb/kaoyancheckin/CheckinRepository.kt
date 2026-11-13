package com.xjb.kaoyancheckin

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One process-wide store, lock, and Room connection; the connection lives with the process. */
internal object CheckinRepository {
    private const val DATABASE_NAME = "checkin.db"

    @Volatile private var connection: Connection? = null

    fun get(context: Context): DocumentStore = (connection ?: synchronized(this) {
        connection ?: Connection(context.applicationContext).also { connection = it }
    }).store

    private class Connection(context: Context) {
        private val database = Room.databaseBuilder(context, CheckinDatabase::class.java, DATABASE_NAME).build()
        private var closed = false
        val store = DocumentStore(
            readRaw = { access { it.readBlocking() } },
            writeRaw = { json -> access { it.writeBlocking(CheckinDocument(json = json)) } },
        )

        private suspend fun <T> access(block: (CheckinDao) -> T): T = withContext(Dispatchers.IO) {
            synchronized(this@Connection) {
                check(!closed) { "数据库连接已重置" }
                block(database.document())
            }
        }

        @Synchronized fun close() {
            closed = true
            database.close()
        }
    }

    /** Closes the process-wide connection so tests can delete or reseed the database file. */
    @Synchronized
    internal fun reset() {
        connection?.close()
        connection = null
    }
}
