package com.xjb.kaoyancheckin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DraftPatchTest {
    private val today = "2026-11-10"
    private val week = "2026-11-09"
    private val now = 123L

    @Test fun blankAndUnchangedValuesNeverCreatePlaceholderRecords() {
        val state = CheckinCore.emptyState()
        val original = state.toString()
        val blanks = listOf(
            DraftField.DayReview(today, "g") to "",
            DraftField.DayReview(today, "s") to "",
            DraftField.DayReview(today, "n") to "",
            DraftField.WeekGoal(week, 0) to "",
            DraftField.WeekGoal(week, 1) to "",
            DraftField.WeekGoal(week, 2) to "",
            DraftField.WeekReview(week) to "",
            DraftField.Hours(today, "西综") to "0.00",
        )
        blanks.forEach { (field, value) -> assertNull(patchDraft(state, field, value, today, now)) }
        assertEquals(original, state.toString())
        assertEquals(0, state.obj("days").length())
        assertEquals(0, state.obj("weeks").length())

        patchDraft(state, DraftField.DayReview(today, "g"), "收获", today, now)
        val unchanged = state.toString()
        assertNull(patchDraft(state, DraftField.DayReview(today, "g"), "收获", today, now + 1))
        assertEquals(unchanged, state.toString())
    }

    @Test fun patchesAllReviewAndGoalFieldsWithoutOverwritingOtherChanges() {
        val state = CheckinCore.emptyState()
        val day = StudyPlan.day().put("h", 1.5).put("sh", JSONObject().put("英语", 1.5))
        day.obj("t").put("words", true)
        state.obj("days").put(today, day)
        state.arr("mk").put("2026-11-08")
        val target = StudyPlan.week().put("ex", JSONArray().put(JSONObject().put("n", "弹性").put("d", true)))
        target.arr("md").put(1, true)
        state.obj("weeks").put(week, target)

        listOf("g", "s", "n").forEach { patchDraft(state, DraftField.DayReview(today, it), "复盘$it", today, now) }
        repeat(3) { patchDraft(state, DraftField.WeekGoal(week, it), "目标$it", today, now) }
        patchDraft(state, DraftField.WeekReview(week), "周复盘", today, now)
        patchDraft(state, DraftField.Hours(today, "西综"), "2", today, now)

        val updatedDay = state.obj("days").obj(today)
        listOf("g", "s", "n").forEach { assertEquals("复盘$it", updatedDay.obj("rw").getString(it)) }
        assertTrue(updatedDay.obj("t").getBoolean("words"))
        assertEquals(3.5, updatedDay.getDouble("h"), 0.0)
        assertEquals(1.5, updatedDay.obj("sh").getDouble("英语"), 0.0)
        assertEquals("2026-11-08", state.arr("mk").getString(0))
        val updatedWeek = state.obj("weeks").obj(week)
        repeat(3) { assertEquals("目标$it", updatedWeek.arr("must").getString(it)) }
        assertEquals("周复盘", updatedWeek.getString("review"))
        assertTrue(updatedWeek.arr("md").getBoolean(1))
        assertTrue(updatedWeek.arr("ex").getJSONObject(0).getBoolean("d"))
    }

    @Test fun invalidHoursAndFutureDatesDoNotChangeTheState() {
        val state = CheckinCore.emptyState()
        state.obj("days").put(today, StudyPlan.day().put("h", 23).put("sh", JSONObject().put("英语", 23)))
        val original = state.toString()
        listOf("", "NaN", "Infinity", "-1", "24.1", "2").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) {
                patchDraft(state, DraftField.Hours(today, "西综"), input, today, now)
            }
            assertEquals(original, state.toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            patchDraft(state, DraftField.DayReview("2026-11-11", "g"), "未来", today, now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            patchDraft(state, DraftField.WeekReview("2026-11-16"), "未来", today, now)
        }
        assertEquals(original, state.toString())
    }
}
