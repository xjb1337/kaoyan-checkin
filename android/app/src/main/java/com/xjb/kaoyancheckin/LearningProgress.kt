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

internal enum class ChapterEstimateTone { GOOD, BAD, NEUTRAL }

internal data class ChapterEstimateInfo(
    val text: String,
    val status: String? = null,
    val tone: ChapterEstimateTone = ChapterEstimateTone.NEUTRAL,
)

/** Dates are calendar dates supplied by the Shanghai-clock caller; estimates include the first day. */
internal fun chapterEstimate(
    today: LocalDate, start: LocalDate, end: LocalDate, done: Int, total: Int,
    first: LocalDate?, completed: LocalDate?,
): ChapterEstimateInfo = when {
    today < start && done <= 0 -> ChapterEstimateInfo("尚未开始")
    total <= 0 -> ChapterEstimateInfo("暂无章节")
    done <= 0 -> ChapterEstimateInfo("0/$total 章", "待勾选")
    done >= total -> if (completed == null) ChapterEstimateInfo("已完成", "缺完成日期")
        else {
            val difference = ChronoUnit.DAYS.between(end, completed)
            ChapterEstimateInfo(
                "已完成",
                when {
                    difference < 0 -> "提前 ${-difference} 天"
                    difference > 0 -> "推迟 $difference 天"
                    else -> "按计划"
                },
                if (difference > 0) ChapterEstimateTone.BAD else ChapterEstimateTone.GOOD,
            )
        }
    first == null -> ChapterEstimateInfo("已有勾选", "缺首次日期")
    done < MIN_CHAPTERS_FOR_FORECAST -> ChapterEstimateInfo("$done/$total 章", "数据不足")
    else -> {
        val elapsed = (ChronoUnit.DAYS.between(first, today) + 1).coerceAtLeast(1)
        if (elapsed < MIN_CHAPTER_SPAN_DAYS) {
            ChapterEstimateInfo("$done/$total 章", "数据不足")
        } else {
            val remaining = (total - done).toLong()
            // Integer ceiling avoids rounding an unfinished chapter down to zero days.
            val days = (remaining * elapsed + done - 1) / done
            val estimate = today.plusDays(days)
            val difference = ChronoUnit.DAYS.between(end, estimate)
            ChapterEstimateInfo(
                "预计 $estimate 学完",
                when {
                    difference < 0 -> "提前 ${-difference} 天"
                    difference > 0 -> "推迟 $difference 天"
                    else -> "按计划"
                },
                if (difference > 0) ChapterEstimateTone.BAD else ChapterEstimateTone.GOOD,
            )
        }
    }
}

internal fun chapterEstimate(forecast: SubjectForecast): ChapterEstimateInfo = when (forecast) {
    is SubjectForecast.NotStarted -> ChapterEstimateInfo("尚未开始")
    is SubjectForecast.Completed -> if (forecast.completedDate == null || forecast.planDeltaDays == null) {
        ChapterEstimateInfo("已完成", "缺完成日期")
    } else {
        val difference = forecast.planDeltaDays
        ChapterEstimateInfo(
            "已完成",
            when {
                difference < 0 -> "提前 ${-difference} 天"
                difference > 0 -> "推迟 $difference 天"
                else -> "按计划"
            },
            if (difference > 0) ChapterEstimateTone.BAD else ChapterEstimateTone.GOOD,
        )
    }
    is SubjectForecast.Insufficient -> when {
        forecast.total <= 0 -> ChapterEstimateInfo("暂无章节")
        forecast.reason == SubjectForecastGap.NO_CHAPTERS -> ChapterEstimateInfo("0/${forecast.total} 章", "待勾选")
        forecast.reason == SubjectForecastGap.MISSING_FIRST_DATE -> ChapterEstimateInfo("已有勾选", "缺首次日期")
        else -> ChapterEstimateInfo("${forecast.done}/${forecast.total} 章", "数据不足")
    }
    is SubjectForecast.Stalled -> ChapterEstimateInfo(
        "近${RECENT_CHAPTER_WINDOW_DAYS}天无新增 · 剩余 ${forecast.remaining} 章",
        "已停滞",
        ChapterEstimateTone.BAD,
    )
    is SubjectForecast.Estimated -> {
        val difference = forecast.planDeltaDays
        ChapterEstimateInfo(
            "预计 ${forecast.estimatedDate} 学完",
            when {
                difference < 0 -> "提前 ${-difference} 天"
                difference > 0 -> "推迟 $difference 天"
                else -> "按计划"
            },
            if (difference > 0) ChapterEstimateTone.BAD else ChapterEstimateTone.GOOD,
        )
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
    val after = (0 until total).count { flags.optBoolean(it) }
    if (after == 0) state.obj("ch").remove(key) else state.obj("ch").put(key, flags)
    val first = state.obj("cf")
    val completed = state.obj("ce")
    if (before == 0 && after > 0) first.put(key, today)
    if (after == 0) first.remove(key)
    if (before < total && after == total) completed.put(key, today)
    if (after < total) completed.remove(key)
    state.put("cf", first).put("ce", completed)
    val dates = state.obj("cd").optJSONObject(key) ?: JSONObject()
    val removedDate = if (!flags.optBoolean(index)) dates.optString(index.toString()).takeIf(CheckinCore::validDate) else null
    if (flags.optBoolean(index)) dates.put(index.toString(), today) else dates.remove(index.toString())
    if (dates.length() == 0) state.obj("cd").remove(key) else state.obj("cd").put(key, dates)
    if (after > 0 && removedDate != null && first.optString(key) == removedDate && dates.length() == after) {
        val earliest = dates.namesList().mapNotNull { dates.optString(it).takeIf(CheckinCore::validDate) }.minOrNull()
        if (earliest != null) first.put(key, earliest)
    }
    return state
}

internal fun setChapterFirstDate(state: JSONObject, key: String, date: String): JSONObject {
    require(CheckinCore.validDate(date)) { "日期无效" }
    state.obj("cf").put(key, date)
    return state
}

internal fun setChapterCompletedDate(state: JSONObject, key: String, date: String): JSONObject {
    require(CheckinCore.validDate(date)) { "日期无效" }
    state.obj("ce").put(key, date)
    return state
}
