package com.xjb.kaoyancheckin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ChapterEstimateTest {
    private fun date(value: String) = LocalDate.parse(value)
    private fun estimate(today: String = "2026-11-10", start: String = "2026-11-01", end: String = "2026-11-20",
        done: Int = 0, total: Int = 10, first: String? = null, completed: String? = null) =
        chapterEstimate(date(today), date(start), date(end), done, total, first?.let(::date), completed?.let(::date))

    @Test fun emptyBeforeStartAndMissingDatesAreExplicit() {
        assertEquals(ChapterEstimateInfo("尚未开始"), estimate(today = "2026-10-31"))
        assertEquals(ChapterEstimateInfo("0/10 章", "待勾选"), estimate())
        assertEquals(ChapterEstimateInfo("暂无章节"), estimate(done = 0, total = 0, first = "2026-11-10"))
        assertEquals(ChapterEstimateInfo("已有勾选", "缺首次日期"), estimate(done = 1))
    }

    @Test fun projectedDatesUseInclusiveDaysAndCompareWithPlan() {
        assertEquals(ChapterEstimateInfo("预计 2026-11-12 学完", "提前 8 天", ChapterEstimateTone.GOOD),
            estimate(done = 5, first = "2026-11-09"))
        assertEquals(ChapterEstimateInfo("预计 2026-12-20 学完", "推迟 30 天", ChapterEstimateTone.BAD),
            estimate(done = 2, first = "2026-11-01"))
        assertEquals(ChapterEstimateInfo("预计 2026-11-20 学完", "按计划", ChapterEstimateTone.GOOD),
            estimate(done = 5, first = "2026-11-01"))
        assertEquals(ChapterEstimateInfo("预计 2026-11-19 学完", "提前 1 天", ChapterEstimateTone.GOOD),
            estimate(done = 1, first = "2026-11-10"))
        assertEquals(estimate(done = 1, first = "2026-11-10"), estimate(done = 1, first = "2026-11-11"))
    }

    @Test fun completionUsesRecordedDateAndNeverInventsAnOldOne() {
        assertEquals(ChapterEstimateInfo("已完成", "提前 1 天", ChapterEstimateTone.GOOD),
            estimate(done = 10, first = "2026-11-01", completed = "2026-11-19"))
        assertEquals(ChapterEstimateInfo("已完成", "推迟 1 天", ChapterEstimateTone.BAD),
            estimate(done = 10, first = "2026-11-01", completed = "2026-11-21"))
        assertEquals(ChapterEstimateInfo("已完成", "按计划", ChapterEstimateTone.GOOD),
            estimate(done = 10, first = "2026-11-01", completed = "2026-11-20"))
        assertEquals(ChapterEstimateInfo("已完成", "缺完成日期"), estimate(done = 10, first = "2026-11-01"))
    }

    @Test fun chapterDatesTrackTransitionsAndMergeWithBooleanArrays() {
        val state = CheckinCore.emptyState()
        val subject = JSONObject().put("k", "phys").put("ch", JSONArray().put("一").put("二"))
        toggleChapter(state, subject, 0, "2026-11-01")
        assertEquals("2026-11-01", state.obj("cf").getString("phys"))
        toggleChapter(state, subject, 1, "2026-11-10")
        assertEquals("2026-11-10", state.obj("ce").getString("phys"))
        val roundTrip = CheckinCore.parseBackup(CheckinCore.backupJSON(state))
        assertTrue(roundTrip.obj("ch").arr("phys").getBoolean(0))
        assertEquals("2026-11-01", roundTrip.obj("cf").getString("phys"))
        assertEquals("2026-11-10", roundTrip.obj("ce").getString("phys"))
        toggleChapter(state, subject, 1, "2026-11-11")
        assertFalse(state.obj("ce").has("phys"))
        toggleChapter(state, subject, 0, "2026-11-12")
        assertFalse(state.obj("cf").has("phys"))
        toggleChapter(state, subject, 1, "2026-11-13")
        assertEquals("2026-11-13", state.obj("cf").getString("phys"))
        state.put("upd", 1)
        roundTrip.put("upd", 2)
        val merged = CheckinCore.mergeState(state, roundTrip)
        assertEquals("2026-11-01", merged.obj("cf").getString("phys"))
        assertEquals("2026-11-10", merged.obj("ce").getString("phys"))
        assertTrue(merged.obj("ch").arr("phys").getBoolean(0))
        assertThrows(IllegalArgumentException::class.java) {
            CheckinCore.parseBackup("""{"days":{},"cf":{"phys":"2026-02-30"}}""")
        }
    }

    @Test fun oldCheckedChaptersDoNotAcquireAnInventedFirstDate() {
        val state = CheckinCore.parseBackup("""{"days":{},"ch":{"phys":[true,false,false]}}""")
        val subject = JSONObject().put("k", "phys").put("ch", JSONArray().put("一").put("二").put("三"))
        toggleChapter(state, subject, 1, "2026-11-10")
        assertFalse(state.obj("cf").has("phys"))
    }
}
