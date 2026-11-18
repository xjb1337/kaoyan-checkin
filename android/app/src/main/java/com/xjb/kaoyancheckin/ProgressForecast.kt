package com.xjb.kaoyancheckin

import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Shared look-back window for word and study-hour forecasts. */
internal const val FORECAST_WINDOW_DAYS = 14L

/** A word forecast needs at least this many recorded study days inside the window. */
internal const val MIN_WORD_ACTIVE_DAYS = 5

/** A chapter forecast needs at least this many completed chapters... */
internal const val MIN_CHAPTERS_FOR_FORECAST = 2

/** ...and at least this many days since the first chapter was checked. */
internal const val MIN_CHAPTER_SPAN_DAYS = 7L

/** Recent chapter pace uses this many calendar days, ending today. */
internal const val RECENT_CHAPTER_WINDOW_DAYS = 28L

/** A recent chapter pace needs at least this many checked chapters with dates. */
internal const val MIN_DATED_CHAPTERS_FOR_RECENT_FORECAST = 3

/** ...and at least this many days between the earliest and latest chapter date. */
internal const val MIN_DATED_CHAPTER_SPAN_DAYS = 7L

/** A study-hour forecast needs at least this many days with recorded hours. */
internal const val MIN_HOURS_ACTIVE_DAYS = 5

/**
 * plan.json 的 MILES「2027-02-28 寒假结束检查」写明“单词第一轮基本过完”，
 * 因此把这一天作为单词第一轮的计划节点。
 */
internal val WORD_FIRST_ROUND_TARGET: LocalDate = LocalDate.of(2027, 2, 28)

internal enum class ForecastTone { GOOD, BAD, NEUTRAL }

internal sealed interface WordForecast {
    val total: Long
    val goal: Int

    data class Completed(override val total: Long, override val goal: Int) : WordForecast

    data class Insufficient(
        override val total: Long,
        override val goal: Int,
        val activeDays: Int,
    ) : WordForecast

    data class Stalled(
        override val total: Long,
        override val goal: Int,
        val activeDays: Int,
        val recentWords: Long,
        val requiredDaily: Int?,
        val requiredDailyForExam: Boolean,
        val examPassed: Boolean,
    ) : WordForecast

    data class Estimated(
        override val total: Long,
        override val goal: Int,
        val windowStart: LocalDate,
        val windowEnd: LocalDate,
        val activeDays: Int,
        val averagePerDay: Double,
        val estimatedDate: LocalDate,
        val planTarget: LocalDate,
        /** estimate - target; negative is early, positive is late. */
        val planDeltaDays: Long,
        val exam: LocalDate,
        /** exam - estimate; negative means the estimate is after the exam. */
        val examMarginDays: Long,
        val requiredDaily: Int?,
        /** True when [requiredDaily] targets the exam rather than [planTarget]. */
        val requiredDailyForExam: Boolean,
        val referenceYear: Int = windowEnd.year,
    ) : WordForecast
}

internal sealed interface SubjectForecast {
    val subjectName: String
    val done: Int
    val total: Int

    data class NotStarted(
        override val subjectName: String,
        override val done: Int,
        override val total: Int,
        val planStart: LocalDate,
        val referenceYear: Int? = null,
    ) : SubjectForecast

    data class Completed(
        override val subjectName: String,
        override val done: Int,
        override val total: Int,
        val planEnd: LocalDate,
        val completedDate: LocalDate?,
        /** completedDate - planEnd; negative is early, positive is late. */
        val planDeltaDays: Long?,
        val referenceYear: Int? = null,
    ) : SubjectForecast

    data class Insufficient(
        override val subjectName: String,
        override val done: Int,
        override val total: Int,
        val reason: SubjectForecastGap,
        val firstDate: LocalDate?,
        val elapsedDays: Long?,
    ) : SubjectForecast

    data class Stalled(
        override val subjectName: String,
        override val done: Int,
        override val total: Int,
        val planEnd: LocalDate,
        val remaining: Int,
        val weeklyNeeded: Double?,
        val planOverdue: Boolean,
        val referenceYear: Int? = null,
    ) : SubjectForecast

