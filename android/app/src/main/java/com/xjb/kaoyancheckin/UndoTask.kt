package com.xjb.kaoyancheckin

import org.json.JSONObject
import java.util.UUID

internal const val UNDO_WINDOW_MS = 45_000L

internal data class UndoStep(
    val token: String, val date: String, val taskId: String, val label: String,
    val wasDone: Boolean, val at: Long,
) {
    val message: String get() = "已${if (wasDone) "取消勾选" else "勾选"}「$label」"
    fun toJson(): JSONObject = JSONObject().put("id", token).put("date", date).put("task", taskId)
        .put("label", label).put("was", wasDone).put("at", at)

    companion object {
        fun fromJson(json: JSONObject?): UndoStep? {
            if (json == null || json.opt("id") !is String || json.optString("id").isEmpty() ||
                !CheckinCore.validDate(json.opt("date")) || json.opt("task") !is String ||
                json.optString("task").isEmpty() || json.opt("label") !is String ||
                json.opt("was") !is Boolean || json.opt("at") !is Number) return null
            val time = json.optDouble("at")
            if (!time.isFinite() || time < 0 || time > 9_007_199_254_740_991.0 || time % 1.0 != 0.0) return null
            return UndoStep(json.getString("id"), json.getString("date"), json.getString("task"),
                json.getString("label"), json.getBoolean("was"), json.getLong("at"))
        }
    }
}

/** A future timestamp is not considered live, e.g. after the device clock is corrected. */
internal fun liveUndo(state: JSONObject, now: Long): UndoStep? = UndoStep.fromJson(state.optJSONObject("_undo"))
    ?.takeIf { now >= it.at && now - it.at < UNDO_WINDOW_MS }

/** Both Activity and widget call this only inside DocumentStore.update's mutex. */
internal fun toggleTaskWithUndo(
    state: JSONObject, plan: StudyPlan, date: String, today: String, id: String, now: Long,
    token: String = UUID.randomUUID().toString(),
): JSONObject {
    val task = plan.tasks(date, state, today).firstOrNull { it.id == id }
    val custom = id.startsWith("r:") || id.startsWith("c:")
    val fullLabel = task?.label ?: if (custom) id.substring(2) else id
    val label = if (custom) fullLabel else fullLabel.substringBefore('：').substringBefore(':').trim().ifEmpty { fullLabel }
    val wasDone = plan.isDone(state.obj("days").optJSONObject(date), id)
    toggleDayTask(state, plan, date, today, id, now)
    return state.put("_undo", UndoStep(token, date, id, label, wasDone, now).toJson())
}

/** Returns null for a stale, replaced or otherwise changed step; never creates another undo step. */
internal fun undoTask(state: JSONObject, plan: StudyPlan, token: String, today: String, now: Long): JSONObject? {
    val step = liveUndo(state, now)?.takeIf { it.token == token && it.date <= today } ?: return null
    val day = state.obj("days").optJSONObject(step.date) ?: return null
    if (plan.isDone(day, step.taskId) == step.wasDone) return null
    if ((step.taskId.startsWith("r:") || step.taskId.startsWith("c:")) &&
        day.arr("c").values().filterIsInstance<JSONObject>().none { it.optString("n") == step.taskId.substring(2) }) return null
    toggleDayTask(state, plan, step.date, today, step.taskId, now)
    state.remove("_undo")
    return state
}
