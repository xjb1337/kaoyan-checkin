package com.xjb.kaoyancheckin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor

fun JSONObject.namesList(): List<String> = keys().asSequence().toList()
fun JSONObject.obj(key: String): JSONObject = optJSONObject(key) ?: JSONObject()
fun JSONObject.arr(key: String): JSONArray = optJSONArray(key) ?: JSONArray()
fun JSONArray.values(): List<Any> = (0 until length()).map { get(it) }
fun JSONObject.copyJson(): JSONObject = JSONObject(toString())

object CheckinCore {
    const val MAX_TEXT = 5 * 1024 * 1024
    const val DEFAULT_WORD_BASE = 1300
    const val DEFAULT_WORD_GOAL = 5500
    val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    // Tests can fix the date; the application normally uses Shanghai system time.
    internal var clock: Clock = Clock.system(shanghai)
    private val dangerous = setOf("__proto__", "constructor", "prototype")

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
    private fun text(value: Any?, max: Int = MAX_TEXT) = value is String && value.length <= max
    private fun number(value: Any?) = value is Number && value.toDouble().isFinite() && value.toDouble() >= 0
    private fun integerTextIn(value: String, min: Int, max: Int): Boolean {
        val decimal = value.toBigDecimalOrNull() ?: return false
        return decimal >= min.toBigDecimal() && decimal <= max.toBigDecimal() && decimal.stripTrailingZeros().scale() <= 0
    }
    internal fun integerIn(value: Any?, min: Int, max: Int): Boolean {
        if (value !is Number) return false
        return integerTextIn(value.toString(), min, max)
    }
    private fun JSONObject.hasValue(key: String): Boolean = has(key) && !isNull(key)
    private fun nullish(value: Any?, default: Any): Any = if (value == null || value === JSONObject.NULL) default else value
    private fun truthy(value: Any?): Boolean = when (value) {
        null, JSONObject.NULL, false, "" -> false
        is Number -> value.toDouble() != 0.0 && !value.toDouble().isNaN()
        else -> true
    }
    private fun fallback(value: Any?, default: Any): Any = if (truthy(value)) value!! else default
    private fun round(value: Double) = floor(value * 100 + 0.5) / 100
    private fun sameJson(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> a.namesList().size == b.namesList().size &&
            a.namesList().all { b.has(it) && sameJson(a.opt(it), b.opt(it)) }
        a is JSONArray && b is JSONArray -> a.length() == b.length() &&
            (0 until a.length()).all { sameJson(a.opt(it), b.opt(it)) }
        a == null || a === JSONObject.NULL -> b == null || b === JSONObject.NULL
        b == null || b === JSONObject.NULL -> false
        else -> a == b
    }

    fun today(): String = LocalDate.now(clock).toString()
    fun todayRefreshDelayMillis(): Long = millisUntilNextShanghaiMidnight().coerceIn(1_000L, 60_000L)