    data class Estimated(
        override val subjectName: String,
        override val done: Int,
        override val total: Int,
        val firstDate: LocalDate,
        /** Inclusive: the first checked day counts as day one. */
        val elapsedDays: Long,
        val estimatedDate: LocalDate,
        val planEnd: LocalDate,
        /** estimate - planEnd; negative is early, positive is late. */
        val planDeltaDays: Long,
        val remaining: Int,
        val weeklyNeeded: Double?,
        val planOverdue: Boolean,
        val planEndsToday: Boolean,
        val pace: SubjectPace = SubjectPace.AVERAGE,
        val referenceYear: Int? = null,
    ) : SubjectForecast
}

internal enum class SubjectPace { RECENT, AVERAGE }

internal enum class SubjectForecastGap {
    NO_CHAPTERS,
    MISSING_FIRST_DATE,
    TOO_FEW_CHAPTERS,
    TOO_SHORT,
}

internal sealed interface HoursForecast {
    data class Insufficient(
        val activeDays: Int,
        val windowStart: LocalDate?,
        val windowEnd: LocalDate?,
    ) : HoursForecast

    data class Estimated(
        val windowStart: LocalDate,
        val windowEnd: LocalDate,
        val activeDays: Int,
        val averageActual: Double,
        val averageTarget: Double,
        val achievementRate: Double,
    ) : HoursForecast
}

internal val WordForecast.tone: ForecastTone
    get() = when (this) {
        is WordForecast.Completed -> ForecastTone.GOOD
        is WordForecast.Insufficient -> ForecastTone.NEUTRAL
        is WordForecast.Stalled -> ForecastTone.BAD
        is WordForecast.Estimated -> if (planDeltaDays <= 0) ForecastTone.GOOD else ForecastTone.BAD
    }

internal val SubjectForecast.tone: ForecastTone
    get() = when (this) {
        is SubjectForecast.Completed -> ForecastTone.GOOD
        is SubjectForecast.NotStarted -> ForecastTone.NEUTRAL
        is SubjectForecast.Insufficient -> ForecastTone.NEUTRAL
        is SubjectForecast.Stalled -> ForecastTone.BAD
        is SubjectForecast.Estimated -> if (planDeltaDays <= 0) ForecastTone.GOOD else ForecastTone.BAD
    }

internal val HoursForecast.tone: ForecastTone
    get() = when (this) {
        is HoursForecast.Insufficient -> ForecastTone.NEUTRAL
        is HoursForecast.Estimated -> if (achievementRate >= 100.0) ForecastTone.GOOD else ForecastTone.BAD
    }

/**
 * Word pace uses the last [FORECAST_WINDOW_DAYS] calendar days, counting missing days as zero.
 * If today's new-word count is zero (or not recorded), the window ends yesterday because today
 * may simply still be unfinished; the projection is then counted forward from today, not from
 * yesterday, so a one-day estimate means "finish tomorrow", not "should have finished yesterday".
 */
