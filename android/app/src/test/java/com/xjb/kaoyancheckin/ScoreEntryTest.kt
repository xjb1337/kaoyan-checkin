package com.xjb.kaoyancheckin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreEntryTest {
    private fun base() = JSONObject("""{"days":{},"weeks":{},"recur":[],"scores":[],"ch":{},"mk":[],"settings":{"exam":"2027-12-25"},"upd":0,"bk":0,"tip":0}""")

    @Test fun subjectKeysMatchWebExportAndDisplayInChinese() {
        assertEquals(listOf("phys", "bioc", "path", "med", "surg"), ScoreEntry.subjects.map { it.key })
        assertEquals("生理", ScoreEntry.label("phys"))
        assertEquals("外科", ScoreEntry.label("surg"))
    }

    @Test fun normalizePartsKeepsOnlyFilledSubjects() {
        val parts = ScoreEntry.normalizeParts(
            mapOf("phys" to "80", "bioc" to " 62.5 ", "path" to "", "med" to "90", "surg" to "0"),
            232.5,
        )
        assertEquals(80.0, parts.getDouble("phys"), 0.0)
        assertEquals(62.5, parts.getDouble("bioc"), 0.0)
        assertFalse(parts.has("path"))
        assertEquals(90.0, parts.getDouble("med"), 0.0)
        assertEquals(0.0, parts.getDouble("surg"), 0.0)
        assertEquals("生理 80 / 生化 62.5 / 内科 90 / 外科 0", ScoreEntry.describe(parts))
    }

    @Test fun emptyPartsRemainAnEmptyObject() {
        val parts = ScoreEntry.normalizeParts(emptyMap(), 200.0)
        assertEquals(0, parts.length())
    }

    @Test fun rejectsPartSumAboveTotal() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ScoreEntry.normalizeParts(mapOf("phys" to "100", "bioc" to "101"), 200.0)
        }
        assertEquals("分科得分合计不能超过总分", error.message)
    }

    @Test fun rejectsNonFiniteNegativeAndOversizedParts() {
        listOf("abc", "NaN", "Infinity", "-1", "300.1").forEach { value ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                ScoreEntry.normalizeParts(mapOf("phys" to value), 300.0)
            }
            assertEquals("分科得分须为 0–300 的数字", error.message)
        }
    }

    @Test fun missingPartObjectStillNormalizes() {
        val raw = base()
        raw.arr("scores").put(JSONObject("""{"id":"s1","d":"2026-11-01","n":"无分科","tot":210}"""))
        val out = CheckinCore.normalizeState(raw)
        val score = out.arr("scores").getJSONObject(0)
        assertFalse(score.has("p"))
        assertEquals("", ScoreEntry.describe(score.obj("p")))
    }

    @Test fun webJsonWithPartsReadsEverySubject() {
        val raw = base()
        raw.arr("scores").put(JSONObject(
            """{"id":"s1","d":"2026-11-01","n":"网页导入","tot":300,"p":{"phys":75,"bioc":60,"path":65,"med":45,"surg":35}}""",
        ))
        val score = CheckinCore.normalizeState(raw).arr("scores").getJSONObject(0)
        val parts = score.obj("p")
        assertEquals(75.0, parts.getDouble("phys"), 0.0)
        assertEquals(60.0, parts.getDouble("bioc"), 0.0)
        assertEquals(65.0, parts.getDouble("path"), 0.0)
        assertEquals(45.0, parts.getDouble("med"), 0.0)
        assertEquals(35.0, parts.getDouble("surg"), 0.0)
        assertTrue(ScoreEntry.describe(parts).contains("病理 65"))
    }
}
