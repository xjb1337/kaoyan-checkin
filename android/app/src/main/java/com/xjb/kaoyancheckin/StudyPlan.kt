package com.xjb.kaoyancheckin

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

data class StudyTask(val id: String, val label: String, val tag: String)

class StudyPlan(val data: JSONObject) {
    val start: String = data.getString("START")
    val historyStart: String = data.getString("HISTORY_START")
    val subjects: List<JSONObject> = data.arr("SUBJ").values().map { it as JSONObject }
    val milestones: List<JSONArray> = data.arr("MILES").values().map { it as JSONArray }
    private val focusSubjects = subjects.filter { it.has("taskId") }.associateBy { it.getString("taskId") }

    fun exam(state: JSONObject): String = state.obj("settings").optString("exam", "2027-12-25")
    fun phase(date: String, state: JSONObject): JSONObject {
        val phase = calendarPhase(date, state)
        val focus = focusSubject(date, state) ?: return phase
        val task = focusTask(focus)
        val tasks = phase.arr("tasks").values().filterIsInstance<JSONObject>()
        val originalIndex = tasks.indexOfFirst { it.getString("id") in focusSubjects }
        val updated = tasks.filterNot { it.getString("id") in focusSubjects }.toMutableList()
        updated.add(if (originalIndex < 0) updated.size else originalIndex, task)
        return phase.put("tasks", JSONArray(updated)).put("name", "主攻 · ${focus.getString("name")}")
    }

    private fun calendarPhase(date: String, state: JSONObject): JSONObject {
        val exam = exam(state)
        if (date < start) return data.obj("LEGACY_PHASE").copyJson()
        if (date >= exam) return data.obj("PH5").copyJson().put("s", exam).put("e", "2028-08-31")
        data.arr("PHASES").values().map { it as JSONObject }.firstOrNull { date >= it.getString("s") && date <= it.getString("e") }?.let { return it.copyJson() }
        if (date >= "2027-12-01" && date < exam) return data.obj("PH4").copyJson().put("s", "2027-12-01").put("e", addDays(exam, -1))
        return data.arr("PHASES").getJSONObject(0).copyJson()
    }

    internal fun subjectWindow(subject: JSONObject, state: JSONObject): JSONObject {
        val window = subjectSchedule(state).obj("windows").optJSONObject(subject.getString("k")) ?: return subject
        return subject.copyJson().put("s", window.getString("s")).put("e", window.getString("e"))
    }

    internal fun focusSubject(date: String, state: JSONObject): JSONObject? {
        if (date < start || date >= exam(state)) return null
        val key = subjectFocusEntry(state, date) ?: return null
        return subjects.firstOrNull { it.has("taskId") && it.getString("k") == key }
    }

    internal fun selectFocus(state: JSONObject, key: String?, today: String) {
        require(today >= start && today < exam(state)) { "当前日期不在初试学习计划内" }
        if (key != null) {
            val subject = subjects.firstOrNull { it.has("taskId") && it.getString("k") == key } ?: error("科目不存在")
            require(chapterDone(subject, state) < subject.arr("ch").length()) { "这科第一轮已完成" }
            val window = subjectWindow(subject, state)
            val initialStart = LocalDate.parse(window.getString("s"))
            val current = LocalDate.parse(today)
            if (current < initialStart) {
                val initialEnd = LocalDate.parse(window.getString("e"))
                val shiftedEnd = initialEnd.minusDays(ChronoUnit.DAYS.between(current, initialStart))
                setSubjectWindow(state, key, today, shiftedEnd.toString())
            }
        }
        setSubjectFocus(state, key, today)
    }

    private fun focusTask(subject: JSONObject): JSONObject = JSONObject()
        .put("id", subject.getString("taskId")).put("tag", "西综")
        .put("label", "${subject.getString("name")} · 教材/提纲 + 章节真题")

    // Web ch is keyed by subject and zero-based chapter index, not by chapter name.
    fun chapterDone(subject: JSONObject, state: JSONObject): Int {
        val flags = state.obj("ch").arr(subject.getString("k"))
        return (0 until subject.arr("ch").length()).count { flags.optBoolean(it) }
    }