internal fun forecastWords(
    state: JSONObject,
    today: LocalDate,
    target: LocalDate = WORD_FIRST_ROUND_TARGET,
    exam: LocalDate,
): WordForecast {
    val progress = wordProgress(state, today.toString())
    if (progress.total >= progress.goal) return WordForecast.Completed(progress.total, progress.goal)

    val days = state.obj("days")
    val todayNew = days.obj(today.toString()).obj("w").optInt("n", 0)
    val windowEnd = if (todayNew > 0) today else today.minusDays(1)
    val defaultStart = windowEnd.minusDays(FORECAST_WINDOW_DAYS - 1)
    val earliestPositive = days.namesList()
        .mapNotNull { raw ->
            val date = parseDate(raw) ?: return@mapNotNull null
            if (date > windowEnd) return@mapNotNull null
            val count = days.obj(raw).obj("w").optInt("n", 0)
            if (count > 0) date else null
        }
        .minOrNull()
        ?: return WordForecast.Insufficient(progress.total, progress.goal, 0)

    val windowStart = maxOf(defaultStart, earliestPositive)
    val calendarDays = ChronoUnit.DAYS.between(windowStart, windowEnd) + 1
    if (calendarDays <= 0) return WordForecast.Insufficient(progress.total, progress.goal, 0)

    var newWords = 0L
    var activeDays = 0
    var date = windowStart
    while (date <= windowEnd) {
        val count = days.obj(date.toString()).obj("w").optInt("n", 0)
        if (count > 0) {
            newWords += count
            activeDays++
        }
        date = date.plusDays(1)
    }
    val effectiveTarget = minOf(target, exam)
    val remaining = (progress.goal.toLong() - progress.total).coerceAtLeast(1)
    if (activeDays < MIN_WORD_ACTIVE_DAYS || newWords <= 0) {
        if (earliestPositive < defaultStart) {
            val deadline = when {
                effectiveTarget >= today -> effectiveTarget
                exam >= today -> exam
                else -> null
            }
            val requiredDaily = deadline?.let { d ->
                val daysLeft = ChronoUnit.DAYS.between(today, d).coerceAtLeast(1)
                ceil(remaining.toDouble() / daysLeft).toInt()
            }
            return WordForecast.Stalled(
                total = progress.total,
                goal = progress.goal,
                activeDays = activeDays,
                recentWords = newWords,
                requiredDaily = requiredDaily,
                requiredDailyForExam = deadline == exam && effectiveTarget < today,
                examPassed = today > exam,
            )
        }
        return WordForecast.Insufficient(progress.total, progress.goal, activeDays)
    }

    val averagePerDay = newWords.toDouble() / calendarDays
    val daysNeeded = ceil(remaining / averagePerDay).toLong()
    val estimateBase = if (windowEnd == today) windowEnd else today
    val estimatedDate = estimateBase.plusDays(daysNeeded)
    val planDeltaDays = ChronoUnit.DAYS.between(effectiveTarget, estimatedDate)
    val examMarginDays = ChronoUnit.DAYS.between(estimatedDate, exam)

    val requiredDailyDeadline = when {
        estimatedDate > exam && exam >= today -> exam
        effectiveTarget >= today && estimatedDate > effectiveTarget -> effectiveTarget
        else -> null
    }
    val requiredDaily = requiredDailyDeadline?.let { deadline ->
        val daysLeft = ChronoUnit.DAYS.between(today, deadline).coerceAtLeast(1)
        ceil(remaining.toDouble() / daysLeft).toInt()
    }

    return WordForecast.Estimated(
        total = progress.total,
        goal = progress.goal,
        windowStart = windowStart,
        windowEnd = windowEnd,
        activeDays = activeDays,
        averagePerDay = averagePerDay,
        estimatedDate = estimatedDate,
        planTarget = effectiveTarget,
        planDeltaDays = planDeltaDays,
        exam = exam,
        examMarginDays = examMarginDays,
        requiredDaily = requiredDaily,
        requiredDailyForExam = requiredDailyDeadline == exam,
        referenceYear = today.year,
    )
}

/**
 * Uses per-chapter dates when at least [MIN_DATED_CHAPTERS_FOR_RECENT_FORECAST] checked chapters
 * span [MIN_DATED_CHAPTER_SPAN_DAYS] days. The recent pace counts chapters checked in the last
 * [RECENT_CHAPTER_WINDOW_DAYS] days. Otherwise it falls back to the average since the first check.
 * Elapsed time includes the first day.
 */
