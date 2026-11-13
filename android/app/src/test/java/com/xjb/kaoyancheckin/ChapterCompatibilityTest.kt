package com.xjb.kaoyancheckin

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ChapterCompatibilityTest {
    private val plan = StudyPlan(JSONObject(File("src/main/assets/plan.json").readText()))

    // The old web plan's chapter order is a compatibility fixture: ch stores positions, not names.
    private val oldChapters = linkedMapOf(
        "phys" to listOf("绪论", "细胞的基本功能", "血液", "血液循环", "呼吸", "消化与吸收", "能量代谢与体温", "尿的生成与排出", "感觉器官的功能", "神经系统的功能", "内分泌", "生殖"),
        "bioc" to listOf("蛋白质的结构与功能", "核酸的结构与功能", "酶", "糖代谢", "脂质代谢", "氨基酸代谢", "核苷酸代谢", "代谢的整合与调节", "DNA的生物合成", "RNA的生物合成", "蛋白质的生物合成", "基因表达调控与信号转导"),
        "path" to listOf("细胞和组织的适应与损伤", "损伤的修复", "局部血液循环障碍", "炎症", "肿瘤", "心血管系统疾病", "呼吸系统疾病", "消化系统疾病", "泌尿系统疾病", "生殖系统与乳腺疾病", "内分泌系统疾病", "神经系统疾病", "传染病与寄生虫病"),
        "med" to listOf("呼吸系统疾病", "循环系统疾病", "消化系统疾病", "泌尿系统疾病", "血液系统疾病", "内分泌与代谢疾病", "风湿性疾病", "理化因素所致疾病"),
        "surg" to listOf("外科总论", "麻醉与围术期", "外科感染与创伤", "普通外科", "骨科", "泌尿外科", "神经外科", "胸心外科", "血管外科"),
    )

    // Same schema and ch structure as a JSON backup exported by the old web application.
    private val oldWebBackup = """{
        "schemaVersion":2,"days":{},"weeks":{},"recur":[],"scores":[],
        "ch":{
            "phys":[true],"bioc":[false,true],
            "path":[true,false,false,false,false,false,false,false,false,false,false,false,true],
            "med":[true,false,false,false,false,false,false,true],"surg":[false,true]
        },
        "mk":[],"settings":{"exam":"2027-12-25","backupDays":7},"upd":200,"bk":0,"tip":0
    }"""

    private val checkedNames = mapOf(
        "phys" to listOf("绪论"),
        "bioc" to listOf("核酸的结构与功能"),
        "path" to listOf("细胞和组织的适应与损伤", "传染病与寄生虫病"),
        "med" to listOf("呼吸系统疾病", "理化因素所致疾病"),
        "surg" to listOf("麻醉与围术期"),
        "hum" to emptyList(),
    )

    private fun assertLegacyProgress(state: JSONObject) {
        assertEquals(checkedNames.keys, plan.subjects.map { it.getString("k") }.toSet())
        plan.subjects.forEach { subject ->
            val key = subject.getString("k")
            val chapters = subject.arr("ch")
            val flags = state.obj("ch").arr(key)
            val names = (0 until chapters.length()).filter { flags.optBoolean(it) }.map { chapters.getString(it) }
            assertEquals(key, checkedNames.getValue(key), names)
            assertEquals(key, names.size, plan.chapterDone(subject, state))
        }
        assertFalse(state.obj("ch").has("hum"))
        assertEquals(2, plan.chapterDone(plan.subjects.first { it.getString("k") == "path" }, state))
        assertEquals(2, plan.chapterDone(plan.subjects.first { it.getString("k") == "med" }, state))
        assertEquals(0, plan.chapterDone(plan.subjects.first { it.getString("k") == "hum" }, state))
    }

    @Test fun everyOldChapterKeepsItsSubjectAndIndex() {
        oldChapters.forEach { (key, old) ->
            val subject = plan.subjects.single { it.getString("k") == key }
            val current = subject.arr("ch").values().map { it as String }
            assertEquals(key, old, current.take(old.size))
        }
        assertEquals(listOf("免疫性疾病", "淋巴造血系统疾病"), plan.subjects.single { it.getString("k") == "path" }.arr("ch").values().drop(13))
        assertEquals(listOf("诊断学（常见症状与体格检查）"), plan.subjects.single { it.getString("k") == "med" }.arr("ch").values().drop(8))
    }

    @Test fun oldLocalChapterFlagsStillMapToTheSameNamesAndCounts() {
        val localState = CheckinCore.parseBackupDocument(oldWebBackup)
        assertLegacyProgress(localState)
        assertEquals(0, localState.obj("cd").length())
    }

    @Test fun webJsonImportPreservesOldChapterNamesAndCountsForMergeAndReplace() {
        val incoming = CheckinCore.parseBackup(oldWebBackup) // Settings preview uses this same parser.
        assertLegacyProgress(incoming)
        listOf("merge", "replace").forEach { mode ->
            val local = CheckinCore.emptyState().put("upd", 100)
            local.obj("ch").put("surg", org.json.JSONArray().put(true))
            val imported = CheckinCore.importBackup(local, oldWebBackup, mode, now = 300)
            assertEquals(2, imported.getInt("schemaVersion"))
            assertTrue(JSONObject(oldWebBackup).getJSONObject("ch").similar(imported.obj("ch")))
            assertLegacyProgress(imported)
            assertEquals(0, imported.obj("cd").length())
        }
    }
}
