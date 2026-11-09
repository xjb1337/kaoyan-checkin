package com.xjb.kaoyancheckin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjb.kaoyancheckin.ui.theme.webColors
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

// Web vProg: plan-vs-actual bars, expandable .mi chapter list, and score card.
@Composable
internal fun ProgressScreen(model: CheckinViewModel) {
    val c = webColors
    val enabled = model.loadError.isEmpty()
    Section("计划 vs 实际（按计划线性推进）") {
        var anyChapter = false
        model.plan.subjects.forEach { subject ->
            val start = subject.getString("s")
            val end = subject.getString("e")
            val total = subject.arr("ch").length()
            val flags = model.state.obj("ch").arr(subject.getString("k"))
            val done = (0 until total).count { flags.optBoolean(it) }
            anyChapter = anyChapter || done > 0
            val expected = when {
                model.today < start -> 0
                model.today >= end -> total
                else -> (daysBetween(start, model.today).toDouble() / daysBetween(start, end).coerceAtLeast(1) * total).roundToInt()
            }
            val diff = done - expected
            val (status, color, bg) = when {
                model.today < start -> Triple("未开始", c.amber, c.chipOrange)
                diff > 0 -> Triple("领先 $diff", c.green, Color(0xFF16A34A).copy(alpha = .14f))
                diff < 0 -> Triple("落后 ${-diff}", c.red, Color(0xFFEF4444).copy(alpha = .13f))
                else -> Triple("正常", c.green, Color(0xFF16A34A).copy(alpha = .14f))
            }
            val barColor = when { model.today < start -> c.orange; diff < 0 -> Color(0xFFEF4444); else -> Color(0xFF16A34A) }
            BarRow(subject.getString("name"), "$done/$total", if (total == 0) 0f else done.toFloat() / total, barColor)
            Row(Modifier.padding(start = 52.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("计划应完成 $expected 章 ·", fontSize = 12.sp, color = c.sub)
                Chip(status, color, bg)
            }
        }
        if (!anyChapter) Tip("还没有勾选章节。下面展开每科，学完一章点一下。")
    }
    Section("章节进度") {
        model.plan.subjects.forEach { subject ->
            val subjectKey = subject.getString("k")
            val chapters = subject.arr("ch")
            val flags = model.state.obj("ch").arr(subjectKey)
            val done = (0 until chapters.length()).count { flags.optBoolean(it) }
            var expanded by rememberSaveable(subjectKey) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable { expanded = !expanded }.padding(horizontal = 2.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(subject.getString("name"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.text)
                Text("$done/${chapters.length()} 章", fontSize = 11.5.sp, color = c.sub, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Chip("${subject.getString("s").substring(5)}~${subject.getString("e").substring(5)}", c.accent, c.chipBlue)
            }
            if (expanded) chapters.values().forEachIndexed { index, chapter ->
                val checked = flags.optBoolean(index)
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(enabled = enabled) {
                    model.edit { state ->
                        val updated = state.obj("ch").arr(subjectKey)
                        while (updated.length() <= index) updated.put(false)
                        updated.put(index, !updated.optBoolean(index))
                        state.obj("ch").put(subjectKey, updated)
                    }
                }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("${index + 1}", Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(if (checked) c.blue else c.line).wrapContentSize(),
                        textAlign = TextAlign.Center, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (checked) Color.White else c.sub)
                    Text(chapter as String, Modifier.weight(1f), fontSize = 13.5.sp, color = if (checked) c.sub else c.text)
                    if (checked) Chip("已学", c.accent, c.chipBlue)
                }
                Divider()
            }
            Divider()
        }
    }
    Section("模考 / 真题套卷成绩") {
        val scores = model.state.arr("scores").values().map { it as JSONObject }.sortedBy { it.getString("d") }
        if (scores.isEmpty()) Tip("还没有记录。做完一套真题就记一次，这里会画出趋势线，并对照 200 分个人参考线。")
        else {
            ScoreChart(scores)
            val last = scores.last()
            Tip("最近一次：${last.optString("n").ifEmpty { "套卷" }} ${last.getString("d")} · ${last.getDouble("tot")} 分")
            scores.reversed().forEach { score ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${score.getString("d")} ${score.optString("n")}", Modifier.weight(1f), fontSize = 13.sp, color = c.text)
                    Text("${score.getDouble("tot")}", color = if (score.getDouble("tot") >= 200) c.green else c.red, fontWeight = FontWeight.Bold)
                    WebButton("删除", { model.edit { state -> state.put("scores", JSONArray(state.arr("scores").values().filter { (it as JSONObject).getString("id") != score.getString("id") })) } }, enabled = enabled)
                }
                if (score.obj("p").length() > 0) Tip(score.obj("p").namesList().joinToString(" / ") { "$it ${score.obj("p").get(it)}" })
                Divider()
            }
        }
        var date by rememberSaveable { mutableStateOf(model.today) }
        var name by rememberSaveable { mutableStateOf("") }
        var total by rememberSaveable { mutableStateOf("") }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("日期", color = c.sub, fontSize = 11.5.sp)
                WebInput(date, { date = it }, "考试日期 YYYY-MM-DD", Modifier.fillMaxWidth(), enabled)
            }
            Column(Modifier.weight(1f)) {
                Text("卷子", color = c.sub, fontSize = 11.5.sp)
                WebInput(name, { name = it }, "如 2015年真题", Modifier.fillMaxWidth(), enabled)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("总分（300 分制；参考线不等于录取线）", color = c.sub, fontSize = 11.5.sp)
        WebInput(total, { total = it }, "如 215", Modifier.fillMaxWidth(), enabled, numeric = true)
        Spacer(Modifier.height(10.dp))
        WebButton("保存这次成绩", {
            val score = total.toDoubleOrNull()
            if (!CheckinCore.validDate(date) || date > model.today) model.notify("请填写今天或过去的有效考试日期")
            else if (name.length > 200) model.notify("卷子名称请控制在 200 字以内")
            else if (score == null || !score.isFinite() || score !in 0.0..300.0) model.notify("请填 0-300 的总分")
            else { model.edit { it.arr("scores").put(JSONObject().put("id", UUID.randomUUID().toString()).put("d", date).put("n", name).put("tot", score).put("p", JSONObject())) }; total = "" }
        }, Modifier.fillMaxWidth(), enabled, big = true, primary = true)
    }
}

@Composable
private fun ScoreChart(scores: List<JSONObject>) {
    val c = webColors
    Canvas(Modifier.fillMaxWidth().height(110.dp).padding(vertical = 8.dp)) {
        val points = scores.mapIndexed { i, score ->
            val x = if (scores.size == 1) size.width / 2 else size.width * i / (scores.size - 1)
            androidx.compose.ui.geometry.Offset(x, size.height * (1f - ((score.optDouble("tot") - 100) / 200).toFloat().coerceIn(0f, 1f)))
        }
        drawLine(c.amber, androidx.compose.ui.geometry.Offset(0f, size.height / 2), androidx.compose.ui.geometry.Offset(size.width, size.height / 2), 1.dp.toPx())
        points.zipWithNext().forEach { (a, b) -> drawLine(c.blue, a, b, 2.dp.toPx()) }
        points.forEach { drawCircle(c.blue, 3.dp.toPx(), it) }
    }
}

// Web vStats: .stat two-column grid, weekly rate, .barrow, .wbar and .heat.
@Composable
internal fun StatsScreen(model: CheckinViewModel) {
    val c = webColors
    val state = model.state
    val dates = state.obj("days").namesList().filter { it <= model.today }
    val totalHours = dates.sumOf { state.obj("days").obj(it).optDouble("h", 0.0) }
    val currentWeek = StudyPlan.monday(model.today)
    val rate = weekStats(model, currentWeek).rate
    val cards = listOf(
        Triple("$rate%", "本周最低线完成率", c.accent),
        Triple("🔥 ${model.plan.streak(state, model.today)}", "连续打卡（天）", c.text),
        Triple("${dates.count { model.plan.anyDone(state.obj("days").optJSONObject(it)) }}", "累计打卡（天）", c.text),
        Triple("${hours(totalHours)} h", "累计学习时长", c.greenStrong),
        Triple("🩹 ${model.plan.makeupLeft(model.today.take(7), state)}", "本月补签卡剩余", c.text),
        Triple("${daysBetween(model.today, model.plan.exam(state))}", "距初试（天）", c.orange),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cards.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (value, label, color) ->
                    Column(Modifier.weight(1f).heightIn(min = 76.dp).clip(RoundedCornerShape(14.dp)).background(c.card)
                        .border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(value, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold, color = color, maxLines = 1)
                        Text(label, fontSize = 12.sp, lineHeight = 18.sp, color = c.sub, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
    Section("本周最低线完成率 $rate%") {
        ProgressBar(rate / 100f)
        Spacer(Modifier.height(8.dp))
        Tip("上周 ${weekStats(model, StudyPlan.addDays(currentWeek, -7)).rate}% · 上上周 ${weekStats(model, StudyPlan.addDays(currentWeek, -14)).rate}%")
    }
    Section("科目时间分布（按记录时长）") {
        val totals = linkedMapOf<String, Double>()
        dates.forEach { date -> state.obj("days").obj(date).obj("sh").let { subjects -> subjects.namesList().forEach { totals[it] = (totals[it] ?: 0.0) + subjects.getDouble(it) } } }
        val shown = totals.filterValues { it > 0 }.entries.sortedByDescending { it.value }
        val max = shown.maxOfOrNull { it.value }?.coerceAtLeast(1.0) ?: 1.0
        if (shown.isEmpty()) Tip("还没有科目时长记录（在今日页点「+0.5 西综」等按钮）")
        shown.forEach { (subject, total) ->
            val color = when (subject) { "西综" -> c.blue; "英语" -> c.greenStrong; "政治" -> c.orange; else -> c.purple }
            BarRow(subject, "${hours(total)}h", (total / max).toFloat(), color)
        }
    }
    Section("近 8 周学习时长（橙=未达目标）") {
        val weeks = (7 downTo 0).map { offset ->
            val monday = StudyPlan.addDays(currentWeek, -7L * offset)
            monday to weekStats(model, monday)
        }
        val max = weeks.maxOf { maxOf(it.second.hours, it.second.target) }.coerceAtLeast(1.0)
        Row(Modifier.fillMaxWidth().height(78.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            weeks.forEach { (monday, stats) ->
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth(.7f).widthIn(max = 26.dp).height(((stats.hours / max * 57).toFloat()).coerceAtLeast(2f).dp)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(Brush.verticalGradient(
                            if (stats.hours < stats.target) listOf(Color(0xFFF59E0B), Color(0xFFFBBF24)) else listOf(c.blue, Color(0xFF60A5FA)))))
                    Text(shortDate(monday), fontSize = 9.sp, lineHeight = 14.sp, color = c.sub, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Tip("柱高为实际时长；橙色表示低于该阶段日目标累计值")
    }
    Section("打卡热力图（计划全程）") { HeatMap(model) }
    Section("数据健康") {
        val last = state.optLong("bk")
        val backup = if (last == 0L) "从未备份" else java.time.Instant.ofEpochMilli(last).atZone(CheckinCore.shanghai).toLocalDate().toString()
        Tip("上次备份：$backup\n累计记录 ${state.obj("days").length()} 天 · 模考 ${state.arr("scores").length()} 次 · 章节数据 ${state.obj("ch").length()} 科")
    }
}

@Composable
private fun HeatMap(model: CheckinViewModel) {
    val c = webColors
    val history = model.state.obj("days").namesList().filter { it < model.plan.start && it <= model.today }.sorted()
    if (model.today < model.plan.start && history.isEmpty()) { Tip("开营（${model.plan.start}）后开始记录，届时这里会显示每一天的完成热度。与已有记录一同展示。") ; return }
    val start = StudyPlan.monday(history.firstOrNull() ?: model.plan.start)
    val end = StudyPlan.monday(model.today)
    val weeks = (daysBetween(start, end) / 7 + 1).toInt().coerceIn(1, 90)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(weeks) { w ->
            val monday = StudyPlan.addDays(start, 7L * w)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.width(11.dp).height(12.dp)) {
                    Text(if (w == 0 || monday.substring(5, 7) != StudyPlan.addDays(monday, -7).substring(5, 7)) "${monday.substring(5, 7).toInt()}月" else "",
                        Modifier.wrapContentWidth(Alignment.Start, unbounded = true), fontSize = 9.sp, lineHeight = 12.sp, maxLines = 1, softWrap = false, color = c.sub)
                }
                repeat(7) { day ->
                    val date = StudyPlan.addDays(monday, day.toLong())
                    val rec = model.state.obj("days").optJSONObject(date)
                    val completed = if (rec == null) 0 else model.plan.tasks(date, model.state, model.today).count { model.plan.isDone(rec, it.id) }
                    val color = when { date > model.today -> c.line.copy(alpha = .25f); completed == 0 -> c.line; completed <= 2 -> Color(0xFF86D99C); completed <= 4 -> Color(0xFF34A853); else -> Color(0xFF15803D) }
                    Box(Modifier.size(11.dp).clip(RoundedCornerShape(2.5.dp)).background(color))
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        Text("少", fontSize = 10.5.sp, color = c.sub)
        listOf(c.line, Color(0xFF86D99C), Color(0xFF34A853), Color(0xFF15803D)).forEach { color ->
            Box(Modifier.size(11.dp).clip(RoundedCornerShape(2.5.dp)).background(color))
        }
        Text("多", fontSize = 10.5.sp, color = c.sub)
    }
    Spacer(Modifier.height(8.dp))
    Tip("横向滑动查看 · 从最早显示的记录周到今天")
}

// Web vMile: info notice, progress and expandable .mi milestone list.
@Composable
internal fun MilestonesScreen(model: CheckinViewModel) {
    val c = webColors
    val start = model.plan.start
    val exam = model.plan.exam(model.state)
    val total = daysBetween(start, exam)
    val passed = if (total <= 0) 0 else (daysBetween(start, model.today).toDouble() / total * 100).roundToInt().coerceIn(0, 100)
    val remaining = daysBetween(model.today, exam)
    Banner("报名、确认、考试等日期仅供规划，尚未核实为官方时间。请提前查看研招网及报考点公告；导入日历不会自动跟随网页更新。", "info")
    Section {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("备考全程进度", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = c.text)
            Text("$start ~ $exam", fontSize = 11.sp, color = c.sub, maxLines = 1)
        }
        Spacer(Modifier.height(10.dp))
        ProgressBar(passed / 100f, height = 10.dp)
        Spacer(Modifier.height(8.dp))
        Tip("已走完 $passed% · ${if (remaining >= 0) "距初试还有 $remaining 天" else "初试已结束"} · 全程 $total 天")
    }
    Section("关键节点与检查点") {
        model.plan.milestones.sortedBy { it.getString(0).ifEmpty { exam } }.forEachIndexed { index, milestone ->
            val date = milestone.getString(0).ifEmpty { exam }
            val delta = daysBetween(model.today, date)
            var expanded by rememberSaveable(index) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable { expanded = !expanded }.padding(horizontal = 2.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(milestone.getString(1), Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                Text(date, color = c.sub, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Chip(if (delta > 0) "还有 $delta 天" else if (delta == 0L) "就是今天" else "已过 ${-delta} 天",
                    if (delta < 0) c.sub else c.accent, if (delta < 0) c.line else c.chipBlue)
            }
            if (expanded) { Tip(milestone.getString(2)); Spacer(Modifier.height(8.dp)) }
            Divider()
        }
    }
    Section("按实际进度调整") {
        Tip("① 平时按每天1–2小时规划，先保证英语与西综两项最低线；\n② 每月核对章节、英语阅读和可用时间；任务未完成时调整计划，不必堆积补课；\n③ 总时长偏紧，真题套卷可另找较长时间。结合多次练习、目标院校要求和可投入时间调整目标。")
    }
    // Preserve the existing Android phase route, after the web's milestone sections.
    Section("阶段路线") {
        val phases = model.plan.data.arr("PHASES").values().map { it as JSONObject } + model.plan.phase("2027-12-01", model.state) + model.plan.data.obj("PH5")
        phases.distinctBy { it.getString("id") }.forEach { phase ->
            Text(phase.getString("name"), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.text)
            Tip("${phase.optString("s", exam)} — ${phase.optString("e", "2028-08-31")} · ${phase.getString("desc")}")
            Spacer(Modifier.height(8.dp))
            Divider()
        }
    }
}