internal fun forecastSubject(subject: JSONObject, state: JSONObject, today: LocalDate): SubjectForecast {
    val key = subject.getString("k")
    val subjectName = subject.getString("name")
    val total = subject.arr("ch").length()
    val flags = state.obj("ch").arr(key)
    val done = (0 until total).count { flags.optBoolean(it) }
    val planStart = LocalDate.parse(subject.getString("s"))
    val planEnd = LocalDate.parse(subject.getString("e"))

    if (today < planStart && done <= 0) {
        return SubjectForecast.NotStarted(subjectName, done, total, planStart, today.year)
    }
    val datedChapters = datedCheckedChapters(state, key, flags, total, today)
    if (total > 0 && done >= total) {
        val completedDate = parseDate(state.obj("ce").optString(key))
            ?: datedChapters.maxOfOrNull { it.second }
        return SubjectForecast.Completed(
            subjectName = subjectName,
            done = done,
            total = total,
            planEnd = planEnd,
            completedDate = completedDate,
            planDeltaDays = completedDate?.let { ChronoUnit.DAYS.between(planEnd, it) },
            referenceYear = today.year,
        )
    }
    if (done <= 0) {
        return SubjectForecast.Insufficient(subjectName, done, total, SubjectForecastGap.NO_CHAPTERS, null, null)
    }

    val cfDate = parseDate(state.obj("cf").optString(key))
    if (datedChapters.size >= MIN_DATED_CHAPTERS_FOR_RECENT_FORECAST) {
        val earliestDated = datedChapters.minOf { it.second }
        val earliest = if (cfDate != null) minOf(cfDate, earliestDated) else earliestDated
        val latest = datedChapters.maxOf { it.second }
        if (ChronoUnit.DAYS.between(earliest, latest) >= MIN_DATED_CHAPTER_SPAN_DAYS) {
            val windowStart = maxOf(today.minusDays(RECENT_CHAPTER_WINDOW_DAYS - 1), earliest)
            val windowDays = ChronoUnit.DAYS.between(windowStart, today) + 1
            val recentDone = datedChapters.count { !it.second.isBefore(windowStart) }
            val remaining = total - done
            if (recentDone > 0 && windowDays > 0) {
                val daysNeeded = (remaining.toLong() * windowDays + recentDone - 1) / recentDone
                val estimatedDate = today.plusDays(daysNeeded)
                return SubjectForecast.Estimated(
                    subjectName = subjectName,
                    done = done,
                    total = total,
                    firstDate = earliest,
                    elapsedDays = ChronoUnit.DAYS.between(earliest, today) + 1,
                    estimatedDate = estimatedDate,
                    planEnd = planEnd,
                    planDeltaDays = ChronoUnit.DAYS.between(planEnd, estimatedDate),
                    remaining = remaining,
                    weeklyNeeded = weeklyNeeded(remaining, today, planEnd),
                    planOverdue = today > planEnd,
                    planEndsToday = today == planEnd,
                    pace = SubjectPace.RECENT,
                    referenceYear = today.year,
                )
            }
            return SubjectForecast.Stalled(
                subjectName = subjectName,
                done = done,
                total = total,
                planEnd = planEnd,
                remaining = remaining,
                weeklyNeeded = weeklyNeeded(remaining, today, planEnd),
                planOverdue = today > planEnd,
                referenceYear = today.year,
            )
        }
    }

    val firstDate = cfDate
        ?: datedChapters.takeIf { it.size == done }?.minOfOrNull { it.second }
        ?: return SubjectForecast.Insufficient(
            subjectName, done, total, SubjectForecastGap.MISSING_FIRST_DATE, null, null,
        )
    val elapsedDays = ChronoUnit.DAYS.between(firstDate, today) + 1
    if (done < MIN_CHAPTERS_FOR_FORECAST) {
        return SubjectForecast.Insufficient(
            subjectName, done, total, SubjectForecastGap.TOO_FEW_CHAPTERS, firstDate, elapsedDays,
        )
    }
    if (elapsedDays < MIN_CHAPTER_SPAN_DAYS) {
        return SubjectForecast.Insufficient(
            subjectName, done, total, SubjectForecastGap.TOO_SHORT, firstDate, elapsedDays,
        )
    }

    val remaining = total - done
    val daysNeeded = (remaining.toLong() * elapsedDays + done - 1) / done
    val estimatedDate = today.plusDays(daysNeeded)
    return SubjectForecast.Estimated(
        subjectName = subjectName,
        done = done,
        total = total,
        firstDate = firstDate,
        elapsedDays = elapsedDays,
        estimatedDate = estimatedDate,
        planEnd = planEnd,
        planDeltaDays = ChronoUnit.DAYS.between(planEnd, estimatedDate),
        remaining = remaining,
        weeklyNeeded = weeklyNeeded(remaining, today, planEnd),
        planOverdue = today > planEnd,
        planEndsToday = today == planEnd,
        pace = SubjectPace.AVERAGE,
        referenceYear = today.year,
    )
}

private fun datedCheckedChapters(
    state: JSONObject,
    key: String,
    flags: org.json.JSONArray,
    total: Int,
    today: LocalDate,
): List<Pair<Int, LocalDate>> {
    val dates = state.obj("cd").optJSONObject(key) ?: return emptyList()
    return dates.namesList().mapNotNull { rawIndex ->
        val index = rawIndex.toIntOrNull() ?: return@mapNotNull null
        val date = parseDate(dates.optString(rawIndex)) ?: return@mapNotNull null
        if (index !in 0 until total || !flags.optBoolean(index) || date > today) null else index to date
    }
}

