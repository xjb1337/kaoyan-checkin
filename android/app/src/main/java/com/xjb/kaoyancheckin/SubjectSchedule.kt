package com.xjb.kaoyancheckin

import org.json.JSONArray
import org.json.JSONObject

internal const val SUBJECT_SCHEDULE = "subjectSchedule"

internal fun subjectSchedule(state: JSONObject): JSONObject = state.obj("settings").obj(SUBJECT_SCHEDULE)

internal fun subjectFocusEntry(state: JSONObject, date: String): String? {
    val changes = subjectSchedule(state).obj("focus")
    return changes.namesList().filter { it <= date }.maxOrNull()?.let(changes::getString)
}

internal fun validSubjectSchedule(value: Any?): Boolean {
    if (value !is JSONObject) return false
    val windows = value.optJSONObject("windows") ?: return false
    val focus = value.optJSONObject("focus") ?: return false
    return windows.namesList().all { key ->
        val window = windows.optJSONObject(key) ?: return@all false
        val start = window.opt("s")
        val end = window.opt("e")
        CheckinCore.validDate(start) && CheckinCore.validDate(end) && (start as String) <= (end as String)
    } && focus.namesList().all { CheckinCore.validDate(it) && focus.opt(it) is String }
}

internal fun setSubjectWindow(state: JSONObject, key: String, start: String, end: String) {
    require(CheckinCore.validDate(start) && CheckinCore.validDate(end) && start <= end) { "学习日期无效" }
    editSubjectSchedule(state) { schedule ->
        val windows = schedule.getJSONObject("windows")
        windows.put(key, JSONObject().put("s", start).put("e", end))
    }
}

internal fun setSubjectFocus(state: JSONObject, key: String?, date: String) {
    require(CheckinCore.validDate(date)) { "日期无效" }
    editSubjectSchedule(state) { it.getJSONObject("focus").put(date, key.orEmpty()) }
}

private fun editSubjectSchedule(state: JSONObject, edit: (JSONObject) -> Unit) {
    val settings = state.obj("settings")
    val schedule = settings.optJSONObject(SUBJECT_SCHEDULE)
        ?: JSONObject().put("windows", JSONObject()).put("focus", JSONObject())
    edit(schedule)
    state.put("settings", settings.put(SUBJECT_SCHEDULE, schedule))
}

internal fun completeFirstRound(state: JSONObject, subject: JSONObject) {
    val key = subject.getString("k")
    val total = subject.arr("ch").length()
    val chapters = state.obj("ch")
    val flags = chapters.optJSONArray(key) ?: JSONArray()
    repeat(total) { flags.put(it, true) }
    state.put("ch", chapters.put(key, flags))
}