    fun goalHours(date: String, state: JSONObject, today: String): Double =
        if (date < start && (date >= today || state.obj("days").optJSONObject(date) == null)) 0.0
        else phase(date, state).optDouble("gh", 1.5).let { if (it == 0.0) 1.5 else it }

    fun tasks(date: String, state: JSONObject, today: String): List<StudyTask> {
        val record = state.obj("days").optJSONObject(date)
        val beforePlan = date < start && (date >= today || record == null)
        val tasks = if (beforePlan) mutableListOf() else phase(date, state).arr("tasks").values().map { it as JSONObject }
            .filter { !it.has("from") || date >= it.getString("from") }
            .map { StudyTask(it.getString("id"), it.getString("label"), it.getString("tag")) }.toMutableList()
        // A switch during the day keeps already checked subject tasks visible under their own IDs.
        if (!beforePlan && record != null && date >= start && date < exam(state) && subjectFocusEntry(state, date) != null) {
            val shownIds = tasks.map { it.id }.toSet()
            focusSubjects.forEach { (id, subject) ->
                if (id !in shownIds && isDone(record, id)) {
                    val task = focusTask(subject)
                    tasks.add(StudyTask(id, task.getString("label"), task.getString("tag")))
                }
            }
        }
        val recurring = recurringTasksOn(state, date)
        recurring.forEach { name -> tasks.add(StudyTask("r:$name", name, "自定义")) }
        record?.arr("c")?.values()?.filterIsInstance<JSONObject>()
            ?.filter { it.optString("n").isNotEmpty() && it.getString("n") !in recurring }
            ?.forEach { tasks.add(StudyTask("c:${it.getString("n")}", it.getString("n"), "自定义")) }
        return tasks
    }

    internal fun recurringTasksOn(state: JSONObject, date: String): Set<String> {
        val skipped = state.obj("days").optJSONObject(date)?.arr("sk")?.values()?.toSet().orEmpty()
        val starts = state.obj("recurFrom")
        return state.arr("recur").values().filterIsInstance<String>().filterTo(linkedSetOf()) { name ->
            date >= starts.optString(name, "") && name !in skipped
        }
    }

    fun isDone(record: JSONObject?, id: String): Boolean = if (id.startsWith("r:") || id.startsWith("c:")) {
        record?.arr("c")?.values()?.any { it is JSONObject && it.optString("n") == id.substring(2) && it.optBoolean("d") } == true
    } else record?.obj("t")?.optBoolean(id) == true

    fun toggle(record: JSONObject, id: String) {
        if (id.startsWith("r:")) {
            val name = id.substring(2)
            removeSkipped(record, name)
            val custom = record.arr("c")
            val index = (0 until custom.length()).firstOrNull { (custom.opt(it) as? JSONObject)?.optString("n") == name }
            if (index == null) {
                custom.put(JSONObject().put("n", name).put("d", true))
                record.put("c", custom)
            } else {
                val task = custom.getJSONObject(index)
                if (task.optBoolean("d")) {
                    val remaining = JSONArray()
                    for (i in 0 until custom.length()) if (i != index) remaining.put(custom.get(i))
                    record.put("c", remaining)
                } else {
                    task.put("d", true)
                }
            }
        } else if (id.startsWith("c:")) {
            val name = id.substring(2)
            val custom = record.arr("c")
            val task = custom.values().filterIsInstance<JSONObject>().firstOrNull { it.optString("n") == name }
            if (task == null) {
                custom.put(JSONObject().put("n", name).put("d", true))
                record.put("c", custom)
            } else {
                task.put("d", !task.optBoolean("d"))
            }
        } else {
            val t = record.obj("t").put(id, !record.obj("t").optBoolean(id))
            record.put("t", t)
        }
    }

    fun deleteCustomTask(record: JSONObject, id: String) {
        require(id.startsWith("c:") || id.startsWith("r:")) { "只能删除自定义任务" }
        val name = id.substring(2)
        val remaining = JSONArray()
        record.arr("c").values().filterIsInstance<JSONObject>()
            .filter { it.optString("n") != name }
            .forEach { remaining.put(it) }
        record.put("c", remaining)
        if (id.startsWith("r:")) {
            val skipped = record.arr("sk")
            if (name !in skipped.values()) skipped.put(name)
            record.put("sk", skipped)
        }
    }

