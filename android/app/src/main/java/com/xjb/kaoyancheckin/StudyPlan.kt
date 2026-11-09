package com.xjb.kaoyancheckin

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

data class StudyTask(val id: String, val label: String, val tag: String)

class StudyPlan(val data: JSONObject) {
    val start: String = data.getString("START")
    val historyStart: String = data.getString("HISTORY_START")
    val subjects: List<JSONObject> = data.arr("SUBJ").values().map { it as JSONObject }
    val milestones: List<JSONArray> = data.arr("MILES").values().map { it as JSONArray }

    fun exam(state: JSONObject): String = state.obj("settings").optString("exam", "2027-12-25")
    fun phase(date: String, state: JSONObject): JSONObject {
        val exam = exam(state)
        if (date >= exam) return data.obj("PH5").copyJson().put("s", exam).put("e", "2028-08-31")
        if (date < start) return data.obj("LEGACY_PHASE")
        data.arr("PHASES").values().map { it as JSONObject }.firstOrNull { date >= it.getString("s") && date <= it.getString("e") }?.let { return it }
        if (date >= "2027-12-01" && date < exam) return data.obj("PH4").copyJson().put("s", "2027-12-01").put("e", addDays(exam, -1))
        return data.arr("PHASES").getJSONObject(0)
    }

    fun tasks(date: String, state: JSONObject, today: String): List<StudyTask> {
        val record = state.obj("days").optJSONObject(date)
        val beforePlan = date < start && (date >= today || record == null)
        val tasks = if (beforePlan) mutableListOf() else phase(date, state).arr("tasks").values().map { it as JSONObject }
            .filter { !it.has("from") || date >= it.getString("from") }
            .map { StudyTask(it.getString("id"), it.getString("label"), it.getString("tag")) }.toMutableList()
        val recurring = state.arr("recur").values().map { it as String }
        recurring.forEach { tasks.add(StudyTask("r:$it", it, "自定义")) }
        record?.arr("c")?.values()?.map { it as JSONObject }?.filter { it.optString("n").isNotEmpty() && it.getString("n") !in recurring }
            ?.forEach { tasks.add(StudyTask("c:${it.getString("n")}", it.getString("n"), "自定义")) }
        return tasks
    }

    fun isDone(record: JSONObject?, id: String): Boolean = if (id.startsWith("r:") || id.startsWith("c:")) {
        record?.arr("c")?.values()?.any { it is JSONObject && it.optString("n") == id.substring(2) && it.optBoolean("d") } == true
    } else record?.obj("t")?.optBoolean(id) == true

    fun toggle(record: JSONObject, id: String) {
        if (id.startsWith("r:") || id.startsWith("c:")) {
            val name = id.substring(2)
            val task = record.arr("c").values().filterIsInstance<JSONObject>().firstOrNull { it.optString("n") == name }
            if (task == null) record.arr("c").put(JSONObject().put("n", name).put("d", true)) else task.put("d", !task.optBoolean("d"))
        } else record.obj("t").put(id, !record.obj("t").optBoolean(id))
    }

    fun anyDone(record: JSONObject?): Boolean = record != null &&
        (record.obj("t").namesList().any { record.obj("t").optBoolean(it) } || record.arr("c").values().any { (it as JSONObject).optBoolean("d") })

    fun minCount(date: String, state: JSONObject): Int {
        val record = state.obj("days").optJSONObject(date)
        if (phase(date, state).optInt("fs") == 1) return if (anyDone(record)) 1 else 0
        return listOf("words", "drill").count { record?.obj("t")?.optBoolean(it) == true }
    }

    fun makeupLeft(month: String, state: JSONObject): Int = (2 - state.arr("mk").values().count { (it as String).startsWith(month) }).coerceAtLeast(0)
    fun isMakeup(date: String, state: JSONObject): Boolean = state.arr("mk").values().contains(date)
    fun satisfied(date: String, state: JSONObject): Boolean = if (phase(date, state).optInt("fs") == 1) anyDone(state.obj("days").optJSONObject(date)) else minCount(date, state) >= 2 || isMakeup(date, state)

    fun useMakeup(date: String, state: JSONObject, today: String) {
        require(date >= start && date < today && phase(date, state).optInt("fs") != 1 && !satisfied(date, state)) { "这天无需补签或不能补签" }
        require(makeupLeft(date.take(7), state) > 0) { "本月补签卡已用完" }
        state.arr("mk").put(date)
    }

    fun streak(state: JSONObject, today: String): Int {
        var date = if (satisfied(today, state)) today else addDays(today, -1)
        var count = 0
        while (count < 999 && satisfied(date, state)) { count++; date = addDays(date, -1) }
        return count
    }

    companion object {
        fun addDays(date: String, days: Long): String = LocalDate.parse(date).plusDays(days).toString()
        fun monday(date: String): String = LocalDate.parse(date).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
        fun day(): JSONObject = JSONObject("""{"t":{},"c":[],"h":0,"sh":{},"rw":{"g":"","s":"","n":""},"u":0}""")
        fun week(): JSONObject = JSONObject("""{"must":["","",""],"md":[false,false,false],"ex":[],"review":"","u":0}""")
    }
}
