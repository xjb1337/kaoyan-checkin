package com.xjb.kaoyancheckin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterDatesTest {
    private val subject = JSONObject()
        .put("k", "phys")
        .put("ch", JSONArray().put("一").put("二").put("三"))

    @Test fun togglingChaptersWritesAndRemovesPerChapterDates() {
        val state = CheckinCore.emptyState()
        assertTrue(state.has("cd"))
        assertEquals(0, state.obj("cd").length())

        toggleChapter(state, subject, 1, "2026-11-03")
        assertEquals("2026-11-03", state.obj("cd").obj("phys").getString("1"))
        toggleChapter(state, subject, 2, "2026-11-05")
        assertEquals("2026-11-05", state.obj("cd").obj("phys").getString("2"))

        toggleChapter(state, subject, 1, "2026-11-06")
        assertFalse(state.obj("cd").obj("phys").has("1"))
        assertEquals("2026-11-05", state.obj("cd").obj("phys").getString("2"))

        toggleChapter(state, subject, 2, "2026-11-07")
        assertFalse(state.obj("cd").has("phys"))
    }

    @Test fun uncheckedChaptersNeverKeepStaleDates() {
        val state = CheckinCore.parseBackup(
            """{"days":{},"ch":{"phys":[true,false,true]},"cd":{"phys":{"0":"2026-11-01","1":"2026-11-02","2":"2026-11-03"}}}""",
        )

        assertEquals(setOf("0", "2"), state.obj("cd").obj("phys").namesList().toSet())
        assertEquals("2026-11-01", state.obj("cd").obj("phys").getString("0"))
        assertEquals("2026-11-03", state.obj("cd").obj("phys").getString("2"))
    }

    @Test fun oldBackupsWithoutChapterDatesStillLoad() {
        val state = CheckinCore.parseBackup("""{"days":{},"ch":{"phys":[true,false]},"cf":{"phys":"2026-11-01"}}""")
        assertTrue(state.has("cd"))
        assertEquals(0, state.obj("cd").length())
        assertEquals("2026-11-01", state.obj("cf").getString("phys"))
    }

    @Test fun backupsWithChapterDatesRoundTripLosslessly() {
        val state = CheckinCore.parseBackup(
            """{"days":{},"ch":{"phys":[true,false,true]},"cf":{"phys":"2026-11-01"},"cd":{"phys":{"0":"2026-11-01","2":"2026-11-09"}}}""",
        )

        val exported = CheckinCore.backupJSON(state)
        val reimported = CheckinCore.parseBackup(exported)
        val reexported = CheckinCore.backupJSON(reimported)

        assertEquals(exported, reexported)
        assertEquals(2, reimported.obj("cd").obj("phys").length())
    }

    @Test fun emptySubjectDateMapsAreDroppedOnNormalize() {
        val state = CheckinCore.parseBackup("""{"days":{},"ch":{"phys":[true]},"cd":{"phys":{}}}""")
        assertFalse(state.obj("cd").has("phys"))
    }

    @Test fun invalidChapterDatesAreRejected() {
        val invalid = listOf(
            """{"days":{},"cd":[]}""",
            """{"days":{},"cd":{"phys":[]}}""",
            """{"days":{},"cd":{"phys":{"-1":"2026-11-01"}}}""",
            """{"days":{},"cd":{"phys":{"one":"2026-11-01"}}}""",
            """{"days":{},"cd":{"phys":{"0":"2026-02-30"}}}""",
            """{"days":{},"cd":{"phys":{"0":null}}}""",
        )

        invalid.forEach { raw ->
            val error = assertThrows(IllegalArgumentException::class.java) { CheckinCore.parseBackup(raw) }
            assertEquals("章节日期格式错误", error.message)
        }
    }
}
