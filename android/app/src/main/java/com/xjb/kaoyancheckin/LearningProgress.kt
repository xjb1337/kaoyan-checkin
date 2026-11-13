package com.xjb.kaoyancheckin

import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal data class WordProgress(val total: Long, val goal: Int, val todayNew: Int, val todayReview: Int) {
    val remaining: Long get() = (goal.toLong() - total).coerceAtLeast(0)
}

internal fun wordProgress(state: JSONObject, today: String): WordProgress {
    val days = state.obj("days")
    val base = state.obj("settings").optInt("wordBase", CheckinCore.DEFAULT_WORD_BASE)
    val total = base.toLong() + days.namesList().sumOf { days.obj(it).obj("w").optInt("n", 0).toLong() }
    val words = days.obj(today).obj("w")
    return WordProgress(total, state.obj("settings").optInt("wordGoal", CheckinCore.DEFAULT_WORD_GOAL),
        words.optInt("n", 0), words.optInt("r", 0))
}

private fun planComparison(date: LocalDate, planEnd: LocalDate): String {
    val difference = ChronoUnit.DAYS.between(planEnd, date)
    return when {
        difference < 0 -> "比计划提前 ${-difference} 天"
        difference > 0 -> "比计划推迟 $difference 天"
        else -> "正好按计划"
    }
}

/** Dates are calendar dates supplied by the Shanghai-clock caller; estimates include the first day. */
internal fun chapterEstimate(
    today: LocalDate, start: LocalDate, end: LocalDate, done: Int, total: Int,
    first: LocalDate?, completed: LocalDate?,
): String = when {
    today < start -> "尚未开始"
    total <= 0 || done <= 0 -> "还没有勾选章节，无法估算"
    done >= total -> if (completed == null) "已完成；缺少完成日期，暂无法与计划比较"
        else "已完成，${planComparison(completed, end)}"
    first == null -> "已有勾选，但缺少首次勾选日期，暂无法估算"
    else -> {
        val elapsed = (ChronoUnit.DAYS.between(first, today) + 1).coerceAtLeast(1)
        val remaining = (total - done).toLong()
        // Integer ceiling avoids rounding an unfinished chapter down to zero days.
        val days = (remaining * elapsed + done - 1) / done
        val estimate = today.plusDays(days)
        "按当前速度，预计 $estimate 学完，${planComparison(estimate, end)}"
    }
}

/** Keep the legacy boolean array untouched in shape; dates are independent subject maps. */
internal fun toggleChapter(state: JSONObject, subject: JSONObject, index: Int, today: String): JSONObject {
    val key = subject.getString("k")
    val total = subject.arr("ch").length()
    require(index in 0 until total && CheckinCore.validDate(today)) { "章节或日期无效" }
    val flags = state.obj("ch").arr(key)
    val before = (0 until total).count { flags.optBoolean(it) }
    while (flags.length() <= index) flags.put(false)
    flags.put(index, !flags.optBoolean(index))
    state.obj("ch").put(key, flags)
    val after = (0 until total).count { flags.optBoolean(it) }
    val first = state.obj("cf")
    val completed = state.obj("ce")
    if (before == 0 && after > 0) first.put(key, today)
    if (after == 0) first.remove(key)
    if (before < total && after == total) completed.put(key, today)
    if (after < total) completed.remove(key)
    state.put("cf", first).put("ce", completed)
    val dates = state.obj("cd").optJSONObject(key) ?: JSONObject()
    if (flags.optBoolean(index)) dates.put(index.toString(), today) else dates.remove(index.toString())
    if (dates.length() == 0) state.obj("cd").remove(key) else state.obj("cd").put(key, dates)
    return state
}
