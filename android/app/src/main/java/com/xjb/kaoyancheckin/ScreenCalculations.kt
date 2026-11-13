package com.xjb.kaoyancheckin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.roundToInt

internal data class PlannedSubject(
    val subject: JSONObject,
    val done: Int,
    val total: Int,
    val expected: Int,
    val upcoming: Boolean,
    val estimate: ChapterEstimateInfo,
) {
    val difference: Int get() = done - expected
}

internal fun plannedSubjects(plan: StudyPlan, state: JSONObject, today: String): List<PlannedSubject> =
    plan.subjects.map { subject ->
        val start = subject.getString("s")
        val end = subject.getString("e")
        val total = subject.arr("ch").length()
        val done = plan.chapterDone(subject, state)
        val expected = when {
            today < start -> 0
            today >= end -> total
            else -> (daysBetween(start, today).toDouble() / daysBetween(start, end).coerceAtLeast(1) * total).roundToInt()
        }
        val key = subject.getString("k")
        PlannedSubject(subject, done, total, expected, today < start && done == 0,
            chapterEstimate(LocalDate.parse(today), LocalDate.parse(start), LocalDate.parse(end), done, total,
                state.obj("cf").optString(key).takeIf(CheckinCore::validDate)?.let(LocalDate::parse),
                state.obj("ce").optString(key).takeIf(CheckinCore::validDate)?.let(LocalDate::parse)))
    }

internal fun formatUnstartedSubjects(subjects: List<PlannedSubject>): String? =
    subjects.filter { it.upcoming }.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "未开始：") {
        val date = LocalDate.parse(it.subject.getString("s"))
        "${it.subject.getString("name")} ${date.monthValue}月${date.dayOfMonth}日"
    }

internal data class ProgressCalculations(
    val words: WordProgress,
    val wordForecast: WordForecast,
    val subjectForecasts: List<SubjectForecast>,
    val hoursForecast: HoursForecast,
    val planned: List<PlannedSubject>,
    val scores: List<JSONObject>,
)

@Composable
internal fun rememberProgressCalculations(plan: StudyPlan, state: JSONObject, today: String): ProgressCalculations =
    remember(plan, state, today) {
        val date = LocalDate.parse(today)
        ProgressCalculations(wordProgress(state, today), forecastWords(state, date, exam = LocalDate.parse(plan.exam(state))),
            plan.subjects.map { forecastSubject(it, state, date) }, forecastHours(state, plan, date),
            plannedSubjects(plan, state, today), state.arr("scores").values().filterIsInstance<JSONObject>().sortedBy { it.getString("d") })
    }

@Composable
internal fun rememberTodayForecast(plan: StudyPlan, state: JSONObject, today: String): List<ForecastSummaryItem> =
    remember(plan, state, today) { forecastSummaryItems(plan, state, LocalDate.parse(today)) }

internal data class HeatmapWeek(val monday: String, val completed: List<Pair<String, Int>>)

internal data class StatsCalculations(
    val totalHours: Double,
    val activeDays: Int,
    val streak: Int,
    val makeupLeft: Int,
    val examDays: Long,
    val weeks: List<Pair<String, WeekStats>>,
    val subjectHours: List<Pair<String, Double>>,
    val heatmap: List<HeatmapWeek>,
)

internal fun statsCalculations(plan: StudyPlan, state: JSONObject, today: String): StatsCalculations {
    val records = state.obj("days")
    val dates = records.namesList().filter { it <= today }
    val currentWeek = StudyPlan.monday(today)
    val weeks = (7 downTo 0).map { offset ->
        val monday = StudyPlan.addDays(currentWeek, -7L * offset)
        monday to weekStats(plan, state, today, monday)
    }
    val totals = linkedMapOf<String, Double>()
    dates.forEach { date ->
        val subjects = records.obj(date).obj("sh")
        subjects.namesList().forEach { subject -> totals[subject] = (totals[subject] ?: 0.0) + subjects.getDouble(subject) }
    }
    val history = dates.filter { it < plan.start }.sorted()
    val heatmap = if (today < plan.start && history.isEmpty()) emptyList() else {
        val start = StudyPlan.monday(history.firstOrNull() ?: plan.start)
        val count = (daysBetween(start, currentWeek) / 7 + 1).toInt().coerceIn(1, 90)
        (0 until count).map { index ->
            val monday = StudyPlan.addDays(start, 7L * index)
            HeatmapWeek(monday, (0..6).map { offset ->
                val date = StudyPlan.addDays(monday, offset.toLong())
                val record = records.optJSONObject(date)
                date to if (record == null || date > today) 0 else plan.tasks(date, state, today).count { plan.isDone(record, it.id) }
            })
        }
    }
    return StatsCalculations(dates.sumOf { records.obj(it).optDouble("h", 0.0) },
        dates.count { plan.anyDone(records.optJSONObject(it)) }, plan.streak(state, today),
        plan.makeupLeft(today.take(7), state), daysBetween(today, plan.exam(state)), weeks,
        totals.filterValues { it > 0 }.toList().sortedByDescending { it.second }, heatmap)
}

@Composable
internal fun rememberStatsCalculations(plan: StudyPlan, state: JSONObject, today: String): StatsCalculations =
    remember(plan, state, today) { statsCalculations(plan, state, today) }
