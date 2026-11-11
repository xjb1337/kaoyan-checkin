package com.xjb.kaoyancheckin

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class CheckinViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        // A replacement ViewModel must not load while the old one is finishing its last writes.
        private val mutex = Mutex()
    }

    private val database = Room.databaseBuilder(application, CheckinDatabase::class.java, "checkin.db").build()
    // Writes already requested on pause must finish even if the Activity's ViewModel is cleared.
    private val writeJob = SupervisorJob()
    private val writeScope = CoroutineScope(writeJob + Dispatchers.Main.immediate)
    private val drafts = DraftSaver(writeScope, save = { field, value ->
        commitChange { current -> patchDraft(current, field, value, CheckinCore.today(), System.currentTimeMillis()) }
    })
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()
    val plan = StudyPlan(JSONObject(application.assets.open("plan.json").bufferedReader().use { it.readText() }))
    var state by mutableStateOf(CheckinCore.emptyState())
        private set
    var ready by mutableStateOf(false)
        private set
    var loadError by mutableStateOf("")
        private set
    private var recoveryRaw: String? = null
    var preview by mutableStateOf<JSONObject?>(null)
        private set
    private var pendingRaw: String? = null
    private var previewRequest = 0
    var today by mutableStateOf(CheckinCore.today())
        private set
    var selected by mutableStateOf(today)
    var week by mutableStateOf(StudyPlan.monday(today))
    private var currentPage by mutableIntStateOf(0)
    var page: Int
        get() = currentPage
        set(value) {
            if (value != currentPage) {
                flushDrafts()
                currentPage = value
            }
        }
    var exportSnapshot: String? = null

    init {
        viewModelScope.launch {
            mutex.withLock {
                runCatching {
                    val raw = database.document().read()
                    recoveryRaw = raw
                    state = if (raw == null) CheckinCore.emptyState() else withContext(Dispatchers.Default) { CheckinCore.parseBackupDocument(raw) }
                    recoveryRaw = null
                }.onFailure {
                    loadError = "原始存档无法读取或格式异常。已停止覆盖，请先导出原始存档，再从备份恢复。"
                    notify(loadError)
                }
                ready = true
            }
        }
    }

    fun notify(message: String) { messages.trySend(message) }

    fun resume() {
        val current = CheckinCore.today()
        selected = CheckinCore.rollDate(selected, today, current)
        week = CheckinCore.rollDate(week, StudyPlan.monday(today), StudyPlan.monday(current))
        today = current
    }

    private fun change(success: String? = null, allowRecovery: Boolean = false, noChange: String? = null,
                       block: (JSONObject) -> JSONObject?) {
        writeScope.launch { commitChange(success, allowRecovery, noChange, block) }
    }

    private suspend fun commitChange(success: String? = null, allowRecovery: Boolean = false, noChange: String? = null,
                                     block: (JSONObject) -> JSONObject?): Boolean = mutex.withLock {
        try {
            check(ready) { "正在读取本机数据" }
            check(loadError.isEmpty() || allowRecovery) { loadError }
            val source = state
            val changed = withContext(Dispatchers.Default) { block(source.copyJson()) }
            if (changed == null) {
                noChange?.let(::notify)
            } else {
                val candidate = withContext(Dispatchers.Default) {
                    CheckinCore.normalizeState(changed.put("upd", System.currentTimeMillis()))
                }
                val json = withContext(Dispatchers.Default) { candidate.toString() }
                database.document().write(CheckinDocument(json = json))
                state = candidate
                if (allowRecovery) { recoveryRaw = null; loadError = ""; pendingRaw = null; preview = null }
                success?.let(::notify)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notify("未保存：${e.message}")
            false
        }
    }

    internal fun draftValue(field: DraftField, saved: String): String = drafts.value(field, saved)
    internal fun updateDraft(field: DraftField, value: String) = drafts.update(field, value)
    internal fun flushDraft(field: DraftField) = drafts.flush(field)
    fun flushDrafts() = drafts.flushAll()

    fun edit(block: (JSONObject) -> Unit) = change { it.apply(block) }
    fun editDay(date: String = selected, block: (JSONObject) -> Unit) = change {
        require(date <= CheckinCore.today()) { "未来日期只读" }
        val record = it.obj("days").optJSONObject(date) ?: StudyPlan.day()
        block(record)
        record.put("u", System.currentTimeMillis())
        it.obj("days").put(date, record)
        it
    }
    fun editWeek(date: String = week, block: (JSONObject) -> Unit) = change {
        require(date <= StudyPlan.monday(CheckinCore.today())) { "未来周目标只读" }
        val record = it.obj("weeks").optJSONObject(date) ?: StudyPlan.week()
        block(record)
        record.put("u", System.currentTimeMillis())
        it.obj("weeks").put(date, record)
        it
    }

    fun copyPreviousWeekGoals(week: String = this.week) {
        // Main.immediate queues these writes on the mutex before the copy operation.
        (0..2).forEach { flushDraft(DraftField.WeekGoal(week, it)) }
        change(success = "已复制上周目标", noChange = "上周没有目标") {
            require(week <= StudyPlan.monday(CheckinCore.today())) { "未来周目标只读" }
            if (plan.copyPreviousWeekGoals(it, week)) it else null
        }
    }

    fun deleteCustomTask(date: String = selected, id: String) = editDay(date) { plan.deleteCustomTask(it, id) }

    fun setHours(date: String, subject: String, hours: Double) = editDay(date) { record ->
        val updated = CheckinCore.setHours(record, subject, hours)
        record.put("h", updated.get("h")).put("sh", updated.get("sh"))
    }

    fun adjustHours(date: String, subject: String, delta: Double) {
        flushDraft(DraftField.Hours(date, subject))
        editDay(date) { day ->
            val value = (day.obj("sh").optDouble(subject, 0.0) + delta).coerceAtLeast(0.0)
            val updated = CheckinCore.setHours(day, subject, value)
            day.put("h", updated.get("h")).put("sh", updated.get("sh"))
        }
    }

    fun stage(raw: String) {
        val request = ++previewRequest
        pendingRaw = null
        preview = null
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.Default) { CheckinCore.parseBackup(raw) } }
                .onSuccess { if (request == previewRequest) { pendingRaw = raw; preview = it; notify("备份检查通过，请选择合并或完整恢复") } }
                .onFailure { if (request == previewRequest) notify("无法读取：${it.message}") }
        }
    }

    fun import(mode: String) {
        val raw = pendingRaw ?: return
        change("恢复成功，原记录已留本机副本", true) { CheckinCore.importBackup(it, raw, mode, recoveryRaw = recoveryRaw) }
    }

    fun restorePrevious() {
        val raw = state.optString("_recovery", "").ifEmpty {
            runCatching { JSONObject(recoveryRaw ?: "{}").optString("_recovery", "") }.getOrDefault("")
        }
        if (raw.isEmpty()) { notify("还没有导入前副本"); return }
        change("已恢复上次导入前的记录", true) { CheckinCore.importBackup(it, raw, "replace", recoveryRaw = recoveryRaw) }
    }

    fun exportText(): String = recoveryRaw ?: CheckinCore.backupJSON(state)
    fun confirmBackup() = edit { it.put("bk", System.currentTimeMillis()) }
    fun makeup(date: String) = edit { plan.useMakeup(date, it, CheckinCore.today()) }

    fun retireTask(name: String) = edit { current ->
        current.put("recur", JSONArray(current.arr("recur").values().filter { it != name }))
    }

    fun wipe() = change("已清空打卡数据，保留设置与固定任务") {
        CheckinCore.emptyState().put("settings", it.get("settings")).put("recur", it.get("recur")).put("tip", 1)
    }

    override fun onCleared() {
        flushDrafts()
        writeJob.invokeOnCompletion { database.close() }
        writeJob.complete()
        super.onCleared()
    }
}
