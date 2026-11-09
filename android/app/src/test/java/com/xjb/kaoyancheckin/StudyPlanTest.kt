package com.xjb.kaoyancheckin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StudyPlanTest {
    private val plan = StudyPlan(JSONObject(File("src/main/assets/plan.json").readText()))

    @Test fun constantsAndChaptersAreCopiedFromWeb() {
        assertEquals("2026-11-02", plan.start)
        assertEquals("2026-09-14", plan.historyStart)
        assertEquals(6, plan.data.arr("PHASES").length())
        assertEquals(listOf(12, 12, 15, 9, 9, 4), plan.subjects.map { it.arr("ch").length() })
        assertEquals(18, plan.milestones.size)
    }

    @Test fun historicalPhaseAndTaskIdsRemainIntact() {
        val state = CheckinCore.emptyState()
        assertTrue(plan.tasks("2026-10-01", state, "2026-11-09").isEmpty())
        state.obj("days").put("2026-10-01", StudyPlan.day())
        assertEquals("背单词 50-80 个", plan.tasks("2026-10-01", state, "2026-11-09").first().label)
        assertFalse(plan.tasks("2026-10-01", state, "2026-11-09").any { it.id == "sent" })
        assertEquals("旧计划 · 生理启动", plan.phase("2026-10-01", state).getString("name"))
    }

    @Test fun phaseBoundariesAndPoliticsStartRespectWebDates() {
        val state = CheckinCore.emptyState()
        listOf(
            "2027-01-17" to "p0a", "2027-01-18" to "p0b",
            "2027-02-28" to "p0b", "2027-03-01" to "p1",
            "2027-04-18" to "p1", "2027-04-19" to "p2",
            "2027-07-25" to "p2", "2027-07-26" to "p3",
            "2027-10-10" to "p3", "2027-10-11" to "p3b",
            "2027-11-30" to "p3b",
        ).forEach { (date, phase) -> assertEquals(date, phase, plan.phase(date, state).getString("id")) }
        assertFalse(plan.tasks("2027-06-30", state, "2027-07-01").any { it.id == "zzqh" })
        assertTrue(plan.tasks("2027-07-01", state, "2027-07-01").any { it.id == "zzqh" })
        assertEquals("p4", plan.phase("2027-12-01", state).getString("id"))
        assertEquals("p5", plan.phase("2027-12-25", state).getString("id"))
        state.obj("settings").put("exam", "2027-06-01")
        assertEquals("p5", plan.phase("2027-06-01", state).getString("id"))
    }

    @Test fun phaseDailyGoalsAndTasksFollowWebPlan() {
        val state = CheckinCore.emptyState()
        listOf(
            "2027-01-17" to 1.5, "2027-01-18" to 3.5,
            "2027-02-28" to 3.5, "2027-03-01" to 1.5,
            "2027-11-30" to 1.5, "2027-12-01" to 1.5,
        ).forEach { (date, expected) -> assertEquals(date, expected, plan.goalHours(date, state, date), 0.0) }
        assertEquals(0.0, plan.goalHours("2026-10-01", state, "2026-11-09"), 0.0)
        state.obj("days").put("2026-10-01", StudyPlan.day())
        assertEquals(2.5, plan.goalHours("2026-10-01", state, "2026-11-09"), 0.0)
        assertTrue(plan.tasks("2027-01-18", state, "2027-01-18").any { it.id == "phyr" })
        assertTrue(plan.tasks("2027-03-01", state, "2027-03-01").any { it.id == "basr" })
        assertTrue(plan.tasks("2027-10-11", state, "2027-10-11").any { it.id == "hum" })
        assertTrue(plan.tasks("2027-01-17", state, "2027-01-17").first().label.contains("新词约40个"))
        assertTrue(plan.tasks("2027-03-01", state, "2027-03-01").first().label.contains("全书二轮"))
    }

    @Test fun weeklyTargetsSumDailyPhaseGoalsAndExcludeFutureDays() {
        val state = CheckinCore.emptyState()
        assertEquals(10.5, weekStats(plan, state, "2027-01-17", "2027-01-11").target, 0.0)
        assertEquals(10.5, weekStats(plan, state, "2027-01-20", "2027-01-18").target, 0.0)
        assertEquals(24.5, weekStats(plan, state, "2027-01-24", "2027-01-18").target, 0.0)
        assertEquals(18.5, weekStats(plan, state, "2027-01-21", "2027-01-15").target, 0.0)
        assertEquals(10.5, weekStats(plan, state, "2027-03-07", "2027-03-01").target, 0.0)
    }

    @Test fun minimumLineNeedsWordsAndDrillNotJustHours() {
        val state = CheckinCore.emptyState()
        val day = StudyPlan.day().put("h", 3)
        state.obj("days").put("2026-11-02", day)
        assertFalse(plan.satisfied("2026-11-02", state))
        plan.toggle(day, "words")
        assertEquals(1, plan.minCount("2026-11-02", state))
        plan.toggle(day, "drill")
        assertTrue(plan.satisfied("2026-11-02", state))
    }

    @Test fun postExamPhaseAcceptsAnyDoneButNotMakeupOrHours() {
        val state = CheckinCore.emptyState().put("mk", JSONArray().put("2027-12-25"))
        val day = StudyPlan.day().put("h", 2)
        state.obj("days").put("2027-12-25", day)
        assertFalse(plan.satisfied("2027-12-25", state))
        plan.toggle(day, "c:整理材料")
        assertTrue(plan.satisfied("2027-12-25", state))
        assertEquals(1, plan.minCount("2027-12-25", state))
    }

    @Test fun makeupQuotaIsPerCalendarMonthAndCannotDuplicateOrTargetFuture() {
        val state = CheckinCore.emptyState()
        plan.useMakeup("2026-11-02", state, "2026-12-03")
        plan.useMakeup("2026-11-03", state, "2026-12-03")
        assertEquals(0, plan.makeupLeft("2026-11", state))
        assertEquals(2, plan.makeupLeft("2026-12", state))
        assertThrows(IllegalArgumentException::class.java) { plan.useMakeup("2026-11-04", state, "2026-12-03") }
        assertThrows(IllegalArgumentException::class.java) { plan.useMakeup("2026-11-02", state, "2026-12-03") }
        assertThrows(IllegalArgumentException::class.java) { plan.useMakeup("2026-12-03", state, "2026-12-03") }
        assertThrows(IllegalArgumentException::class.java) { plan.useMakeup("2026-12-04", state, "2026-12-03") }
        plan.useMakeup("2026-12-02", state, "2026-12-03")
        assertTrue(plan.satisfied("2026-12-02", state))
        assertEquals(1, plan.makeupLeft("2026-12", state))
    }

    @Test fun recurringAndCustomTasksUseCustomArrayWithoutDuplicates() {
        val state = CheckinCore.emptyState().put("recur", JSONArray().put("阅读"))
        val day = StudyPlan.day()
        state.obj("days").put("2026-11-02", day)
        plan.toggle(day, "r:阅读")
        assertTrue(plan.isDone(day, "r:阅读"))
        assertEquals(1, plan.tasks("2026-11-02", state, "2026-11-09").count { it.label == "阅读" })
        state.put("recur", JSONArray())
        assertTrue(plan.tasks("2026-11-02", state, "2026-11-09").any { it.id == "c:阅读" })
        assertTrue(plan.isDone(day, "c:阅读"))
    }

    @Test fun streakCanStartYesterdayAndUsesMakeup() {
        val state = CheckinCore.emptyState().put("mk", JSONArray().put("2026-11-02").put("2026-11-03"))
        assertEquals(2, plan.streak(state, "2026-11-04"))
        assertEquals(0, plan.streak(state, "2026-11-05"))
        assertEquals("2026-11-02", StudyPlan.monday("2026-11-08"))
        assertEquals("2026-11-09", StudyPlan.monday("2026-11-09"))
    }
}