    fun millisUntilNextShanghaiMidnight(): Long {
        val now = Instant.now(clock)
        val next = now.atZone(shanghai).toLocalDate().plusDays(1).atStartOfDay(shanghai).toInstant()
        return (next.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(1L)
    }
    fun validDate(value: Any?): Boolean {
        if (value !is String || !Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) return false
        return runCatching { LocalDate.parse(value).toString() == value }.getOrDefault(false)
    }

    private fun safeKeys(value: Any?, depth: Int = 0) {
        if (depth > 15) fail("数据嵌套过深")
        when (value) {
            is JSONObject -> value.namesList().forEach {
                if (it in dangerous) fail("数据包含不安全字段")
                safeKeys(value.get(it), depth + 1)
            }
            is JSONArray -> value.values().forEach { safeKeys(it, depth + 1) }
        }
    }

    private fun task(value: Any?) = value is JSONObject && text(value.opt("n")) && value.opt("d") is Boolean
    private fun validDay(value: Any?): Boolean {
        if (value !is JSONObject) return false
        val tasks = fallback(value.opt("t"), JSONObject())
        val custom = fallback(value.opt("c"), JSONArray())
        val skipped = fallback(value.opt("sk"), JSONArray())
        val hours = fallback(value.opt("sh"), JSONObject())
        val review = fallback(value.opt("rw"), JSONObject())
        val words = nullish(value.opt("w"), JSONObject())
        return tasks is JSONObject && tasks.namesList().all { tasks.get(it) is Boolean } &&
            custom is JSONArray && custom.values().all(::task) &&
            skipped is JSONArray && skipped.values().all { text(it) } &&
            number(nullish(value.opt("h"), 0)) && number(nullish(value.opt("u"), 0)) &&
            hours is JSONObject && hours.namesList().all { text(it) && number(hours.get(it)) } &&
            review is JSONObject && listOf("g", "s", "n").all { text(nullish(review.opt(it), "")) } &&
            words is JSONObject && listOf("n", "r").all { !words.has(it) || integerIn(words.opt(it), 0, 2000) } &&
            text(nullish(value.opt("note"), ""))
    }

    private fun validWeek(value: Any?): Boolean = value is JSONObject &&
        value.opt("must") is JSONArray && value.arr("must").length() == 3 && value.arr("must").values().all { text(it) } &&
        value.opt("md") is JSONArray && value.arr("md").length() == 3 && value.arr("md").values().all { it is Boolean } &&
        value.opt("ex") is JSONArray && value.arr("ex").values().all(::task) &&
        text(nullish(value.opt("review"), "")) && number(nullish(value.opt("u"), 0))

    private fun validScore(value: Any?): Boolean {
        if (value !is JSONObject) return false
        val parts = fallback(value.opt("p"), JSONObject())
        return text(value.opt("id")) && validDate(value.opt("d")) && text(nullish(value.opt("n"), "")) &&
            number(value.opt("tot")) && value.optDouble("tot") <= 300 && parts is JSONObject &&
            parts.namesList().all { number(parts.get(it)) }
    }

    private fun recordMap(value: Any?, validator: (Any?) -> Boolean, label: String) {
        if (value !is JSONObject) fail(label + "格式错误")
        value.namesList().forEach { if (!validDate(it) || !validator(value.get(it))) fail(label + "含无效记录：" + it) }
    }

    fun parseBackup(raw: String): JSONObject {
        if (raw.length > MAX_TEXT) fail("备份超过 5 MB")
        return parseBackupDocument(raw).apply { remove("_undo") }
    }

    fun parseBackupDocument(raw: String): JSONObject {
        val parsed = Json.parseToJsonElement(raw)
        if (parsed !is JsonObject) return normalizeState(null)
        // Android's JSONObject can round a fractional literal before validation. Check the
        // original JSON number text first so a non-integer cannot be silently rounded away.
        fun validInteger(value: kotlinx.serialization.json.JsonElement, min: Int, max: Int): Boolean =
            value is JsonPrimitive && !value.isString && integerTextIn(value.content, min, max)
        (parsed["settings"] as? JsonObject)?.let { settings ->
            settings["wordBase"]?.let { if (!validInteger(it, 0, 20000)) fail("单词基数须为 0–20000 的整数") }
            settings["wordGoal"]?.let { if (!validInteger(it, 1, 50000)) fail("单词目标须为 1–50000 的整数") }
        }
        (parsed["days"] as? JsonObject)?.forEach { (date, value) ->
            val words = ((value as? JsonObject)?.get("w") as? JsonObject) ?: return@forEach
            listOf("n", "r").forEach { key ->
                words[key]?.let { if (!validInteger(it, 0, 2000)) fail("每日数据含无效记录：$date") }
            }
        }
        return normalizeState(JSONObject(parsed.toString()))
    }

    fun emptyState(): JSONObject = normalizeState(JSONObject().put("days", JSONObject()))

    fun normalizeState(raw: Any?): JSONObject {
        if (raw !is JSONObject || raw.opt("days") !is JSONObject) fail("不是有效的打卡备份（缺少 days）")
        safeKeys(raw)
        if (raw.hasValue("schemaVersion") && (raw.opt("schemaVersion") !is Number || raw.optDouble("schemaVersion") !in listOf(1.0, 2.0))) {
            fail("备份版本较新，请先更新应用")
        }
        val weeks = fallback(raw.opt("weeks"), JSONObject())
        recordMap(raw.get("days"), ::validDay, "每日数据")
        recordMap(weeks, ::validWeek, "周目标")
        val recur = nullish(raw.opt("recur"), JSONArray())
        val recurFrom = nullish(raw.opt("recurFrom"), JSONObject())
        val scores = nullish(raw.opt("scores"), JSONArray())
        val chapters = nullish(raw.opt("ch"), JSONObject())
        val firstChapters = nullish(raw.opt("cf"), JSONObject())
        val completedChapters = nullish(raw.opt("ce"), JSONObject())
        val chapterDates = nullish(raw.opt("cd"), JSONObject())
        val makeup = nullish(raw.opt("mk"), JSONArray())
        val settings = nullish(raw.opt("settings"), JSONObject())
        if (recur !is JSONArray || !recur.values().all { text(it) }) fail("固定任务格式错误")
        if (recurFrom !is JSONObject || !recurFrom.namesList().all { text(it) && validDate(recurFrom.opt(it)) }) fail("固定任务格式错误")
        if (scores !is JSONArray || !scores.values().all(::validScore)) fail("模考成绩格式错误")
        if (chapters !is JSONObject || !chapters.namesList().all { key ->
                val chapter = chapters.opt(key)
                chapter is JSONArray && chapter.values().all { it is Boolean || it === JSONObject.NULL }
            }) fail("章节格式错误")
        if (listOf(firstChapters, completedChapters).any { dates ->
                dates !is JSONObject || !dates.namesList().all { validDate(dates.opt(it)) }
            }) fail("章节日期格式错误")
        if (chapterDates !is JSONObject || !chapterDates.namesList().all { key ->
                val dates = chapterDates.opt(key)
                dates is JSONObject && dates.namesList().all { index ->
                    Regex("[0-9]+").matches(index) && validDate(dates.opt(index))
                }
            }) fail("章节日期格式错误")
        if (makeup !is JSONArray || !makeup.values().all(::validDate)) fail("补签日期格式错误")
        val examDate = if (settings is JSONObject) fallback(settings.opt("exam"), "2027-12-25") else null
        if (settings !is JSONObject || !validDate(examDate) || (examDate as String) < "2026-11-02" || examDate > "2028-08-31") {
            fail("初试日期格式错误")
        }
        if (settings.hasValue("backupDays") && (settings.opt("backupDays") !is Number || settings.optDouble("backupDays") !in listOf(7.0, 14.0, 30.0))) {
            fail("备份周期格式错误")
        }
        if (settings.has("wordBase") && !integerIn(settings.opt("wordBase"), 0, 20000)) fail("单词基数须为 0–20000 的整数")
        if (settings.has("wordGoal") && !integerIn(settings.opt("wordGoal"), 1, 50000)) fail("单词目标须为 1–50000 的整数")
        if (raw.hasValue("_recovery") && !text(raw.opt("_recovery"))) fail("恢复副本格式错误")
        if (!number(nullish(raw.opt("upd"), 0)) || !number(nullish(raw.opt("bk"), 0))) fail("备份时间格式错误")
        val days = JSONObject()
        raw.obj("days").namesList().forEach { date ->
            val source = raw.obj("days").getJSONObject(date)
            val review = source.obj("rw")
            val record = JSONObject().put("t", source.obj("t").copyJson()).put("c", JSONArray(source.arr("c").toString()))
                .put("h", fallback(source.opt("h"), 0)).put("sh", source.obj("sh").copyJson())
                .put("rw", JSONObject().put("g", nullish(review.opt("g"), nullish(source.opt("note"), "")))
                    .put("s", nullish(review.opt("s"), "")).put("n", nullish(review.opt("n"), "")))
                .put("u", fallback(source.opt("u"), 0))
            if (source.hasValue("w")) record.put("w", JSONObject().put("n", source.obj("w").optInt("n", 0)).put("r", source.obj("w").optInt("r", 0)))
            if (source.hasValue("sk")) {
                val skipped = source.arr("sk").values().filterIsInstance<String>().filter { it.isNotEmpty() }.distinct()
                if (skipped.isNotEmpty()) record.put("sk", JSONArray(skipped))
            }
            days.put(date, normalizeHours(record))
        }
        val normalizedChapterDates = JSONObject()
        chapterDates.namesList().forEach { key ->
            val flags = chapters.optJSONArray(key)
            val dates = chapterDates.getJSONObject(key)
            val kept = JSONObject()
            dates.namesList().forEach { rawIndex ->
                val index = rawIndex.toIntOrNull()
                if (index != null && flags != null && index in 0 until flags.length() && flags.optBoolean(index)) {
                    kept.put(rawIndex, dates.getString(rawIndex))
                }
            }
            if (kept.length() > 0) normalizedChapterDates.put(key, kept)
        }
        val recurNames = recur.values().filterIsInstance<String>().toSet()
        val normalizedRecurFrom = JSONObject()
        recurFrom.namesList().forEach { name ->
            if (name in recurNames) normalizedRecurFrom.put(name, recurFrom.getString(name))
        }
        val result = JSONObject().put("schemaVersion", 2).put("days", days).put("weeks", JSONObject(weeks.toString()))
            .put("recur", JSONArray(recur.toString())).put("scores", JSONArray(scores.toString()))
            .put("ch", chapters.copyJson()).put("cf", (firstChapters as JSONObject).copyJson())
            .put("ce", (completedChapters as JSONObject).copyJson()).put("cd", normalizedChapterDates)
            .put("mk", JSONArray(makeup.toString()))
            .put("settings", JSONObject().put("exam", fallback(settings.opt("exam"), "2027-12-25"))
                .put("backupDays", fallback(settings.opt("backupDays"), 7))
                .put("wordBase", settings.optInt("wordBase", DEFAULT_WORD_BASE))
                .put("wordGoal", settings.optInt("wordGoal", DEFAULT_WORD_GOAL)))
            .put("upd", fallback(raw.opt("upd"), 0)).put("bk", fallback(raw.opt("bk"), 0))
            .put("tip", if (truthy(raw.opt("tip"))) 1 else 0)
        if (normalizedRecurFrom.length() > 0) result.put("recurFrom", normalizedRecurFrom)
        if (truthy(raw.opt("_recovery"))) result.put("_recovery", raw.get("_recovery"))
        // Short-lived local undo metadata is never part of an exported backup.
        raw.optJSONObject("_undo")?.let { if (UndoStep.fromJson(it) != null) result.put("_undo", it.copyJson()) }
        return result
    }

    fun normalizeHours(record: JSONObject): JSONObject {
        val hours = record.obj("sh").copyJson()
        val sum = round(hours.namesList().sumOf { hours.getDouble(it) })
        if (record.optDouble("h", 0.0) > sum) hours.put("其他", round(hours.optDouble("其他", 0.0) + record.getDouble("h") - sum))
        return record.copyJson().put("sh", hours).put("h", round(hours.namesList().sumOf { hours.getDouble(it) }))
    }

    fun setHours(record: JSONObject, subject: Any?, value: Double): JSONObject {
        if (!text(subject, 200) || subject == "" || subject in dangerous) fail("科目名称无效")
        if (!number(value) || value > 24) fail("科目时长须在 0–24 小时之间")
        val normalized = normalizeHours(record)
        val hours = normalized.obj("sh").put(subject as String, round(value))
        val total = round(hours.namesList().sumOf { hours.getDouble(it) })
        if (total > 24) fail("一天的学习时长不能超过 24 小时")
        return normalized.put("sh", hours).put("h", total)
    }

    fun hasData(state: JSONObject): Boolean {
        val hasDayData = state.obj("days").namesList().any { date ->
            val record = state.obj("days").obj(date)
            truthy(record.opt("h")) || record.obj("t").namesList().any { truthy(record.obj("t").get(it)) } ||
                record.obj("w").optInt("n", 0) > 0 || record.obj("w").optInt("r", 0) > 0 ||
                record.arr("c").length() > 0 || record.obj("rw").namesList().any { truthy(record.obj("rw").get(it)) }
        }
        if (hasDayData) return true
        val hasWeekData = state.obj("weeks").namesList().any { week ->
            val record = state.obj("weeks").obj(week)
            record.arr("must").values().any { truthy(it) } ||
                record.arr("md").values().any { truthy(it) } ||
                record.arr("ex").length() > 0 ||
                truthy(record.opt("review"))
        }
        if (hasWeekData) return true
        if (state.arr("scores").length() > 0 || state.arr("recur").length() > 0 || state.arr("mk").length() > 0) return true
        val hasChapterData = state.obj("ch").namesList().any { key ->
            val flags = state.obj("ch").arr(key)
            (0 until flags.length()).any { flags.optBoolean(it) }
        } || state.obj("cf").length() > 0 || state.obj("ce").length() > 0 || state.obj("cd").length() > 0
        if (hasChapterData) return true
        val settings = state.optJSONObject("settings") ?: return false
        return (settings.hasValue("exam") && settings.optString("exam", "2027-12-25") != "2027-12-25") ||
            (settings.hasValue("backupDays") && settings.optInt("backupDays", 7) != 7) ||
            (settings.hasValue("wordBase") && settings.optInt("wordBase", DEFAULT_WORD_BASE) != DEFAULT_WORD_BASE) ||
            (settings.hasValue("wordGoal") && settings.optInt("wordGoal", DEFAULT_WORD_GOAL) != DEFAULT_WORD_GOAL)
    }

    fun needsBackup(state: JSONObject, now: Long): Boolean = hasData(state) &&
        (!truthy(state.opt("bk")) || (now - state.optDouble("bk", 0.0)) / 86_400_000 >= state.obj("settings").optInt("backupDays", 7))

    fun rollDate(selected: String, previous: String, current: String): String = if (selected == previous) current else selected
    fun backupJSON(state: JSONObject): String = state.copyJson().apply {
        remove("_recovery")
        remove("_undo")
        if (optJSONObject("cd") == null) put("cd", JSONObject())
    }.toString()

    private data class IncomingPresence(
        val hasCf: Boolean,
        val hasCe: Boolean,
        val hasCd: Boolean,
        val hasWordBase: Boolean,
        val hasWordGoal: Boolean,
        val daysWithWords: Set<String>,
    )

    private fun detectPresence(raw: String): IncomingPresence? {
        val parsed = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
        val settings = parsed["settings"] as? JsonObject
        val days = (parsed["days"] as? JsonObject)?.entries
            ?.filter { (_, value) -> (value as? JsonObject)?.get("w").let { it != null && it !is JsonNull } }
            ?.map { it.key }
            ?.toSet()
            ?: emptySet()
        return IncomingPresence(
            hasCf = parsed["cf"].let { it != null && it !is JsonNull },
            hasCe = parsed["ce"].let { it != null && it !is JsonNull },
            hasCd = parsed["cd"].let { it != null && it !is JsonNull },
            hasWordBase = settings?.get("wordBase").let { it != null && it !is JsonNull },
            hasWordGoal = settings?.get("wordGoal").let { it != null && it !is JsonNull },
            daysWithWords = days,
        )
    }

    fun mergeState(current: JSONObject, incoming: JSONObject): JSONObject =
        mergeStateTracked(current, incoming, null).first

    private fun mergeStateTracked(
        current: JSONObject,
        incoming: JSONObject,
        presence: IncomingPresence?,
    ): Pair<JSONObject, Boolean> {
        val out = current.copyJson()
        var changed = false
        val incomingNewer = incoming.optDouble("upd", 0.0) > current.optDouble("upd", 0.0)

        incoming.obj("days").namesList().forEach { date ->
            val next = incoming.obj("days").obj(date)
            val old = out.obj("days").optJSONObject(date)
            if (old == null || old.optDouble("u", 0.0) < next.optDouble("u", 0.0)) {
                val mergedDay = next.copyJson()
                if (presence != null && date !in presence.daysWithWords && old != null && old.hasValue("w")) {
                    mergedDay.put("w", old.obj("w").copyJson())
                }
                out.obj("days").put(date, mergedDay)
                changed = true
            }
        }
        incoming.obj("weeks").namesList().forEach { week ->
            val next = incoming.obj("weeks").obj(week)
            val old = out.obj("weeks").optJSONObject(week)
            if (old == null || old.optDouble("u", 0.0) < next.optDouble("u", 0.0)) {
                out.obj("weeks").put(week, next.copyJson())
                changed = true
            }
        }

        // Merge scores by id.
        val scoreList = mutableListOf<JSONObject>()
        val scoreIndexById = mutableMapOf<String, Int>()
        current.arr("scores").values().filterIsInstance<JSONObject>().forEach { item ->
            val id = item.optString("id")
            scoreIndexById[id] = scoreList.size
            scoreList.add(item.copyJson())
        }
        incoming.arr("scores").values().filterIsInstance<JSONObject>().forEach { item ->
            val id = item.optString("id")
            val existingIdx = scoreIndexById[id]
            if (existingIdx == null) {
                scoreIndexById[id] = scoreList.size
                scoreList.add(item.copyJson())
                changed = true
            } else if (incomingNewer && !sameJson(scoreList[existingIdx], item)) {
                scoreList[existingIdx] = item.copyJson()
                changed = true
            }
        }
        if (changed) {
            out.put("scores", JSONArray().apply { scoreList.forEach { put(it) } })
        }

        // Merge mk by union.
        val mergedMk = LinkedHashSet<String>()
        current.arr("mk").values().filterIsInstance<String>().forEach { mergedMk.add(it) }
        incoming.arr("mk").values().filterIsInstance<String>().forEach { mergedMk.add(it) }
        if (mergedMk.toList() != current.arr("mk").values()) {
            out.put("mk", JSONArray(mergedMk.toList()))
            changed = true
        }

        // Merge ch, cf, ce, cd per subject.
        val chapterSubjects = linkedSetOf<String>()
        listOf("ch", "cf", "ce", "cd").forEach { field ->
            current.optJSONObject(field)?.namesList()?.forEach { chapterSubjects.add(it) }
            incoming.optJSONObject(field)?.namesList()?.forEach { chapterSubjects.add(it) }
        }
        val outCh = out.optJSONObject("ch") ?: JSONObject().also { out.put("ch", it) }
        val outCf = out.optJSONObject("cf") ?: JSONObject().also { out.put("cf", it) }
        val outCe = out.optJSONObject("ce") ?: JSONObject().also { out.put("ce", it) }
        val outCd = out.optJSONObject("cd") ?: JSONObject().also { out.put("cd", it) }
        chapterSubjects.forEach { subject ->
            val inCurrent = listOf("ch", "cf", "ce", "cd").any { current.optJSONObject(it)?.hasValue(subject) == true }
            val inIncoming = listOf("ch", "cf", "ce", "cd").any { incoming.optJSONObject(it)?.hasValue(subject) == true }
            if (!inCurrent && inIncoming) {
                incoming.optJSONObject("ch")?.optJSONArray(subject)?.let { outCh.put(subject, JSONArray(it.toString())); changed = true }
                if (presence == null || presence.hasCf) incoming.optJSONObject("cf")?.opt(subject)?.takeIf { it !== JSONObject.NULL }?.let { outCf.put(subject, it); changed = true }
                if (presence == null || presence.hasCe) incoming.optJSONObject("ce")?.opt(subject)?.takeIf { it !== JSONObject.NULL }?.let { outCe.put(subject, it); changed = true }
                if (presence == null || presence.hasCd) incoming.optJSONObject("cd")?.optJSONObject(subject)?.let { outCd.put(subject, it.copyJson()); changed = true }
            } else if (inCurrent && inIncoming && incomingNewer) {
                val incFlags = incoming.optJSONObject("ch")?.optJSONArray(subject)
                val curFlags = current.optJSONObject("ch")?.optJSONArray(subject)
                if (!sameJson(curFlags, incFlags)) {
                    if (incFlags != null) outCh.put(subject, JSONArray(incFlags.toString())) else outCh.remove(subject)
                    changed = true
                }
                if (presence == null || presence.hasCd) {
                    val incDates = incoming.optJSONObject("cd")?.optJSONObject(subject)
                    val curDates = current.optJSONObject("cd")?.optJSONObject(subject)
                    if (!sameJson(curDates, incDates)) {
                        if (incDates != null) outCd.put(subject, incDates.copyJson()) else outCd.remove(subject)
                        changed = true
                    }
                }
                if (presence == null || presence.hasCf) {
                    val incFirst = incoming.optJSONObject("cf")?.opt(subject)?.takeIf { it !== JSONObject.NULL }
                    val curFirst = current.optJSONObject("cf")?.opt(subject)?.takeIf { it !== JSONObject.NULL }
                    if (!sameJson(curFirst, incFirst)) {
                        if (incFirst != null) outCf.put(subject, incFirst) else outCf.remove(subject)
                        changed = true
                    }
                }
                if (presence == null || presence.hasCe) {
                    val incEnd = incoming.optJSONObject("ce")?.opt(subject)?.takeIf { it !== JSONObject.NULL }
                    val curEnd = current.optJSONObject("ce")?.opt(subject)?.takeIf { it !== JSONObject.NULL }
                    if (!sameJson(curEnd, incEnd)) {
                        if (incEnd != null) outCe.put(subject, incEnd) else outCe.remove(subject)
                        changed = true
                    }
                }
            }
        }
        if (!out.has("cd")) out.put("cd", JSONObject())

        if (incomingNewer) {
            val incRecur = incoming.opt("recur")
            val curRecur = current.opt("recur")
            if (incRecur?.toString() != curRecur?.toString()) {
                out.put("recur", JSONArray(incRecur?.toString() ?: "[]"))
                changed = true
            }
            val incRecurFrom = incoming.optJSONObject("recurFrom")
            val curRecurFrom = current.optJSONObject("recurFrom")
            if (incRecurFrom != null && !sameJson(curRecurFrom, incRecurFrom)) {
                out.put("recurFrom", incRecurFrom.copyJson())
                changed = true
            }
            val settings = current.obj("settings").copyJson()
            incoming.obj("settings").namesList().forEach { key ->
                val skipWebDefault = (key == "wordBase" && presence != null && !presence.hasWordBase) ||
                    (key == "wordGoal" && presence != null && !presence.hasWordGoal)
                if (!skipWebDefault) settings.put(key, incoming.obj("settings").get(key))
            }
            if (settings.toString() != current.obj("settings").toString()) { out.put("settings", settings); changed = true }
            if (truthy(incoming.opt("tip")) && !truthy(current.opt("tip"))) { out.put("tip", 1); changed = true }
            if (incoming.optDouble("bk", 0.0) > current.optDouble("bk", 0.0)) { out.put("bk", incoming.get("bk")); changed = true }
        }
        out.put("upd", maxOf(current.optDouble("upd", 0.0), incoming.optDouble("upd", 0.0)))
        return (if (changed) out else current.copyJson()) to changed
    }

    fun importBackup(current: JSONObject, raw: String, mode: String = "merge", now: Long = System.currentTimeMillis(), recoveryRaw: String? = null): JSONObject {
        require(mode in listOf("merge", "replace"))
        val incoming = parseBackup(raw)
        if (mode == "replace" || recoveryRaw != null) {
            incoming.remove("_undo")
            return normalizeState(incoming.put("upd", now).put("_recovery", recoveryRaw ?: backupJSON(current)))
        }
        val presence = detectPresence(raw)
        val (merged, changed) = mergeStateTracked(current, incoming, presence)
        if (!changed) {
            return normalizeState(current.copyJson().apply { remove("_undo") })
        }
        merged.remove("_undo")
        return normalizeState(merged.put("upd", now).put("_recovery", backupJSON(current)))
    }
}
