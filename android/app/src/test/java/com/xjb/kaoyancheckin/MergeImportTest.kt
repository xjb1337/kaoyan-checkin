package com.xjb.kaoyancheckin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MergeImportTest {
    private fun state(time: Long, date: String = "2026-11-02", hours: Double = 1.0): JSONObject = CheckinCore.emptyState().apply {
        put("upd", time)
        obj("days").put(date, StudyPlan.day().put("u", time).put("h", hours).put("sh", JSONObject().put("西综", hours)))
    }

    @Test fun dayMergeUsesRecordTimestampNotGlobalTimestamp() {
        val current = state(100)
        val incoming = state(200, hours = 2.0)
        incoming.obj("days").obj("2026-11-02").put("u", 50)
        incoming.obj("days").put("2026-11-03", StudyPlan.day().put("u", 1))
        val out = CheckinCore.mergeState(current, incoming)
        assertEquals(1.0, out.obj("days").obj("2026-11-02").getDouble("h"), 0.0)
        assertTrue(out.obj("days").has("2026-11-03"))
        assertEquals(200, out.getInt("upd"))
        assertFalse(current.obj("days").has("2026-11-03"))
    }

    @Test fun newerDayAndWeekWinEvenWithOlderGlobalTimestamp() {
        val current = state(100)
        current.obj("weeks").put("2026-11-02", StudyPlan.week().put("u", 10))
        val incoming = state(20, hours = 3.0)
        incoming.obj("days").obj("2026-11-02").put("u", 101)
        incoming.obj("weeks").put("2026-11-02", StudyPlan.week().put("u", 11).put("review", "new"))
        val out = CheckinCore.mergeState(current, incoming)
        assertEquals(3.0, out.obj("days").obj("2026-11-02").getDouble("h"), 0.0)
        assertEquals("new", out.obj("weeks").obj("2026-11-02").getString("review"))
        assertEquals(100, out.getInt("upd"))
    }

    @Test fun equalTimestampsKeepLocalDayWeekAndGlobalFields() {
        val current = state(100)
        current.obj("weeks").put("2026-11-02", StudyPlan.week().put("u", 100).put("review", "local"))
        val incoming = state(100, hours = 2.0).put("recur", JSONArray().put("incoming"))
        incoming.obj("weeks").put("2026-11-02", StudyPlan.week().put("u", 100).put("review", "incoming"))
        val out = CheckinCore.mergeState(current, incoming)
        assertEquals(1.0, out.obj("days").obj("2026-11-02").getDouble("h"), 0.0)
        assertEquals("local", out.obj("weeks").obj("2026-11-02").getString("review"))
        assertEquals(0, out.arr("recur").length())
    }

    @Test fun newerGlobalSnapshotReplacesCollectionsButTipAndBackupAreMonotonic() {
        val current = state(10).put("tip", 1).put("bk", 100)
        val incoming = state(20).put("tip", 0).put("bk", 50).put("recur", JSONArray().put("每天阅读"))
            .put("ch", JSONObject("""{"phys":[true]}""")).put("mk", JSONArray().put("2026-11-01"))
        incoming.obj("settings").put("exam", "2028-01-01").put("backupDays", 30)
        incoming.arr("scores").put(JSONObject("""{"id":"s1","d":"2026-11-01","tot":200,"p":{}}"""))
        val out = CheckinCore.mergeState(current, incoming)
        assertEquals("每天阅读", out.arr("recur").getString(0))
        assertTrue(out.obj("ch").arr("phys").getBoolean(0))
        assertEquals(1, out.arr("scores").length())
        assertEquals("2026-11-01", out.arr("mk").getString(0))
        assertEquals("2028-01-01", out.obj("settings").getString("exam"))
        assertEquals(30, out.obj("settings").getInt("backupDays"))
        assertEquals(1, out.getInt("tip"))
        assertEquals(100, out.getInt("bk"))
        out.obj("ch").arr("phys").put(0, false)
        assertTrue(incoming.obj("ch").arr("phys").getBoolean(0))
    }

    @Test fun newerGlobalTipAndLargerBackupAreAdopted() {
        val out = CheckinCore.mergeState(state(10), state(20).put("tip", 1).put("bk", 100))
        assertEquals(1, out.getInt("tip"))
        assertEquals(100, out.getInt("bk"))
    }

    @Test fun unchangedMergePreservesWebNoChangeTimestampBehavior() {
        val current = state(10)
        val incoming = current.copyJson().put("upd", 20)
        assertEquals(10, CheckinCore.mergeState(current, incoming).getInt("upd"))
    }

    @Test fun fullRestoreRemovesLocalOnlyRecordsAndUsesIncomingSettings() {
        val current = state(100)
        val incoming = state(10, "2026-11-03").put("settings", JSONObject().put("exam", "2028-01-01").put("backupDays", 14))
        val out = CheckinCore.importBackup(current, incoming.toString(), "replace", 200)
        assertFalse(out.obj("days").has("2026-11-02"))
        assertTrue(out.obj("days").has("2026-11-03"))
        assertEquals("2028-01-01", out.obj("settings").getString("exam"))
        assertEquals(200, out.getInt("upd"))
    }

    @Test fun importWritesExactlyOnePreImportSnapshotAndStripsRecoveryOnExport() {
        val current = state(100).put("_recovery", "older snapshot")
        val incoming = state(200, "2026-11-03").put("_recovery", "untrusted incoming snapshot")
        val out = CheckinCore.importBackup(current, incoming.toString(), now = 300)
        assertEquals(CheckinCore.backupJSON(current), out.getString("_recovery"))
        assertFalse(JSONObject(out.getString("_recovery")).has("_recovery"))
        assertEquals(2, out.obj("days").length())
        assertEquals(300, out.getInt("upd"))
        assertFalse(JSONObject(CheckinCore.backupJSON(out)).has("_recovery"))
        assertEquals(2, JSONObject(CheckinCore.backupJSON(out)).getInt("schemaVersion"))
    }

    @Test fun restorePreviousSwapsSnapshotsWithoutNestingRecovery() {
        val first = state(10)
        val imported = CheckinCore.importBackup(first, state(20, "2026-11-03").toString(), "replace", 30)
        val restored = CheckinCore.importBackup(imported, imported.getString("_recovery"), "replace", 40)
        assertTrue(restored.obj("days").has("2026-11-02"))
        assertFalse(restored.obj("days").has("2026-11-03"))
        assertEquals(CheckinCore.backupJSON(imported), restored.getString("_recovery"))
    }

    @Test fun corruptOriginalIsPreservedAndForcesReplace() {
        val original = "{broken json"
        val out = CheckinCore.importBackup(state(10), state(20, "2026-11-03").toString(), now = 30, recoveryRaw = original)
        assertEquals(original, out.getString("_recovery"))
        assertFalse(out.obj("days").has("2026-11-02"))
    }

    @Test fun rejectedImportLeavesOriginalAndItsRecoveryUntouched() {
        val current = state(10).put("_recovery", "keep me")
        val before = current.toString()
        assertThrows(IllegalArgumentException::class.java) { CheckinCore.importBackup(current, """{"days":{},"schemaVersion":99}""") }
        assertEquals(before, current.toString())
    }

    private fun chapterState(time: Long, flags: List<Boolean>, dates: Map<String, String>): JSONObject =
        CheckinCore.emptyState().apply {
            put("upd", time)
            obj("ch").put("phys", JSONArray(flags))
            if (dates.isNotEmpty()) obj("cd").put("phys", JSONObject(dates))
        }

    @Test fun newerChapterSnapshotMovesFlagsAndDatesTogether() {
        val current = chapterState(100, listOf(true), mapOf("0" to "2026-11-01")).put("cf", JSONObject().put("phys", "2026-11-01"))
        val incoming = chapterState(
            200,
            listOf(true, true),
            mapOf("0" to "2026-11-02", "1" to "2026-11-09"),
        ).put("cf", JSONObject().put("phys", "2026-11-02"))

        val out = CheckinCore.mergeState(current, incoming)

        assertEquals(2, out.obj("ch").arr("phys").length())
        assertTrue(out.obj("ch").arr("phys").getBoolean(1))
        assertEquals(mapOf("0" to "2026-11-02", "1" to "2026-11-09"), out.obj("cd").obj("phys").let { dates ->
            dates.namesList().associateWith { dates.getString(it) }
        })
        assertEquals("2026-11-02", out.obj("cf").getString("phys"))
    }

    @Test fun olderChapterSnapshotKeepsCurrentFlagsAndDates() {
        val current = chapterState(
            200,
            listOf(true, true),
            mapOf("0" to "2026-11-02", "1" to "2026-11-09"),
        )
        val incoming = chapterState(100, listOf(true), mapOf("0" to "2026-11-01"))

        val out = CheckinCore.mergeState(current, incoming)

        assertEquals(2, out.obj("ch").arr("phys").length())
        assertEquals("2026-11-02", out.obj("cd").obj("phys").getString("0"))
        assertEquals("2026-11-09", out.obj("cd").obj("phys").getString("1"))
    }

    @Test fun newerSnapshotWithoutDatesClearsChapterDates() {
        val current = chapterState(
            100,
            listOf(true, true),
            mapOf("0" to "2026-11-02", "1" to "2026-11-09"),
        )
        val incoming = JSONObject()
            .put("days", JSONObject())
            .put("upd", 200)
            .put("ch", JSONObject().put("phys", JSONArray().put(true).put(true)))
        assertFalse(incoming.has("cd"))

        val out = CheckinCore.mergeState(current, incoming)

        assertEquals(2, out.obj("ch").arr("phys").length())
        assertTrue(out.has("cd"))
        assertEquals(0, out.obj("cd").length())
    }

    @Test fun newerDatesUpdateEvenWhenChapterFlagsMatch() {
        val current = chapterState(100, listOf(true, true), mapOf("0" to "2026-11-01", "1" to "2026-11-02"))
        val incoming = chapterState(200, listOf(true, true), mapOf("0" to "2026-11-03", "1" to "2026-11-09"))

        val out = CheckinCore.mergeState(current, incoming)

        assertEquals("2026-11-03", out.obj("cd").obj("phys").getString("0"))
        assertEquals("2026-11-09", out.obj("cd").obj("phys").getString("1"))
    }
}
