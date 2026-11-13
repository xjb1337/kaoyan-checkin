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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjb.kaoyancheckin.ui.theme.webColors
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

// Web vProg: plan-vs-actual bars, expandable .mi chapter list, and score card.
@Composable
internal fun ProgressScreen(model: CheckinViewModel) {
    val c = webColors
    val enabled = model.loadError.isEmpty()
    var deleteScoreId by remember { mutableStateOf<String?>(null) }
    val words = wordProgress(model.state, model.today)
    val todayDate = LocalDate.parse(model.today)
    val examDate = LocalDate.parse(model.plan.exam(model.state))
    val wordForecast = forecastWords(model.state, todayDate, exam = examDate)
    val subjectForecasts = model.plan.subjects.map { forecastSubject(it, model.state, todayDate) }
    val hoursForecast = forecastHours(model.state, model.plan, todayDate)
    Section("学习进度预测") {
        ForecastRow(formatWordsForecast(wordForecast), wordForecast.tone, wordForecastStatus(wordForecast))
        subjectForecasts.filterNot { it is SubjectForecast.NotStarted }.forEach { forecast ->
            ForecastRow(formatSubjectForecast(forecast), forecast.tone, subjectForecastStatus(forecast))
        }
        formatUpcomingSubjects(subjectForecasts)?.let { text ->
            ForecastRow(text, ForecastTone.NEUTRAL, "未开始")
        }
        ForecastRow(formatHoursForecast(hoursForecast), hoursForecast.tone, hoursForecastStatus(hoursForecast))
        Spacer(Modifier.height(8.dp))
        Tip("按近 ${FORECAST_WINDOW_DAYS} 天实际速度推算；章节有逐章日期时按近 " +
            "$RECENT_CHAPTER_WINDOW_DAYS 天速度，否则按首次勾选以来的平均速度；记录太少时不预测")
    }
    Section("单词第一轮") {
        Text("累计已背 ${words.total} / ${words.goal}", fontWeight = FontWeight.SemiBold, color = c.text)
        Spacer(Modifier.height(8.dp))
        ProgressBar((words.total.toDouble() / words.goal).toFloat())
        Spacer(Modifier.height(8.dp))
        Tip("今日新词 ${words.todayNew} · 复习 ${words.todayReview} · 距第一轮还剩 ${words.remaining}")
    }
    Section("计划 vs 实际（按计划线性推进）") {
        var anyChapter = false
        model.plan.subjects.forEach { subject ->
            val start = subject.getString("s")
            val end = subject.getString("e")
            val total = subject.arr("ch").length()
            val done = model.plan.chapterDone(subject, model.state)
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
            val done = model.plan.chapterDone(subject, model.state)
            var expanded by rememberSaveable(subjectKey) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable { expanded = !expanded }.padding(horizontal = 2.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(subject.getString("name"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.text)
                Text("$done/${chapters.length()} 章", fontSize = 11.5.sp, color = c.sub, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Chip("${subject.getString("s").substring(5)}~${subject.getString("e").substring(5)}", c.accent, c.chipBlue)
            }
            val firstDate = model.state.obj("cf").optString(subjectKey).takeIf(CheckinCore::validDate)?.let(LocalDate::parse)
            val completionDate = model.state.obj("ce").optString(subjectKey).takeIf(CheckinCore::validDate)?.let(LocalDate::parse)
            Text(chapterEstimate(LocalDate.parse(model.today), LocalDate.parse(subject.getString("s")),
                LocalDate.parse(subject.getString("e")), done, chapters.length(), firstDate, completionDate),
                Modifier.padding(start = 2.dp, bottom = 10.dp), color = c.sub, fontSize = 12.sp, lineHeight = 18.sp)
            if (expanded) chapters.values().forEachIndexed { index, chapter ->
                val checked = flags.optBoolean(index)
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(enabled = enabled) {
                    model.toggleChapter(subject, index)
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
                    WebButton("删除", { deleteScoreId = score.getString("id") }, enabled = enabled)
                }
                val partText = ScoreEntry.describe(score.obj("p"))
                if (partText.isNotEmpty()) Tip(partText)
                Divider()
            }
        }
        var date by rememberSaveable { mutableStateOf(model.today) }
        var name by rememberSaveable { mutableStateOf("") }
        var total by rememberSaveable { mutableStateOf("") }
        var phys by rememberSaveable { mutableStateOf("") }
        var bioc by rememberSaveable { mutableStateOf("") }
        var path by rememberSaveable { mutableStateOf("") }
        var med by rememberSaveable { mutableStateOf("") }
        var surg by rememberSaveable { mutableStateOf("") }
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
        Spacer(Modifier.height(8.dp))
        Text("选填：各科得分（合计不能超过总分）", color = c.sub, fontSize = 11.5.sp)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScorePartField("生理", phys, { phys = it }, enabled, Modifier.weight(1f))
            ScorePartField("生化", bioc, { bioc = it }, enabled, Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScorePartField("病理", path, { path = it }, enabled, Modifier.weight(1f))
            ScorePartField("内科", med, { med = it }, enabled, Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScorePartField("外科", surg, { surg = it }, enabled, Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        WebButton("保存这次成绩", {
            val score = total.toDoubleOrNull()
            if (!CheckinCore.validDate(date) || date > model.today) model.notify("请填写今天或过去的有效考试日期")
            else if (name.length > 200) model.notify("卷子名称请控制在 200 字以内")
            else if (score == null || !score.isFinite() || score !in 0.0..300.0) model.notify("请填 0-300 的总分")
            else {
                val parts = try {
                    ScoreEntry.normalizeParts(mapOf(
                        "phys" to phys, "bioc" to bioc, "path" to path, "med" to med, "surg" to surg,
                    ), score)
                } catch (e: IllegalArgumentException) {
                    model.notify(e.message ?: "分科得分格式错误")
                    null
                }
                if (parts != null) {
                    model.edit { it.arr("scores").put(JSONObject().put("id", UUID.randomUUID().toString()).put("d", date).put("n", name).put("tot", score).put("p", parts)) }
                    total = ""
                    phys = ""
                    bioc = ""
                    path = ""
                    med = ""
                    surg = ""
                }
            }
        }, Modifier.fillMaxWidth(), enabled, big = true, primary = true)
    }
    deleteScoreId?.let { id ->
        ConfirmDialog(
            "删除这次模考记录？",
            "只删除这条模考成绩，不会影响打卡记录。",
            { deleteScoreId = null },
        ) {
            model.edit { state ->
                state.put(
                    "scores",
                    JSONArray(state.arr("scores").values().filter { (it as JSONObject).getString("id") != id }),
                )
            }
        }
    }
}

@Composable
private fun ForecastRow(text: String, tone: ForecastTone, status: String) {
    val c = webColors
    val (color, background) = when (tone) {
        ForecastTone.GOOD -> c.green to c.chipGreen
        ForecastTone.BAD -> c.red to c.chipRed
        ForecastTone.NEUTRAL -> c.sub to c.bg
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, Modifier.weight(1f), color = color, fontSize = 12.5.sp, lineHeight = 19.sp)
        Chip(status, color, background)
    }
}

private fun wordForecastStatus(forecast: WordForecast): String = when (forecast) {
    is WordForecast.Completed -> "已完成"
    is WordForecast.Insufficient -> "数据不足"
    is WordForecast.Estimated -> when {
        forecast.planDeltaDays < 0 -> "提前"
        forecast.planDeltaDays > 0 -> "落后"
        else -> "按计划"
    }
}

private fun subjectForecastStatus(forecast: SubjectForecast): String = when (forecast) {
    is SubjectForecast.NotStarted -> "未开始"
    is SubjectForecast.Completed -> "已完成"
    is SubjectForecast.Insufficient -> "数据不足"
    is SubjectForecast.Estimated -> when {
        forecast.planDeltaDays < 0 -> "提前"
        forecast.planDeltaDays > 0 -> "落后"
        else -> "按计划"
    }
}

private fun hoursForecastStatus(forecast: HoursForecast): String = when (forecast) {
    is HoursForecast.Insufficient -> "数据不足"
    is HoursForecast.Estimated -> if (forecast.achievementRate >= 100.0) "达标" else "未达标"
}

@Composable
private fun ScoreChart(scores: List<JSONObject>) {
    val c = webColors
    val textMeasurer = rememberTextMeasurer()
    val tickStyle = TextStyle(color = c.sub, fontSize = 9.sp)
    val referenceText = "参考线 ${ScoreChartMath.REFERENCE_SCORE.toInt()}"
    val referenceStyle = TextStyle(color = c.orange, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
    val ticks = ScoreChartMath.axisScores.map { it to textMeasurer.measure("$it", style = tickStyle) }
    val referenceLabel = textMeasurer.measure(referenceText, style = referenceStyle)
    Canvas(Modifier.fillMaxWidth().aspectRatio(ScoreChartMath.VIEW_WIDTH / ScoreChartMath.VIEW_HEIGHT)) {
        val left = size.width * ScoreChartMath.PLOT_LEFT / ScoreChartMath.VIEW_WIDTH
        val right = size.width - size.width * ScoreChartMath.PLOT_RIGHT / ScoreChartMath.VIEW_WIDTH
        ticks.forEach { (value, label) ->
            val y = ScoreChartMath.plotY(value.toDouble(), size.height)
            drawLine(c.line, Offset(left, y), Offset(right, y), 1.dp.toPx())
            drawText(label, topLeft = Offset(left - label.size.width - 3.dp.toPx(), y - label.size.height / 2f))
        }
        val referenceY = ScoreChartMath.plotY(ScoreChartMath.REFERENCE_SCORE, size.height)
        drawLine(c.orange, Offset(left, referenceY), Offset(right, referenceY), 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
        val labelPadX = 5.dp.toPx()
        val labelPadY = 1.dp.toPx()
        val labelTop = 2.dp.toPx()
        drawRoundRect(c.card, topLeft = Offset(left, labelTop),
            size = Size(referenceLabel.size.width + labelPadX * 2, referenceLabel.size.height + labelPadY * 2),
            cornerRadius = CornerRadius(4.dp.toPx()))
        drawText(referenceLabel, topLeft = Offset(left + labelPadX, labelTop + labelPadY))

        val points = ScoreChartMath.points(scores.map { it.optDouble("tot") }, size.width, size.height)
        points.zipWithNext().forEach { (a, b) ->
            drawLine(c.blue, Offset(a.x, a.y), Offset(b.x, b.y), 2.dp.toPx(), cap = StrokeCap.Round)
        }
        points.forEach { point ->
            drawCircle(if (ScoreChartMath.meetsReference(point.score)) c.green else c.red,
                3.5.dp.toPx(), Offset(point.x, point.y))
        }
    }
}

@Composable
private fun ScorePartField(label: String, value: String, onValueChange: (String) -> Unit, enabled: Boolean, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = webColors.sub, fontSize = 11.5.sp)
        WebInput(value, onValueChange, "选填", Modifier.fillMaxWidth(), enabled, numeric = true)
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
    val rate = weekStats(model.plan, state, model.today, currentWeek).rate
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
        Tip("上周 ${weekStats(model.plan, state, model.today, StudyPlan.addDays(currentWeek, -7)).rate}% · 上上周 ${weekStats(model.plan, state, model.today, StudyPlan.addDays(currentWeek, -14)).rate}%")
    }
    Section("科目时间分布（按记录时长）") {
        val totals = linkedMapOf<String, Double>()
        dates.forEach { date -> state.obj("days").obj(date).obj("sh").let { subjects -> subjects.namesList().forEach { totals[it] = (totals[it] ?: 0.0) + subjects.getDouble(it) } } }
        val shown = totals.filterValues { it > 0 }.entries.sortedByDescending { it.value }
        val max = shown.maxOfOrNull { it.value }?.coerceAtLeast(1.0) ?: 1.0
        if (shown.isEmpty()) Tip("还没有科目时长记录（在今日页点「+0.5 西综」等按钮）")
        shown.forEach { (subject, total) ->
            val color = when (subject) { "西综" -> c.subjectBlue; "英语" -> c.greenStrong; "政治" -> c.orange; else -> c.purple }
            BarRow(subject, "${hours(total)}h", (total / max).toFloat(), color)
        }
    }
    Section("近 8 周学习时长（橙=未达目标）") {
        val weeks = (7 downTo 0).map { offset ->
            val monday = StudyPlan.addDays(currentWeek, -7L * offset)
            monday to weekStats(model.plan, state, model.today, monday)
        }
        val max = weeks.maxOf { maxOf(it.second.hours, it.second.target) }.coerceAtLeast(1.0)
        Row(Modifier.fillMaxWidth().height(78.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            weeks.forEach { (monday, stats) ->
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth(.7f).widthIn(max = 26.dp).height(((stats.hours / max * 57).toFloat()).coerceAtLeast(2f).dp)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(Brush.verticalGradient(
                            if (stats.hours < stats.target) listOf(Color(0xFFF59E0B), Color(0xFFFBBF24)) else listOf(c.blue, c.progressHighlight))))
                    Text(shortDate(monday), fontSize = 9.sp, lineHeight = 14.sp, color = c.sub, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Tip("柱高为实际时长；橙色表示低于该阶段日目标累计值")
    }
    Section("打卡热力图（计划全程）") { HeatMap(model) }
    Section("数据健康") {
        val lastBackup = state.optLong("bk")
        val daysAgo = if (lastBackup > 0L) {
            (System.currentTimeMillis() - lastBackup).coerceAtLeast(0L) / 86_400_000L
        } else null
        val needsBackup = daysAgo != null && daysAgo >= state.obj("settings").optInt("backupDays", 7)
        val backupLabel = when {
            daysAgo == null -> "从未备份"
            daysAgo == 0L -> "今天"
            else -> "$daysAgo 天前"
        }
        Text(buildAnnotatedString {
            append("上次备份：")
            if (daysAgo == null) {
                withStyle(SpanStyle(color = c.amber, fontWeight = FontWeight.Bold)) { append(backupLabel) }
            } else {
                append(backupLabel)
                if (needsBackup) {
                    withStyle(SpanStyle(color = c.orange, fontWeight = FontWeight.Bold)) { append(" · 该备份了") }
                }
            }
        }, color = c.sub, fontSize = 13.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(6.dp))
        Tip("累计记录 ${state.obj("days").length()} 天 · 模考 ${state.arr("scores").length()} 次 · 章节数据 ${state.obj("ch").length()} 科")
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
        Tip("① 平时按每天1.5小时规划，寒假3–4小时，先保证英语与西综两项最低线；\n② 每月核对章节、英语阅读和可用时间；任务未完成时调整计划，不必堆积补课；\n③ 内科、外科分值最高（约56%），一轮后在10–11月安排二轮；7月实习后每天1–2小时以保底为主，真题套卷放周末。结合多次练习、目标院校要求和可投入时间调整目标。")
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
