package com.xjb.kaoyancheckin

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One process-wide store, lock, and Room connection; the connection lives with the process. */
internal object CheckinRepository {
    private const val DATABASE_NAME = "checkin.db"

    @Volatile private var store: DocumentStore? = null
    @Volatile private var database: CheckinDatabase? = null

    fun get(context: Context): DocumentStore = store ?: synchronized(this) {
        store ?: create(context.applicationContext).also { store = it }
    }

    private fun create(context: Context): DocumentStore {
        val db = Room.databaseBuilder(context, CheckinDatabase::class.java, DATABASE_NAME).build()
        database = db
        return DocumentStore(
            readRaw = { withContext(Dispatchers.IO) { db.document().read() } },
            writeRaw = { json -> withContext(Dispatchers.IO) { db.document().write(CheckinDocument(json = json)) } },
        )
    }

    /** Closes the process-wide connection so tests can delete or reseed the database file. */
    @Synchronized
    internal fun reset() {
        val db = database
        database = null
        store = null
        db?.close()
    }
}