private fun weeklyNeeded(remaining: Int, today: LocalDate, planEnd: LocalDate): Double? {
    val remainingToEnd = ChronoUnit.DAYS.between(today, planEnd)
    return if (remainingToEnd > 0) remaining * 7.0 / remainingToEnd else null
}

/**
 * Uses actual and target hours over the same recent window. Missing-day hours count as zero, and
 * the window starts no earlier than the plan start. If today has no hours yet, the window ends
 * yesterday so an unfinished day is not treated as a zero-hour miss.
 */
internal fun forecastHours(state: JSONObject, plan: StudyPlan, today: LocalDate): HoursForecast {
    val days = state.obj("days")
    val todayHours = days.obj(today.toString()).optDouble("h", 0.0)
    val windowEnd = if (todayHours > 0) today else today.minusDays(1)
    val planStart = LocalDate.parse(plan.start)
    val windowStart = maxOf(windowEnd.minusDays(FORECAST_WINDOW_DAYS - 1), planStart)
    if (windowEnd < planStart || windowEnd < windowStart) {
        return HoursForecast.Insufficient(0, null, null)
    }

    val calendarDays = ChronoUnit.DAYS.between(windowStart, windowEnd) + 1
    var actual = 0.0
    var target = 0.0
    var activeDays = 0
    var date = windowStart
    while (date <= windowEnd) {
        val key = date.toString()
        val hours = days.obj(key).optDouble("h", 0.0)
        if (hours > 0) activeDays++
        actual += hours
        target += plan.goalHours(key, state, today.toString())
        date = date.plusDays(1)
    }
    val averageActual = actual / calendarDays
    val averageTarget = target / calendarDays
    val achievementRate = if (averageTarget > 0) averageActual / averageTarget * 100.0 else 0.0
    if (activeDays < MIN_HOURS_ACTIVE_DAYS) {
        val earliestPositive = days.namesList().mapNotNull { raw ->
            val d = parseDate(raw) ?: return@mapNotNull null
            if (d <= windowEnd && days.obj(raw).optDouble("h", 0.0) > 0.0) d else null
        }.minOrNull()
        if (earliestPositive != null && earliestPositive < windowStart && achievementRate < 100.0) {
            return HoursForecast.Estimated(
                windowStart = windowStart,
                windowEnd = windowEnd,
                activeDays = activeDays,
                averageActual = averageActual,
                averageTarget = averageTarget,
                achievementRate = achievementRate,
            )
        }
        return HoursForecast.Insufficient(activeDays, windowStart, windowEnd)
    }

    return HoursForecast.Estimated(
        windowStart = windowStart,
        windowEnd = windowEnd,
        activeDays = activeDays,
        averageActual = averageActual,
        averageTarget = averageTarget,
        achievementRate = achievementRate,
    )
}

internal fun formatWordsForecast(forecast: WordForecast): String = when (forecast) {
    is WordForecast.Completed ->
        "单词：累计 ${forecast.total} / ${forecast.goal}"
    is WordForecast.Insufficient ->
        "单词：累计 ${forecast.total} / ${forecast.goal}"
    is WordForecast.Stalled -> buildString {
        append("单词：近${FORECAST_WINDOW_DAYS}天仅 ${forecast.activeDays} 天 · 距目标还剩 ${(forecast.goal.toLong() - forecast.total).coerceAtLeast(0)}")
        if (forecast.examPassed) {
            append(" · 初试已过未完成")
        } else if (forecast.requiredDaily != null) {
            append(" · 日均需 ${forecast.requiredDaily} 个")
        }
    }
    is WordForecast.Estimated -> buildString {
        append("单词：日均新词 ${oneDecimal(forecast.averagePerDay)} 个 · ")
        append("预计 ${monthDay(forecast.estimatedDate, forecast.referenceYear)} 过完第一轮")
        if (forecast.requiredDaily != null && !forecast.requiredDailyForExam) {
            append(" · 日均需 ${forecast.requiredDaily} 个")
        }
        when {
            forecast.examMarginDays > 0 -> append(" · 距初试 ${forecast.examMarginDays} 天")
            forecast.examMarginDays == 0L -> append(" · 初试当天")
            else -> {
                append(" · 初试前完不成")
                if (forecast.requiredDaily != null && forecast.requiredDailyForExam) {
                    append(" · 日均需 ${forecast.requiredDaily} 个")
                }
                append(" · 晚于初试 ${-forecast.examMarginDays} 天")
            }
        }
    }
}

