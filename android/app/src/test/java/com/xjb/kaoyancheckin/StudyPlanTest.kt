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

    @Test fun completingPhysiologyAndStartingMedicineEarlyUsesPersonalDatesWithoutInventingChapterDates() {
        val state = CheckinCore.emptyState()
        val today = "2026-11-22"
        val physiology = plan.subjects.first { it.getString("k") == "phys" }
        val firstChecked = "2026-11-06"
        toggleChapter(state, physiology, 0, firstChecked)

        completeFirstRound(state, physiology)
        plan.selectFocus(state, "med", today)
        val progress = plannedSubjects(plan, state, today).associateBy { it.subject.getString("k") }
        assertEquals(12, progress.getValue("phys").done)
        assertEquals(firstChecked, state.obj("cf").getString("phys"))
        assertEquals(setOf("0"), state.obj("cd").obj("phys").namesList().toSet())
        assertFalse(state.obj("ce").has("phys"))
        val medicine = progress.getValue("med")
        assertEquals(today, medicine.subject.getString("s"))
        assertEquals("2027-02-27", medicine.subject.getString("e"))
        assertFalse(medicine.upcoming)
        assertEquals("2027-01-18", progress.getValue("bioc").subject.getString("s"))
        assertEquals("主攻 · 内科", plan.phase(today, state).getString("name"))
        assertEquals("内科 · 0/9 章", forecastSummaryItems(plan, state, java.time.LocalDate.parse(today)).first().text)

        setSubjectWindow(state, "med", today, "2026-12-20")
        val restored = CheckinCore.parseBackupDocument(CheckinCore.backupJSON(state))
        assertEquals("med", plan.focusSubject(today, restored)?.getString("k"))
        assertEquals("2026-12-20", plannedSubjects(plan, restored, today)
            .first { it.subject.getString("k") == "med" }.subject.getString("e"))
        val mergedWithOlderFormat = CheckinCore.importBackup(restored, """{"days":{},"upd":50}""")
        assertEquals("med", plan.focusSubject(today, mergedWithOlderFormat)?.getString("k"))
    }

    @Test fun switchingAndReturningToCalendarKeepEarlierSubjectsAndSameDayCompletedTasks() {
        val state = CheckinCore.emptyState()
        val yesterday = "2026-11-21"
        val today = "2026-11-22"
        val tomorrow = "2026-11-23"
        state.obj("days").put(yesterday, dayWith("phy"))
        state.obj("days").put(today, dayWith("phy", "words", "drill"))

        plan.selectFocus(state, "med", today)
        assertEquals(listOf("words", "drill", "phy", "sent"), plan.tasks(yesterday, state, today).map { it.id })
        assertEquals(listOf("words", "drill", "med", "sent", "phy"), plan.tasks(today, state, today).map { it.id })
        val widget = widgetSnapshot(plan, state, today)
        assertEquals(3, widget.totalDone)
        assertEquals(5, widget.totalTasks)
        assertEquals(listOf("words", "drill"), widget.tasks.filter { it.minimum }.map { it.id })
        assertTrue(widget.satisfied)

        state.obj("days").put(tomorrow, dayWith("med"))
        plan.selectFocus(state, null, tomorrow)
        assertTrue(plan.tasks(tomorrow, state, tomorrow).any { it.id == "med" })
        assertEquals("med", plan.focusSubject(today, state)?.getString("k"))
        assertNull(plan.focusSubject(tomorrow, state))
        assertFalse(plan.tasks("2026-11-24", state, "2026-11-24").any { it.id == "med" })
    }

    private fun dayWith(vararg checked: String): JSONObject = StudyPlan.day().apply {
        checked.forEach { obj("t").put(it, true) }
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

    @Test fun deletingCustomTaskOnlyChangesThatDaysCustomList() {
        val state = CheckinCore.emptyState().put("recur", JSONArray().put("阅读"))
        val day = StudyPlan.day()
        day.arr("c").put(JSONObject().put("n", "阅读").put("d", true))
        day.arr("c").put(JSONObject().put("n", "整理错题").put("d", false))
        state.obj("days").put("2026-11-09", day)

        plan.deleteCustomTask(day, "r:阅读")
        assertEquals(listOf("整理错题"), day.arr("c").values().map { (it as JSONObject).getString("n") })
        assertEquals(listOf("阅读"), state.arr("recur").values())
        assertFalse(plan.tasks("2026-11-09", state, "2026-11-09").any { it.id == "r:阅读" })
        assertTrue(plan.tasks("2026-11-10", state, "2026-11-10").any { it.id == "r:阅读" })

        plan.deleteCustomTask(day, "c:整理错题")
        assertEquals(0, day.arr("c").length())
        assertEquals(listOf("阅读"), state.arr("recur").values())
    }

    @Test fun recurringStartDateAndRetirementPreserveHistoryAndPhaseCopiesAreIsolated() {
        val state = CheckinCore.emptyState()
        StudyPlan.addRecurringTask(state, "新固定", "2026-11-10")
        assertFalse(plan.tasks("2026-11-09", state, "2026-11-10").any { it.id == "r:新固定" })
        assertTrue(plan.tasks("2026-11-10", state, "2026-11-10").any { it.id == "r:新固定" })

        val past = StudyPlan.day().apply { arr("c").put(JSONObject().put("n", "旧固定").put("d", false)) }
        state.obj("days").put("2026-11-05", past)
        StudyPlan.retireRecurringTask(state, "旧固定")
        assertFalse(plan.tasks("2026-11-05", state, "2026-11-10").any { it.label == "旧固定" })

        state.obj("settings").put("exam", "2027-06-01")
        val legacy = plan.phase("2026-10-01", state)
        assertEquals("旧计划 · 生理启动", legacy.getString("name"))
        legacy.put("name", "mutated")
        assertEquals("旧计划 · 生理启动", plan.phase("2026-10-01", state).getString("name"))
    }

    @Test fun copiesPreviousWeekGoalsAndResetsCompletion() {
        val state = CheckinCore.emptyState()
        val previous = StudyPlan.week()
            .put("must", JSONArray().put("生理").put("英语").put("政治"))
            .put("md", JSONArray().put(true).put(true).put(false))
            .put("u", 10)
        state.obj("weeks").put("2026-11-02", previous)

        assertTrue(plan.copyPreviousWeekGoals(state, "2026-11-09", 20))
        val copied = state.obj("weeks").obj("2026-11-09")
        assertEquals(listOf("生理", "英语", "政治"), copied.arr("must").values())
        assertEquals(listOf(false, false, false), copied.arr("md").values())
        assertEquals(20L, copied.getLong("u"))
        assertEquals(listOf(true, true, false), previous.arr("md").values())

        assertTrue(plan.copyPreviousWeekGoals(state, "2026-11-09", 30))
        assertEquals(30L, copied.getLong("u"))
    }

    @Test fun missingPreviousWeekDoesNotCreateEmptyPlaceholder() {
        val state = CheckinCore.emptyState()
        assertFalse(plan.copyPreviousWeekGoals(state, "2026-11-09", 20))
        assertFalse(state.obj("weeks").has("2026-11-09"))
    }

    @Test fun forwardNavigationStopsAtTodayAndCurrentWeek() {
        assertEquals("2026-11-09", StudyPlan.nextWithinLimit("2026-11-08", "2026-11-09", 1))
        assertNull(StudyPlan.nextWithinLimit("2026-11-09", "2026-11-09", 1))
        assertNull(StudyPlan.nextWithinLimit("2026-11-10", "2026-11-09", 1))
        assertEquals("2026-11-09", StudyPlan.nextWithinLimit("2026-11-02", "2026-11-09", 7))
        assertNull(StudyPlan.nextWithinLimit("2026-11-09", "2026-11-09", 7))
        assertNull(StudyPlan.nextWithinLimit("2026-11-16", "2026-11-09", 7))
    }

    @Test fun streakCanStartYesterdayAndUsesMakeup() {
        val state = CheckinCore.emptyState().put("mk", JSONArray().put("2026-11-02").put("2026-11-03"))
        assertEquals(2, plan.streak(state, "2026-11-04"))
        assertEquals(0, plan.streak(state, "2026-11-05"))
        assertEquals("2026-11-02", StudyPlan.monday("2026-11-08"))
        assertEquals("2026-11-09", StudyPlan.monday("2026-11-09"))
    }
}
