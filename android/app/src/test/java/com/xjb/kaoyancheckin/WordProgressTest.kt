package com.xjb.kaoyancheckin

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WordProgressTest {
    private val date = "2026-11-10"

    @Test fun absentFieldsDefaultAndValidWordCountsSurviveRoundTrip() {
        val legacy = CheckinCore.parseBackup("""{"schemaVersion":1,"days":{"2026-11-10":{}}}""")
        assertEquals(1300, legacy.obj("settings").getInt("wordBase"))
        assertEquals(5500, legacy.obj("settings").getInt("wordGoal"))
        assertEquals(WordProgress(1300, 5500, 0, 0), wordProgress(legacy, date))
        legacy.obj("days").obj(date).put("w", JSONObject().put("n", 2000))
        legacy.obj("settings").put("wordBase", 1200).put("wordGoal", 6000)
        val restored = CheckinCore.parseBackup(CheckinCore.backupJSON(legacy))
        assertEquals(2000, restored.obj("days").obj(date).obj("w").getInt("n"))
        assertEquals(0, restored.obj("days").obj(date).obj("w").getInt("r"))
        assertEquals(WordProgress(3200, 6000, 2000, 0), wordProgress(restored, date))
        assertEquals(2, restored.getInt("schemaVersion"))
    }

    @Test fun invalidWordCountsAndSettingsAreRejectedWithChineseErrors() {
        listOf("-1", "2001", "1.5", "1.0000000000000000001", "null", "\"3\"").forEach { value ->
            listOf("n", "r").forEach { field ->
                val error = assertThrows(IllegalArgumentException::class.java) {
                    CheckinCore.parseBackup("""{"days":{"$date":{"w":{"$field":$value}}}}""")
                }
                assertTrue(error.message!!.contains("每日数据"))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { CheckinCore.parseBackup("""{"days":{"$date":{"w":[]}}}""") }
        listOf("-1", "20001", "1.5", "null", "\"3\"").forEach { value ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                CheckinCore.parseBackup("""{"days":{},"settings":{"wordBase":$value}}""")
            }
            assertTrue(error.message!!.contains("单词基数"))
        }
        listOf("0", "50001", "-1", "1.5", "null").forEach { value ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                CheckinCore.parseBackup("""{"days":{},"settings":{"wordGoal":$value}}""")
            }
            assertTrue(error.message!!.contains("单词目标"))
        }
        val bounds = CheckinCore.parseBackup("""{"days":{},"settings":{"wordBase":0,"wordGoal":50000}}""")
        assertEquals(0, bounds.obj("settings").getInt("wordBase"))
        assertEquals(50000, bounds.obj("settings").getInt("wordGoal"))
        val otherBounds = CheckinCore.parseBackup("""{"days":{},"settings":{"wordBase":20000,"wordGoal":1}}""")
        assertEquals(20000, otherBounds.obj("settings").getInt("wordBase"))
        assertEquals(1, otherBounds.obj("settings").getInt("wordGoal"))
    }

    @Test fun settingsFollowGlobalTimestampAndSurviveBackup() {
        val local = CheckinCore.emptyState().put("upd", 10)
        local.obj("settings").put("wordBase", 1400).put("wordGoal", 5800)
        val incoming = CheckinCore.emptyState().put("upd", 11)
        incoming.obj("settings").put("wordBase", 1500).put("wordGoal", 6000)
        val merged = CheckinCore.mergeState(local, incoming)
        val restored = CheckinCore.parseBackup(CheckinCore.backupJSON(merged))
        assertEquals(1500, restored.obj("settings").getInt("wordBase"))
        assertEquals(6000, restored.obj("settings").getInt("wordGoal"))
        incoming.put("upd", 10)
        assertEquals(1400, CheckinCore.mergeState(local, incoming).obj("settings").getInt("wordBase"))
    }

    @Test fun numbersFollowDayTimestampWhileReviewDoesNotIncreaseTotal() {
        val local = CheckinCore.emptyState().put("upd", 100)
        local.obj("days").put(date, StudyPlan.day().put("u", 50).put("w", JSONObject().put("n", 20).put("r", 100)))
        val incoming = CheckinCore.emptyState().put("upd", 1)
        incoming.obj("days").put(date, StudyPlan.day().put("u", 51).put("w", JSONObject().put("n", 40).put("r", 500)))
        incoming.obj("days").put("2026-11-09", StudyPlan.day().put("u", 1).put("w", JSONObject().put("n", 10).put("r", 800)))
        val out = CheckinCore.mergeState(local, incoming)
        assertEquals(WordProgress(1350, 5500, 40, 500), wordProgress(out, date))
        assertEquals(4150L, wordProgress(out, date).remaining)
        incoming.obj("days").obj(date).put("u", 50)
        assertEquals(20, CheckinCore.mergeState(local, incoming).obj("days").obj(date).obj("w").getInt("n"))
    }

    @Test fun wordsAloneCountAsDataAndDraftsValidateWithoutCreatingBlankDays() {
        val state = CheckinCore.emptyState()
        val field = DraftField.Words(date, "n")
        assertNull(patchDraft(state, field, "", date, 1))
        assertNull(patchDraft(state, field, "0", date, 1))
        assertFalse(CheckinCore.hasData(state))
        listOf("-1", "2001", "1.5", "abc").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { patchDraft(state, field, input, date, 1) }
        }
        patchDraft(state, DraftField.Words(date, "r"), "50", date, 2)
        assertTrue(CheckinCore.hasData(state))
        patchDraft(state, field, "10", date, 3)
        assertEquals(WordProgress(1310, 5500, 10, 50), wordProgress(state, date))
        patchDraft(state, field, "", date, 4)
        assertEquals(0, state.obj("days").obj(date).obj("w").getInt("n"))
        assertThrows(IllegalArgumentException::class.java) { patchDraft(state, DraftField.Words("2026-11-11", "n"), "1", date, 1) }
    }
}