internal fun formatSubjectForecast(forecast: SubjectForecast): String = when (forecast) {
    is SubjectForecast.NotStarted ->
        "${forecast.subjectName}：${monthDay(forecast.planStart, forecast.referenceYear)} 开始"
    is SubjectForecast.Completed -> when {
        forecast.planDeltaDays == null ->
            "${forecast.subjectName}：已完成"
        forecast.completedDate == null ->
            "${forecast.subjectName}：已完成"
        else -> "${forecast.subjectName}：已完成 · ${monthDay(forecast.completedDate, forecast.referenceYear)}"
    }
    is SubjectForecast.Insufficient ->
        "${forecast.subjectName}：${forecast.done}/${forecast.total} 章"
    is SubjectForecast.Stalled -> buildString {
        append("${forecast.subjectName}：近${RECENT_CHAPTER_WINDOW_DAYS}天无新增 · 剩余 ${forecast.remaining} 章")
        if (forecast.planOverdue) {
            append(" · 计划已于 ${monthDay(forecast.planEnd, forecast.referenceYear)} 截止")
        } else if (forecast.weeklyNeeded != null) {
            append(" · 每周需 ${oneDecimal(forecast.weeklyNeeded)} 章")
        }
    }
    is SubjectForecast.Estimated -> buildString {
        append("${forecast.subjectName}：预计 ${monthDay(forecast.estimatedDate, forecast.referenceYear)} 学完")
        when {
            forecast.planOverdue -> append(" · 计划已于 ${monthDay(forecast.planEnd, forecast.referenceYear)} 截止")
            forecast.planEndsToday -> append(" · 剩余 ${forecast.remaining} 章")
            forecast.weeklyNeeded != null ->
                append(" · 每周需 ${oneDecimal(forecast.weeklyNeeded)} 章")
        }
    }
}

internal fun formatUpcomingSubjects(forecasts: List<SubjectForecast>): String? {
    val upcoming = forecasts.filterIsInstance<SubjectForecast.NotStarted>()
    if (upcoming.isEmpty()) return null
    return "后续：" + upcoming.joinToString("、") { "${it.subjectName} ${monthDay(it.planStart, it.referenceYear)}" } + "开始"
}

internal fun formatHoursForecast(forecast: HoursForecast): String = when (forecast) {
    is HoursForecast.Insufficient -> "时长"
    is HoursForecast.Estimated ->
        "时长：日均 ${oneDecimal(forecast.averageActual)}h / 目标 " +
            "${oneDecimal(forecast.averageTarget)}h · 达成率 ${forecast.achievementRate.roundToInt()}%"
}

internal data class ForecastSummaryItem(
    val text: String,
    val status: String,
    val tone: ForecastTone,
    val method: String? = null,
)

