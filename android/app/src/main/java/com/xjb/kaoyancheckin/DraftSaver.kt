package com.xjb.kaoyancheckin

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

internal sealed interface DraftField {
    data class DayReview(val date: String, val field: String) : DraftField
    data class WeekGoal(val week: String, val index: Int) : DraftField
    data class WeekReview(val week: String) : DraftField
    data class Hours(val date: String, val subject: String) : DraftField
}

// Called with a copy of the latest state while the shared repository's write mutex is held.
// Return null for unchanged values so merely viewing a blank date/week never creates a record.
internal fun patchDraft(state: JSONObject, field: DraftField, value: String, today: String, now: Long): JSONObject? {
    when (field) {
        is DraftField.DayReview -> {
            require(field.date <= today) { "未来日期只读" }
            require(field.field in listOf("g", "s", "n")) { "复盘字段无效" }
            val days = state.obj("days")
            val existing = days.optJSONObject(field.date)
            if ((existing?.obj("rw")?.optString(field.field) ?: "") == value) return null
            val day = existing ?: StudyPlan.day()
            day.put("rw", day.obj("rw").put(field.field, value)).put("u", now)
            days.put(field.date, day)
        }
        is DraftField.WeekGoal -> {
            require(field.week <= StudyPlan.monday(today)) { "未来周目标只读" }
            require(field.index in 0..2) { "周目标字段无效" }
            val weeks = state.obj("weeks")
            val existing = weeks.optJSONObject(field.week)
            if ((existing?.arr("must")?.optString(field.index) ?: "") == value) return null
            val week = existing ?: StudyPlan.week()
            week.put("must", week.arr("must").put(field.index, value)).put("u", now)
            weeks.put(field.week, week)
        }
        is DraftField.WeekReview -> {
            require(field.week <= StudyPlan.monday(today)) { "未来周目标只读" }
            val weeks = state.obj("weeks")
            val existing = weeks.optJSONObject(field.week)
            if ((existing?.optString("review") ?: "") == value) return null
            val week = existing ?: StudyPlan.week()
            week.put("review", value).put("u", now)
            weeks.put(field.week, week)
        }
        is DraftField.Hours -> {
            require(field.date <= today) { "未来日期只读" }
            val days = state.obj("days")
            val existing = days.optJSONObject(field.date)
            val day = existing ?: StudyPlan.day()
            // setHours validates the number and the total against the latest day's other subjects.
            val updated = CheckinCore.setHours(day, field.subject, value.toDoubleOrNull() ?: Double.NaN)
            if (updated.obj("sh").getDouble(field.subject) == day.obj("sh").optDouble(field.subject, 0.0)) return null
            updated.put("u", now)
            days.put(field.date, updated)
        }
    }
    return state
}

// All methods and completion callbacks run on the owning scope's main thread.
internal class DraftSaver(
    private val scope: CoroutineScope,
    private val save: suspend (DraftField, String) -> Boolean,
    // Allow a short pause in typing before writing; navigation and ON_PAUSE bypass this delay.
    private val debounceMillis: Long = 600,
) {
    private data class Draft(val value: String, val revision: Long)
    private data class Saving(val revision: Long, val job: Job)

    private val drafts = mutableStateMapOf<DraftField, Draft>()
    private val timers = mutableMapOf<DraftField, Job>()
    private val saving = mutableMapOf<DraftField, Saving>()
    private var revision = 0L

    fun value(field: DraftField, saved: String): String = drafts[field]?.value ?: saved

    fun update(field: DraftField, value: String) {
        if (drafts[field]?.value == value) return
        drafts[field] = Draft(value, ++revision)
        timers.remove(field)?.cancel()
        timers[field] = scope.launch {
            delay(debounceMillis)
            flush(field)
        }
    }

    fun flush(field: DraftField): Job? {
        timers.remove(field)?.cancel()
        val draft = drafts[field] ?: return null
        saving[field]?.let { if (it.revision == draft.revision && it.job.isActive) return it.job }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (save(field, draft.value) && drafts[field]?.revision == draft.revision) drafts.remove(field)
        }
        saving[field] = Saving(draft.revision, job)
        job.invokeOnCompletion { if (saving[field]?.job === job) saving.remove(field) }
        job.start()
        return job
    }

    fun flushAll() {
        drafts.keys.toList().forEach { flush(it) }
    }
}
