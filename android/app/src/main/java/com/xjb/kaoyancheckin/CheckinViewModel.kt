package com.xjb.kaoyancheckin

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class RolledToday(val today: String, val selected: String, val week: String)

internal fun rollToday(currentToday: String, selected: String, week: String, newToday: String): RolledToday =
    RolledToday(
        today = newToday,
        selected = CheckinCore.rollDate(selected, currentToday, newToday),
        week = CheckinCore.rollDate(week, StudyPlan.monday(currentToday), StudyPlan.monday(newToday)),
    )

/** Keep an explicitly edited historical date, but move a stale "today" write to the rolled day. */
internal fun resolveWriteDate(
    date: String?,
    previousToday: String,
    currentToday: String,
    selectedAfterRoll: String,
    selectedWasToday: Boolean,
): String = when {
    date == null -> selectedAfterRoll
    date == previousToday && selectedWasToday -> currentToday
    else -> date
}

class CheckinViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CheckinRepository.get(application)
    // Writes already requested on pause must finish even if the Activity's ViewModel is cleared.
    private val writeJob = SupervisorJob()
    private val writeScope = CoroutineScope(writeJob + Dispatchers.Main.immediate)
    internal val hasPendingWrites: Boolean get() = writeJob.children.any { !it.isCompleted }
    private val drafts = DraftSaver(writeScope, save = { field, value ->
        val today = currentDay()
        commitChange { current -> patchDraft(current, field, value, today, System.currentTimeMillis()) }
    })
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()
    val plan = StudyPlan(JSONObject(application.assets.open("plan.json").bufferedReader().use { it.readText() }))
    var state by mutableStateOf(CheckinCore.emptyState())
        private set
    var ready by mutableStateOf(false)
        private set
    internal var pendingUndo by mutableStateOf<UndoStep?>(null)
        private set
    private var dismissedUndo: String? = null
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
        // Subscribe before the initial read, so a simultaneous widget write cannot be missed.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.changes.collect { reload() }
        }
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        var rawOnFailure: String? = null
        try {
            state = repository.inspect { raw ->
                rawOnFailure = raw
                if (raw == null) CheckinCore.emptyState() else CheckinCore.parseBackupDocument(raw)
            }
            pendingUndo = liveUndo(state, System.currentTimeMillis())?.takeUnless { it.token == dismissedUndo }
            recoveryRaw = null
            loadError = ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recoveryRaw = rawOnFailure
            val error = "原始存档无法读取或格式异常。已停止覆盖，请先导出原始存档，再从备份恢复。"
            if (loadError != error) notify(error)
            loadError = error
        }
        ready = true
    }

    fun notify(message: String) { messages.trySend(message) }

    fun resume() = applyToday(CheckinCore.today())

    /** Single date source for every write; crossing midnight rolls the visible day first. */
    fun currentDay(): String {
        val current = CheckinCore.today()
        if (current != today) applyToday(current)
        return current
    }

    private fun applyToday(current: String) {
        val rolled = rollToday(today, selected, week, current)
        today = rolled.today
        selected = rolled.selected
        week = rolled.week
    }
    fun refresh() { viewModelScope.launch { reload() } }

    private fun change(success: String? = null, allowRecovery: Boolean = false, noChange: String? = null,
                       block: (JSONObject) -> JSONObject?) {
        writeScope.launch { commitChange(success, allowRecovery, noChange, block) }
    }

    private suspend fun commitChange(success: String? = null, allowRecovery: Boolean = false, noChange: String? = null,
                                     block: (JSONObject) -> JSONObject?): Boolean {
        return try {
            check(ready) { "正在读取本机数据" }
            check(loadError.isEmpty() || allowRecovery) { loadError }
            val changed = repository.update(allowRecovery, block)
            if (changed) {
                if (allowRecovery) { recoveryRaw = null; loadError = ""; pendingRaw = null; preview = null }
                reload()
                runCatching { CheckinWidget.refreshAll(getApplication()) }
                success?.let(::notify)
            } else noChange?.let(::notify)
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

    fun edit(block: (JSONObject) -> Unit) {
        currentDay()
        change { it.apply(block) }
    }

    fun editDay(date: String? = null, block: (JSONObject) -> Unit) {
        val previousToday = today
        val selectedWasToday = selected == previousToday
        val current = currentDay()
        val target = resolveWriteDate(date, previousToday, current, selected, selectedWasToday)
        val now = System.currentTimeMillis()
        change { editDayRecord(it, target, current, now, block) }
    }

    fun toggleTask(date: String, id: String) {
        val previousToday = today
        val selectedWasToday = selected == previousToday
        val current = currentDay()
        val target = resolveWriteDate(date, previousToday, current, selected, selectedWasToday)
        val now = System.currentTimeMillis()
        change { toggleTaskWithUndo(it, plan, target, current, id, now) }
    }

    internal fun dismissUndo(token: String) {
        dismissedUndo = token
        if (pendingUndo?.token == token) pendingUndo = null
    }
    internal fun undoTask(step: UndoStep) {
        val current = currentDay()
        val now = System.currentTimeMillis()
        change(noChange = "撤销已过期或任务已变化") {
            undoTask(it, plan, step.token, current, now)
        }
    }

    fun editWeek(date: String? = null, block: (JSONObject) -> Unit) {
        val previousWeek = week
        val previousToday = today
        val wasCurrentWeek = previousWeek == StudyPlan.monday(previousToday)
        val current = currentDay()
        val target = if (date != null && date == previousWeek && wasCurrentWeek) week else date ?: week
        val now = System.currentTimeMillis()
        change {
            require(target <= StudyPlan.monday(current)) { "未来周目标只读" }
            val record = it.obj("weeks").optJSONObject(target) ?: StudyPlan.week()
            block(record)
            record.put("u", now)
            it.obj("weeks").put(target, record)
            it
        }
    }

    fun copyPreviousWeekGoals(week: String? = null) {
        val previousWeek = this.week
        val previousToday = today
        val wasCurrentWeek = previousWeek == StudyPlan.monday(previousToday)
        val current = currentDay()
        val target = if (week != null && week == previousWeek && wasCurrentWeek) this.week else week ?: this.week
        // Main.immediate queues these writes on the mutex before the copy operation.
        (0..2).forEach { flushDraft(DraftField.WeekGoal(target, it)) }
        change(success = "已复制上周目标", noChange = "上周没有目标") {
            require(target <= StudyPlan.monday(current)) { "未来周目标只读" }
            if (plan.copyPreviousWeekGoals(it, target)) it else null
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

    fun makeup(date: String) {
        val current = currentDay()
        change { plan.useMakeup(date, it, current); it }
    }

    fun toggleChapter(subject: JSONObject, index: Int) {
        val current = currentDay()
        change { state -> toggleChapter(state, subject, index, current) }
    }

    fun retireTask(name: String) = edit { current ->
        current.put("recur", JSONArray(current.arr("recur").values().filter { it != name }))
    }

    fun wipe() = change("已清空打卡数据，保留设置与固定任务") {
        CheckinCore.emptyState().put("settings", it.get("settings")).put("recur", it.get("recur")).put("tip", 1)
    }

    override fun onCleared() {
        flushDrafts()
        writeJob.complete()
        super.onCleared()
    }
}
