package com.xjb.kaoyancheckin

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One process-wide store, lock, and Room connection; the connection lives with the process. */
internal object CheckinRepository {
    private const val DATABASE_NAME = "checkin.db"
    private const val MAIN_DOCUMENT_ID = 1
    private const val RECOVERY_DOCUMENT_ID = 2
    private const val CHUNK_SIZE = 250_000

    @Volatile private var connection: Connection? = null

    fun get(context: Context): DocumentStore = (connection ?: synchronized(this) {
        connection ?: Connection(context.applicationContext).also { connection = it }
    }).store

    private class Connection(context: Context) {
        private val database = Room.databaseBuilder(context, CheckinDatabase::class.java, DATABASE_NAME).build()
        private var closed = false
        val store = DocumentStore(
            readRaw = { access { readSafe(it, MAIN_DOCUMENT_ID) } },
            writeRaw = { json -> access { it.writeBlocking(CheckinDocument(MAIN_DOCUMENT_ID, json)) } },
            readRecovery = { access { readSafe(it, RECOVERY_DOCUMENT_ID) } },
            writeWithRecovery = { json, recovery ->
                access { dao ->
                    database.runInTransaction {
                        dao.writeBlocking(CheckinDocument(MAIN_DOCUMENT_ID, json))
                        if (recovery == null) dao.deleteByIdBlocking(RECOVERY_DOCUMENT_ID)
                        else dao.writeBlocking(CheckinDocument(RECOVERY_DOCUMENT_ID, recovery))
                    }
                }
            },
        )

        private fun readSafe(dao: CheckinDao, id: Int): String? {
            return try {
                dao.readByIdBlocking(id)
            } catch (_: Exception) {
                val total = dao.readLengthBlocking(id) ?: return null
                if (total == 0) return ""
                buildString(total) {
                    var start = 1
                    while (start <= total) {
                        append(dao.readChunkBlocking(id, start, CHUNK_SIZE).orEmpty())
                        start += CHUNK_SIZE
                    }
                }
            }
        }

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