    private fun removeSkipped(record: JSONObject, name: String) {
        val current = record.optJSONArray("sk") ?: return
        val remaining = JSONArray()
        current.values().filterIsInstance<String>().filter { it != name }.forEach { remaining.put(it) }
        if (remaining.length() == 0) record.remove("sk") else record.put("sk", remaining)
    }

    fun copyPreviousWeekGoals(state: JSONObject, week: String, now: Long = System.currentTimeMillis()): Boolean {
        val previous = state.obj("weeks").optJSONObject(addDays(week, -7)) ?: return false
        val target = state.obj("weeks").optJSONObject(week) ?: week()
        target.put("must", JSONArray(previous.arr("must").toString()))
            .put("md", JSONArray().put(false).put(false).put(false))
            .put("u", now)
        state.obj("weeks").put(week, target)
        return true
    }

    fun anyDone(record: JSONObject?): Boolean = record != null &&
        (record.obj("t").namesList().any { record.obj("t").optBoolean(it) } || record.arr("c").values().any { (it as JSONObject).optBoolean("d") })

    fun minCount(date: String, state: JSONObject): Int {
        val record = state.obj("days").optJSONObject(date)
        val currentPhase = phase(date, state)
        if (currentPhase.optInt("fs") == 1) return if (anyDone(record)) 1 else 0
        return currentPhase.arr("tasks").values().filterIsInstance<JSONObject>()
            .filter { (!it.has("from") || date >= it.getString("from")) && it.optInt("min") == 1 }
            .count { record?.obj("t")?.optBoolean(it.getString("id")) == true }
    }

    fun makeupLeft(month: String, state: JSONObject): Int = (2 - state.arr("mk").values().count { (it as String).startsWith(month) }).coerceAtLeast(0)
    fun isMakeup(date: String, state: JSONObject): Boolean = state.arr("mk").values().contains(date)
    fun satisfied(date: String, state: JSONObject): Boolean {
        val currentPhase = phase(date, state)
        if (currentPhase.optInt("fs") == 1) return anyDone(state.obj("days").optJSONObject(date))
        val required = currentPhase.arr("tasks").values().filterIsInstance<JSONObject>()
            .count { (!it.has("from") || date >= it.getString("from")) && it.optInt("min") == 1 }
            .coerceAtLeast(1)
        return minCount(date, state) >= required || isMakeup(date, state)
    }

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
        fun nextWithinLimit(date: String, limit: String, days: Long): String? =
            addDays(date, days).takeIf { it <= limit }
        fun day(): JSONObject = JSONObject("""{"t":{},"c":[],"h":0,"sh":{},"rw":{"g":"","s":"","n":""},"u":0}""")
        fun week(): JSONObject = JSONObject("""{"must":["","",""],"md":[false,false,false],"ex":[],"review":"","u":0}""")

        fun addRecurringTask(state: JSONObject, name: String, fromDate: String = CheckinCore.today()) {
            require(name.length <= 200) { "任务名请控制在 200 字以内" }
            val recur = state.arr("recur")
            require(name !in recur.values()) { "任务已存在" }
            recur.put(name)
            state.put("recur", recur)
            state.put("recurFrom", state.obj("recurFrom").put(name, fromDate))
        }

        fun retireRecurringTask(state: JSONObject, name: String) {
            if (name !in state.arr("recur").values()) return
            val start = state.obj("recurFrom").optString(name, "")
            state.put("recur", JSONArray(state.arr("recur").values().filter { it != name }))
            state.optJSONObject("recurFrom")?.let { from ->
                from.remove(name)
                if (from.length() == 0) state.remove("recurFrom")
            }
            val days = state.optJSONObject("days") ?: return
            days.namesList().forEach { date ->
                if (date < start) return@forEach
                val day = days.optJSONObject(date) ?: return@forEach
                if (name in day.arr("sk").values()) return@forEach
                val custom = day.optJSONArray("c") ?: return@forEach
                val remaining = JSONArray()
                var changed = false
                custom.values().filterIsInstance<JSONObject>().forEach { item ->
                    if (item.optString("n") == name && !item.optBoolean("d")) changed = true
                    else remaining.put(item)
                }
                if (changed) day.put("c", remaining)
            }
        }
    }
}