/** Home-screen highlights: current phase subjects first, then words, then study hours. */
internal fun forecastSummaryItems(plan: StudyPlan, state: JSONObject, today: LocalDate): List<ForecastSummaryItem> {
    val exam = LocalDate.parse(plan.exam(state))
    val phase = plan.phase(today.toString(), state)
    val phaseStart = parseDate(phase.optString("s"))
    val phaseEnd = parseDate(phase.optString("e"))
    val currentSubjects = plan.subjects.filter { subject ->
        val start = parseDate(subject.optString("s")) ?: return@filter false
        val end = parseDate(subject.optString("e")) ?: return@filter false
        val total = subject.arr("ch").length()
        val done = plan.chapterDone(subject, state)
        val inWindow = !today.isBefore(start) && !today.isAfter(end) &&
            (phaseStart == null || phaseEnd == null || (start <= phaseEnd && end >= phaseStart))
        val startedEarly = today.isBefore(start) && done > 0
        val overdueUnfinished = today.isAfter(end) && done < total
        inWindow || startedEarly || overdueUnfinished
    }

    val items = mutableListOf<ForecastSummaryItem>()
    currentSubjects.forEach { subject ->
        val end = parseDate(subject.optString("e"))
        when (val forecast = forecastSubject(subject, state, today)) {
            is SubjectForecast.Estimated ->
                items.add(ForecastSummaryItem(
                    text = "${forecast.subjectName} · 预计 ${monthDay(forecast.estimatedDate, today.year)} 学完",
                    status = earlyOrLate(forecast.planDeltaDays),
                    tone = forecast.tone,
                    method = if (forecast.pace == SubjectPace.RECENT) "近${RECENT_CHAPTER_WINDOW_DAYS}天" else "平均",
                ))
            is SubjectForecast.Stalled ->
                items.add(ForecastSummaryItem(
                    text = "${forecast.subjectName} · 近${RECENT_CHAPTER_WINDOW_DAYS}天无新增",
                    status = "已停滞",
                    tone = ForecastTone.BAD,
                    method = "近${RECENT_CHAPTER_WINDOW_DAYS}天",
                ))
            is SubjectForecast.Completed -> items.add(ForecastSummaryItem(
                text = forecast.subjectName,
                status = "已完成",
                tone = ForecastTone.GOOD,
            ))
            is SubjectForecast.Insufficient -> if (end != null && today.isAfter(end) && forecast.done < forecast.total) {
                items.add(ForecastSummaryItem(
                    text = "${forecast.subjectName} · ${forecast.done}/${forecast.total} 章",
                    status = "已逾期",
                    tone = ForecastTone.BAD,
                ))
            }
            else -> Unit
        }
    }
    when (val forecast = forecastWords(state, today, exam = exam)) {
        is WordForecast.Estimated -> {
            val requiredDaily = forecast.requiredDaily
            items.add(if (forecast.estimatedDate > exam && requiredDaily != null) {
                ForecastSummaryItem(
                    text = "单词 · 初试前完不成",
                    status = "日均需 $requiredDaily 个",
                    tone = ForecastTone.BAD,
                    method = "近${FORECAST_WINDOW_DAYS}天",
                )
            } else {
                ForecastSummaryItem(
                    text = "单词 · 预计 ${monthDay(forecast.estimatedDate, today.year)} 过完第一轮",
                    status = earlyOrLate(forecast.planDeltaDays),
                    tone = forecast.tone,
                    method = "近${FORECAST_WINDOW_DAYS}天",
                )
            })
        }
        is WordForecast.Stalled -> items.add(ForecastSummaryItem(
            text = if (forecast.examPassed) "单词 · 初试已过未完成" else "单词 · 近${FORECAST_WINDOW_DAYS}天仅 ${forecast.activeDays} 天",
            status = forecast.requiredDaily?.let { "日均需 $it 个" } ?: "进度停滞",
            tone = ForecastTone.BAD,
            method = "近${FORECAST_WINDOW_DAYS}天",
        ))
        is WordForecast.Completed -> items.add(ForecastSummaryItem("单词第一轮", "已完成", ForecastTone.GOOD))
        is WordForecast.Insufficient -> Unit
    }
    when (val forecast = forecastHours(state, plan, today)) {
        is HoursForecast.Estimated ->
            items.add(ForecastSummaryItem(
                text = "日均学习 ${oneDecimal(forecast.averageActual)}h",
                status = "达成率 ${forecast.achievementRate.roundToInt()}%",
                tone = forecast.tone,
                method = "近${FORECAST_WINDOW_DAYS}天",
            ))
        is HoursForecast.Insufficient -> Unit
    }
    return items
}

private fun parseDate(value: String): LocalDate? {
    if (value.isEmpty()) return null
    return runCatching { LocalDate.parse(value) }.getOrNull()?.takeIf { it.toString() == value }
}

private fun monthDay(date: LocalDate, referenceYear: Int? = null): String =
    if (referenceYear == null || date.year == referenceYear) "${date.monthValue}月${date.dayOfMonth}日"
    else "${date.year}年${date.monthValue}月${date.dayOfMonth}日"

private fun earlyOrLate(deltaDays: Long): String = when {
    deltaDays < 0 -> "提前 ${-deltaDays} 天"
    deltaDays > 0 -> "落后 $deltaDays 天"
    else -> "正好按计划"
}

private fun oneDecimal(value: Double): String {
    val tenths = (value * 10.0).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}
