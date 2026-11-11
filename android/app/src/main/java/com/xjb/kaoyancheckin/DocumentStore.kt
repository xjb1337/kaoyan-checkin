package com.xjb.kaoyancheckin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Serializes read-modify-write operations, including writers outside the Activity. */
internal class DocumentStore(
    private val readRaw: suspend () -> String?,
    private val writeRaw: suspend (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes = changed.asSharedFlow()

    suspend fun read(): String? = mutex.withLock { readRaw() }

    suspend fun <T> inspect(block: (String?) -> T): T = mutex.withLock {
        val raw = readRaw()
        withContext(Dispatchers.Default) { block(raw) }
    }

    suspend fun update(allowRecovery: Boolean = false, change: (JSONObject) -> JSONObject?): Boolean {
        val written = mutex.withLock {
            val raw = readRaw()
            val json = withContext(Dispatchers.Default) {
                val current = if (raw == null) CheckinCore.emptyState() else try {
                    CheckinCore.parseBackupDocument(raw)
                } catch (e: Exception) {
                    if (!allowRecovery) throw e
                    CheckinCore.emptyState()
                }
                val result = change(current.copyJson()) ?: return@withContext null
                CheckinCore.normalizeState(result.put("upd", now())).toString()
            } ?: return@withLock false
            writeRaw(json)
            true
        }
        // Collectors may immediately call inspect(), which needs the same non-reentrant mutex.
        if (written) changed.tryEmit(Unit)
        return written
    }
}

/** Same date guard, StudyPlan.toggle and record timestamp for app and widget. */
internal fun editDayRecord(state: JSONObject, date: String, today: String, now: Long, block: (JSONObject) -> Unit): JSONObject {
    require(CheckinCore.validDate(date) && date <= today) { "未来日期只读" }
    val record = state.obj("days").optJSONObject(date) ?: StudyPlan.day()
    block(record)
    record.put("u", now)
    state.obj("days").put(date, record)
    return state
}

internal fun toggleDayTask(state: JSONObject, plan: StudyPlan, date: String, today: String, id: String, now: Long): JSONObject =
    editDayRecord(state, date, today, now) { plan.toggle(it, id) }
