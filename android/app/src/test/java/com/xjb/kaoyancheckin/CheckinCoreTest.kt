package com.xjb.kaoyancheckin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CheckinCoreTest {
    private fun base() = JSONObject("""{"days":{},"weeks":{},"recur":[],"scores":[],"ch":{},"mk":[],"settings":{"exam":"2027-12-25"},"upd":0,"bk":0,"tip":0}""")
    private fun rejects(message: String? = null, block: () -> Unit) {
        val error = assertThrows(IllegalArgumentException::class.java, block)
        if (message != null) assertEquals(message, error.message)
    }

    @Test fun oldNotesAndUnclassifiedHoursMigrateWithoutMutatingInput() {
        val raw = base()
        raw.obj("days").put("2026-09-13", JSONObject("""{"t":{"words":true},"c":[],"h":2,"sh":{"英语":0.5},"note":"收获","u":1}"""))
        val out = CheckinCore.normalizeState(raw)
        val day = out.obj("days").obj("2026-09-13")
        assertEquals("收获", day.obj("rw").getString("g"))
        assertEquals(1.5, day.obj("sh").getDouble("其他"), 0.0)
        assertEquals(7, out.obj("settings").getInt("backupDays"))
        assertEquals("收获", raw.obj("days").obj("2026-09-13").getString("note"))
    }

    @Test fun legacySubjectHoursGreaterThanTotalArePreserved() {
        val out = CheckinCore.normalizeHours(JSONObject("""{"h":0,"sh":{"西综":2}}"""))
        assertEquals(2.0, out.getDouble("h"), 0.0)
    }

    @Test fun setHoursAddsEditsAndRemovesWithConsistentTotals() {
        val original = JSONObject("""{"h":0,"sh":{}}""")
        val first = CheckinCore.setHours(original, "西综", 0.5)
        assertEquals(0.5, first.getDouble("h"), 0.0)
        assertEquals(0.0, original.getDouble("h"), 0.0)
        val second = CheckinCore.setHours(first, "英语", 1.0)
        assertEquals(1.5, second.getDouble("h"), 0.0)
        assertEquals(1.0, CheckinCore.setHours(second, "西综", 0.0).getDouble("h"), 0.0)
        rejects("一天的学习时长不能超过 24 小时") { CheckinCore.setHours(second, "政治", 24.0) }
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, 24.1).forEach { value ->
            rejects("科目时长须在 0–24 小时之间") { CheckinCore.setHours(second, "英语", value) }
        }
    }

    @Test fun hoursRoundToTwoDecimalsAndPreserveUnclassifiedLegacyTime() {
        val day = JSONObject("""{"h":2,"sh":{"英语":0.5}}""")
        val out = CheckinCore.setHours(day, "英语", 0.126)
        assertEquals(0.13, out.obj("sh").getDouble("英语"), 0.0)
        assertEquals(1.63, out.getDouble("h"), 0.0)
        assertEquals(24.0, CheckinCore.setHours(JSONObject(), "西综", 24.0).getDouble("h"), 0.0)
    }

    @Test fun badTopLevelStructuresAreRejected() {
        listOf(null, JSONArray(), JSONObject(), JSONObject("""{"days":[]}""")).forEach { raw ->
            rejects("不是有效的打卡备份（缺少 days）") { CheckinCore.normalizeState(raw) }
        }
    }

    @Test fun invalidRecordsRejectWithWebErrorMessages() {
        val patches = listOf(
            """{"schemaVersion":99}""" to "备份版本较新，请先更新应用",
            """{"schemaVersion":"2"}""" to "备份版本较新，请先更新应用",
            """{"weeks":[]}""" to "周目标格式错误",
            """{"weeks":{"no":{}}}""" to "周目标含无效记录：no",
            """{"recur":"bad"}""" to "固定任务格式错误",
            """{"recur":[1]}""" to "固定任务格式错误",
            """{"scores":{}}""" to "模考成绩格式错误",
            """{"scores":[{"id":"1","d":"2026-09-13","tot":301}]}""" to "模考成绩格式错误",
            """{"ch":[]}""" to "章节格式错误",
            """{"ch":{"phys":[1]}}""" to "章节格式错误",
            """{"mk":{}}""" to "补签日期格式错误",
            """{"mk":["bad"]}""" to "补签日期格式错误",
            """{"settings":[]}""" to "初试日期格式错误",
            """{"settings":{"exam":"bad"}}""" to "初试日期格式错误",
            """{"settings":{"backupDays":0}}""" to "备份周期格式错误",
            """{"settings":{"backupDays":"7"}}""" to "备份周期格式错误",
            """{"bk":-1}""" to "备份时间格式错误",
            """{"upd":"1"}""" to "备份时间格式错误",
            """{"_recovery":12}""" to "恢复副本格式错误",
            """{"days":{"2026-02-30":{}}}""" to "每日数据含无效记录：2026-02-30",
            """{"days":{"2026-09-13":{"t":{"words":1}}}}""" to "每日数据含无效记录：2026-09-13",
            """{"days":{"2026-09-13":{"c":[{"n":"x","d":1}]}}}""" to "每日数据含无效记录：2026-09-13",
            """{"days":{"2026-09-13":{"h":-1}}}""" to "每日数据含无效记录：2026-09-13",
            """{"days":{"2026-09-13":{"rw":{"s":1}}}}""" to "每日数据含无效记录：2026-09-13"
        )
        patches.forEach { (patch, message) ->
            val raw = base()
            JSONObject(patch).let { fields -> fields.namesList().forEach { raw.put(it, fields.get(it)) } }
            rejects(message) { CheckinCore.normalizeState(raw) }
        }
    }

    @Test fun rejectsDangerousKeysAtAnyDepth() {
        listOf("__proto__", "constructor", "prototype").forEach { key ->
            rejects("数据包含不安全字段") { CheckinCore.parseBackup("""{"days":{},"$key":{"polluted":true}}""") }
            rejects("数据包含不安全字段") { CheckinCore.parseBackup("""{"days":{},"extra":[{"$key":true}]}""") }
        }
    }

    @Test fun rejectsExcessiveDepthIncludingUnknownFields() {
        var deep = JSONObject()
        repeat(18) { deep = JSONObject().put("child", deep) }
        rejects("数据嵌套过深") { CheckinCore.normalizeState(base().put("extra", deep)) }
    }

    @Test fun strictJsonParserRejectsJavascriptAndTrailingContent() {
        listOf("{days:{}}", "{'days':{}}", "{\"days\":{},}", "{\"days\":{}}oops").forEach { raw ->
            assertThrows(Exception::class.java) { CheckinCore.parseBackup(raw) }
        }
    }

    @Test fun oldWeekGoalsAndMissingSchemaRemainCompatible() {
        val raw = base()
        raw.obj("weeks").put("2026-09-07", JSONObject("""{"must":["a","",""],"md":[true,false,false],"ex":[{"n":"b","d":false}],"review":"周复盘","u":1}"""))
        assertEquals("周复盘", CheckinCore.normalizeState(raw).obj("weeks").obj("2026-09-07").getString("review"))
        assertEquals(2, CheckinCore.normalizeState(base()).getInt("schemaVersion"))
    }

    @Test fun backupRemindersIncludeChaptersAndCustomPeriod() {
        val raw = base()
        val day = 86_400_000L
        assertFalse(CheckinCore.needsBackup(raw, 10 * day))
        raw.put("ch", JSONObject("""{"phys":[true]}"""))
        assertTrue(CheckinCore.needsBackup(raw, 10 * day))
        raw.put("bk", 4 * day).obj("settings").put("backupDays", 7)
        assertFalse(CheckinCore.needsBackup(raw, 10 * day))
        assertTrue(CheckinCore.needsBackup(raw, 11 * day))
        assertTrue(CheckinCore.needsBackup(raw, 12 * day))
        raw.obj("settings").put("backupDays", 14)
        assertFalse(CheckinCore.needsBackup(raw, 12 * day))
        assertTrue(CheckinCore.needsBackup(raw, 18 * day))
        raw.obj("settings").put("backupDays", 30)
        assertFalse(CheckinCore.needsBackup(raw, 33 * day))
        assertTrue(CheckinCore.needsBackup(raw, 34 * day))
        val weekly = base().put("weeks", JSONObject().put("2026-09-07", StudyPlan.week()))
        assertTrue(CheckinCore.needsBackup(weekly, 10 * day))
    }

    @Test fun rollDateOnlyFollowsPreviouslySelectedToday() {
        assertEquals("2026-09-14", CheckinCore.rollDate("2026-09-13", "2026-09-13", "2026-09-14"))
        assertEquals("2026-09-12", CheckinCore.rollDate("2026-09-12", "2026-09-13", "2026-09-14"))
        assertEquals("2026-11-09", CheckinCore.rollDate("2026-11-02", "2026-11-02", "2026-11-09"))
    }

    @Test fun longLegacyTextAndUnboundedSubjectScoresDoNotLockArchive() {
        val raw = base().put("recur", JSONArray().put("a".repeat(500)))
        raw.arr("scores").put(JSONObject().put("id", "s1").put("d", "2026-09-13").put("n", "b".repeat(500)).put("tot", 200).put("p", JSONObject().put("phys", 301)))
        raw.obj("weeks").put("2026-09-07", StudyPlan.week().put("must", JSONArray().put("x".repeat(3000)).put("").put(""))
            .put("ex", JSONArray().put(JSONObject().put("n", "x".repeat(500)).put("d", false))).put("review", "x".repeat(21000)))
        val out = CheckinCore.normalizeState(raw)
        assertEquals(301, out.arr("scores").getJSONObject(0).obj("p").getInt("phys"))
        assertEquals(21000, out.obj("weeks").obj("2026-09-07").getString("review").length)
    }

    @Test fun allOptionalFieldsAreDeepCopiedAndRecoveryRetained() {
        val raw = base().put("schemaVersion", 1).put("tip", 1).put("bk", 2).put("_recovery", """{"days":{}}""")
        raw.obj("settings").put("backupDays", 14)
        raw.obj("days").put("2026-09-13", StudyPlan.day().put("rw", JSONObject("""{"g":"","s":"难点","n":"明天"}""")))
        raw.put("ch", JSONObject("""{"phys":[true,null,false]}""")).put("mk", JSONArray().put("2026-09-12"))
        val out = CheckinCore.normalizeState(raw)
        assertEquals(1, out.getInt("tip"))
        assertEquals(raw.getString("_recovery"), out.getString("_recovery"))
        assertEquals(14, out.obj("settings").getInt("backupDays"))
        assertEquals("难点", out.obj("days").obj("2026-09-13").obj("rw").getString("s"))
        out.obj("ch").arr("phys").put(0, false)
        out.obj("days").obj("2026-09-13").obj("rw").put("s", "changed")
        out.arr("mk").put("2026-09-14")
        assertTrue(raw.obj("ch").arr("phys").getBoolean(0))
        assertEquals("难点", raw.obj("days").obj("2026-09-13").obj("rw").getString("s"))
        assertEquals(1, raw.arr("mk").length())
        assertFalse(CheckinCore.normalizeState(base().put("_recovery", "")).has("_recovery"))
    }

    @Test fun dateAndSubjectValidationMatchesWeb() {
        listOf(null, 123, "2026-2-01", "2026-13-01", "2026-02-29", "2026-02-30").forEach { assertFalse(CheckinCore.validDate(it)) }
        assertTrue(CheckinCore.validDate("2028-02-29"))
        listOf(null, "", "constructor", "prototype", "__proto__", "x".repeat(201)).forEach { subject ->
            rejects("科目名称无效") { CheckinCore.setHours(JSONObject(), subject, 0.0) }
        }
    }

    @Test fun realStudyDataButNotEmptyPlaceholderDatesTriggersBackup() {
        assertFalse(CheckinCore.hasData(JSONObject()))
        assertFalse(CheckinCore.hasData(JSONObject("""{"days":{"d":{"t":{"words":false},"c":[],"rw":{}}}}""")))
        listOf(
            """{"days":{"d":{"h":1}}}""", """{"days":{"d":{"t":{"words":true}}}}""",
            """{"days":{"d":{"c":[{"n":"x","d":false}]}}}""", """{"days":{"d":{"rw":{"g":"收获"}}}}""",
            """{"weeks":{"w":{}}}""", """{"scores":[{}]}""", """{"ch":{"phys":[]}}""", """{"recur":["阅读"]}"""
        ).forEach { assertTrue(CheckinCore.hasData(JSONObject(it))) }
        assertFalse(CheckinCore.hasData(base().put("mk", JSONArray().put("2026-09-13"))))
    }

    @Test fun jsFalsyDefaultsAndTruthyTipArePreserved() {
        val raw = base().put("weeks", false).put("tip", "yes").put("settings", JSONObject().put("exam", ""))
        raw.obj("days").put("2026-09-13", JSONObject("""{"t":false,"c":"","sh":null,"rw":false,"h":null,"u":null}"""))
        val out = CheckinCore.normalizeState(raw)
        assertEquals(1, out.getInt("tip"))
        assertEquals("2027-12-25", out.obj("settings").getString("exam"))
        assertEquals(0, out.obj("weeks").length())
    }

    @Test fun backupSizeGuardRunsBeforeParsing() {
        rejects("备份超过 5 MB") { CheckinCore.parseBackup("x".repeat(CheckinCore.MAX_TEXT + 1)) }
    }
}
