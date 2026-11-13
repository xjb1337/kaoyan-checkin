package com.xjb.kaoyancheckin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ScreenCalculationsTest {
    private val plan = StudyPlan(JSONObject(File("src/main/assets/plan.json").readText()))

    @Test fun upcomingSubjectsShareOneLineInPlanOrder() {
        val rows = plannedSubjects(plan, CheckinCore.emptyState(), "2026-11-09")
        val upcoming = rows.filter { it.upcoming }
        assertEquals(5, upcoming.size)
        val expected = upcoming.joinToString("、", prefix = "未开始：") {
            val date = java.time.LocalDate.parse(it.subject.getString("s"))
            "${it.subject.getString("name")} ${date.monthValue}月${date.dayOfMonth}日"
        }
        assertEquals(expected, formatUnstartedSubjects(rows))
        assertTrue(expected.contains("生化 1月18日"))
        assertFalse(expected.contains("生理"))
    }

    @Test fun earlyStudiedSubjectIsNotHiddenInUpcomingLine() {
        val state = CheckinCore.emptyState()
        state.obj("ch").put("bioc", JSONArray().put(true))
        val rows = plannedSubjects(plan, state, "2026-11-09")
        val biochemistry = rows.single { it.subject.getString("k") == "bioc" }
        assertFalse(biochemistry.upcoming)
        assertEquals(1, biochemistry.done)
        assertEquals(0, biochemistry.expected)
        assertEquals(1, biochemistry.difference)
        assertFalse(checkNotNull(formatUnstartedSubjects(rows)).contains("生化"))
    }

    @Test fun scheduledStartMovesAZeroProgressSubjectOutOfUpcomingLine() {
        val state = CheckinCore.emptyState()
        val before = plannedSubjects(plan, state, "2027-01-17").single { it.subject.getString("k") == "bioc" }
        val after = plannedSubjects(plan, state, "2027-01-18").single { it.subject.getString("k") == "bioc" }
        assertTrue(before.upcoming)
        assertFalse(after.upcoming)
        assertEquals(0, after.expected)
    }

    @Test fun noUpcomingSubjectsMeansNoPlaceholderLine() {
        assertNull(formatUnstartedSubjects(plannedSubjects(plan, CheckinCore.emptyState(), "2028-01-01")))
        assertNull(formatUnstartedSubjects(emptyList()))
    }

    @Test fun planExpectedProgressKeepsLinearRoundingAndEndBoundary() {
        val subject = plan.subjects.first()
        val state = CheckinCore.emptyState()
        val rows = plannedSubjects(plan, state, subject.getString("e"))
        val row = rows.first()
        assertEquals(subject.arr("ch").length(), row.expected)
        assertEquals(-row.total, row.difference)
    }

    @Test fun statisticsExcludeFutureRecordsAndKeepWeeklyTotalsAndHeatmapConsistent() {
        val state = CheckinCore.emptyState()
        state.obj("days").put("2026-11-08", StudyPlan.day().put("h", 1.5)
            .put("sh", JSONObject().put("英语", 1.0).put("西综", .5)).put("t", JSONObject().put("words", true)))
        state.obj("days").put("2026-11-09", StudyPlan.day().put("h", 2.5)
            .put("sh", JSONObject().put("英语", .5).put("西综", 2.0)).put("t", JSONObject().put("words", true)))
        state.obj("days").put("2026-11-10", StudyPlan.day().put("h", 99.0)
            .put("sh", JSONObject().put("西综", 99.0)).put("t", JSONObject().put("words", true)))
        val result = statsCalculations(plan, state, "2026-11-09")
        assertEquals(4.0, result.totalHours, 0.0)
        assertEquals(2, result.activeDays)
        assertEquals(listOf("西综" to 2.5, "英语" to 1.5), result.subjectHours)
        assertEquals(8, result.weeks.size)
        result.weeks.forEach { (monday, stats) -> assertEquals(weekStats(plan, state, "2026-11-09", monday), stats) }
        assertEquals(1, result.heatmap.flatMap { it.completed }.single { it.first == "2026-11-09" }.second)
        assertEquals(0, result.heatmap.flatMap { it.completed }.single { it.first == "2026-11-10" }.second)
    }

    @Test fun prePlanHistoryIsShownButEmptyPrePlanHeatmapIsHidden() {
        val state = CheckinCore.emptyState()
        assertTrue(statsCalculations(plan, state, "2026-10-01").heatmap.isEmpty())
        state.obj("days").put("2026-09-30", StudyPlan.day())
        assertEquals(StudyPlan.monday("2026-09-30"), statsCalculations(plan, state, "2026-10-01").heatmap.first().monday)
    }
}
