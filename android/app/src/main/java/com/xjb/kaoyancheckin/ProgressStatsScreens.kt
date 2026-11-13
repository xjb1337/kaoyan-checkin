package com.xjb.kaoyancheckin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

// Web vProg: plan-vs-actual bars, expandable .mi chapter list, and score card.
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProgressScreen(model: CheckinViewModel) {
    val c = webColors
    val enabled = model.loadError.isEmpty()
    var deleteScoreId by remember { mutableStateOf<String?>(null) }
    var expandedSubjectKey by rememberSaveable { mutableStateOf<String?>(null) }
    val calculations = rememberProgressCalculations(model.plan, model.state, model.today)
    val words = calculations.words
    val wordForecast = calculations.wordForecast
    val subjectForecasts = calculations.subjectForecasts
    val hoursForecast = calculations.hoursForecast
    val scope = rememberCoroutineScope()
    val chapterRequester = remember { BringIntoViewRequester() }
    val scoreRequester = remember { BringIntoViewRequester() }
    fun revealChapters() {
        val first = calculations.planned.firstOrNull { !it.upcoming } ?: calculations.planned.firstOrNull()
        expandedSubjectKey = first?.subject?.getString("k")
        scope.launch { chapterRequester.bringIntoView() }
    }
    Section("学习进度预测") {
        ForecastRow(
            formatWordsForecast(wordForecast), wordForecast.tone, wordForecastStatus(wordForecast),
            method = wordForecastMethod(wordForecast), gates = wordForecastGates(wordForecast),
        )
        subjectForecasts.filterNot { it is SubjectForecast.NotStarted }.forEach { forecast ->
            ForecastRow(
                formatSubjectForecast(forecast), subjectForecastTone(forecast), subjectForecastStatus(forecast),
                method = subjectForecastMethod(forecast), gates = subjectForecastGates(forecast),
            )
        }
        formatUpcomingSubjects(subjectForecasts)?.let { text ->
            ForecastRow(text, ForecastTone.NEUTRAL, "未开始")
        }
        ForecastRow(
            formatHoursForecast(hoursForecast), hoursForecast.tone, hoursForecastStatus(hoursForecast),
            method = hoursForecastMethod(hoursForecast), gates = hoursForecastGates(hoursForecast),
        )
    }
    Section("单词第一轮") {
        Text("累计已背 ${words.total} / ${words.goal}", fontWeight = FontWeight.SemiBold, color = c.text)
        Spacer(Modifier.height(8.dp))
        ProgressBar((words.total.toDouble() / words.goal).toFloat())
        Spacer(Modifier.height(8.dp))
        Tip("今日新词 ${words.todayNew} · 复习 ${words.todayReview} · 距第一轮还剩 ${words.remaining}")
    }
    Section("计划 vs 实际") {
        calculations.planned.filterNot { it.upcoming }.forEach { progress ->
            val subject = progress.subject
            val total = progress.total
            val done = progress.done
            val expected = progress.expected
            val diff = progress.difference
            val (status, color, bg) = when {
                diff > 0 -> Triple("领先 $diff", c.green, c.chipGreen)
                diff < 0 -> Triple("落后 ${-diff}", c.red, c.chipRed)
                else -> Triple("正常", c.green, c.chipGreen)
            }
            val barColor = if (diff < 0) c.red else c.green
            BarRow(
                subject.getString("name"), "$done/$total", if (total == 0) 0f else done.toFloat() / total, barColor,
                target = if (total == 0) null else expected.toFloat() / total,
            )
            Row(Modifier.padding(start = 52.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("计划 $expected 章", fontSize = 12.sp, color = c.sub)
                Chip(status, color, bg)
            }
        }
        formatUnstartedSubjects(calculations.planned)?.let { Tip(it) }
        if (calculations.planned.none { it.done > 0 }) {
            Spacer(Modifier.height(8.dp))
            WebButton("开始勾选章节", ::revealChapters)
        }
    }
    Section("章节进度", modifier = Modifier.bringIntoViewRequester(chapterRequester)) {
        calculations.planned.forEach { progress ->
            val subject = progress.subject
            val subjectKey = subject.getString("k")
            val chapters = subject.arr("ch")
            val flags = model.state.obj("ch").arr(subjectKey)
            val done = progress.done
            val expanded = expandedSubjectKey == subjectKey
            Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable {
                expandedSubjectKey = if (expanded) null else subjectKey
            }.padding(horizontal = 2.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(subject.getString("name"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.text)
                Text("$done/${chapters.length()} 章", fontSize = 11.5.sp, color = c.sub, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Chip("${subject.getString("s").substring(5)}~${subject.getString("e").substring(5)}", c.accent, c.chipBlue)
            }
            Row(Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(progress.estimate.text, Modifier.weight(1f), color = c.sub, fontSize = 12.sp, lineHeight = 18.sp)
                progress.estimate.status?.let { status ->
                    val (fg, bg) = when (progress.estimate.tone) {
                        ChapterEstimateTone.GOOD -> c.green to c.chipGreen
                        ChapterEstimateTone.BAD -> c.red to c.chipRed
                        ChapterEstimateTone.NEUTRAL -> c.sub to c.bg
                    }
                    Chip(status, fg, bg)
                }
            }
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
        val scores = calculations.scores
        var date by rememberSaveable { mutableStateOf(model.today) }
        var name by rememberSaveable { mutableStateOf("") }
        var total by rememberSaveable { mutableStateOf("") }
        var phys by rememberSaveable { mutableStateOf("") }
        var bioc by rememberSaveable { mutableStateOf("") }
        var path by rememberSaveable { mutableStateOf("") }
        var med by rememberSaveable { mutableStateOf("") }
        var surg by rememberSaveable { mutableStateOf("") }
        val typedTotal = total.toDoubleOrNull()
        val partsTotal = listOf(phys, bioc, path, med, surg).sumOf { it.toDoubleOrNull() ?: 0.0 }
        val partsOver = typedTotal != null && partsTotal > typedTotal
        if (scores.isEmpty()) {
            ScoreChart(emptyList())
            WebButton("记录一次模考", { scope.launch { scoreRequester.bringIntoView() } }, Modifier.fillMaxWidth())
        }
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
        Column(Modifier.bringIntoViewRequester(scoreRequester)) {
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("总分", Modifier.weight(1f), color = c.sub, fontSize = 11.5.sp)
                Chip("300 分", c.accent, c.chipBlue)
            }
            WebInput(total, { total = it }, "如 215", Modifier.fillMaxWidth(), enabled, numeric = true)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("分科得分", Modifier.weight(1f), color = c.sub, fontSize = 11.5.sp)
                Chip("选填", c.sub, c.bg)
                if (partsOver) Chip("合计 ${scoreAmount(partsTotal)} / ${scoreAmount(typedTotal!!)} 分", c.red, c.chipRed)
            }
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
            }, Modifier.fillMaxWidth(), enabled && !partsOver, big = true, primary = true)
        }
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
private fun ForecastRow(
    text: String,
    tone: ForecastTone,
    status: String,
    method: String? = null,
    gates: List<String> = emptyList(),
) {
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
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, color = c.text, fontSize = 12.5.sp, lineHeight = 19.sp)
            if (method != null || gates.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    method?.let { Chip(it, c.sub, c.bg) }
                    gates.forEach { Chip(it, c.sub, c.bg) }
                }
            }
        }
        Chip(status, color, background)
    }
}

private fun wordForecastStatus(forecast: WordForecast): String = when (forecast) {
    is WordForecast.Completed -> "已完成"
    is WordForecast.Insufficient -> "数据不足"
    is WordForecast.Estimated -> deltaStatus(forecast.planDeltaDays)
}

private fun subjectForecastStatus(forecast: SubjectForecast): String = when (forecast) {
    is SubjectForecast.NotStarted -> "未开始"
    is SubjectForecast.Completed -> forecast.planDeltaDays?.let(::completionStatus) ?: "已完成"
    is SubjectForecast.Insufficient -> "数据不足"
    is SubjectForecast.Estimated -> deltaStatus(forecast.planDeltaDays)
}

private fun subjectForecastTone(forecast: SubjectForecast): ForecastTone = when {
    forecast is SubjectForecast.Completed && (forecast.planDeltaDays ?: 0) > 0 -> ForecastTone.BAD
    else -> forecast.tone
}

private fun hoursForecastStatus(forecast: HoursForecast): String = when (forecast) {
    is HoursForecast.Insufficient -> "数据不足"
    is HoursForecast.Estimated -> if (forecast.achievementRate >= 100.0) "达标" else "未达标"
}

private fun deltaStatus(deltaDays: Long): String = when {
    deltaDays < 0 -> "提前 ${-deltaDays} 天"
    deltaDays > 0 -> "落后 $deltaDays 天"
    else -> "按计划"
}

private fun completionStatus(deltaDays: Long): String = when {
    deltaDays < 0 -> "提前 ${-deltaDays} 天"
    deltaDays > 0 -> "推迟 $deltaDays 天"
    else -> "按计划"
}

private fun wordForecastMethod(forecast: WordForecast): String? = when (forecast) {
    is WordForecast.Estimated, is WordForecast.Insufficient -> "近${FORECAST_WINDOW_DAYS}天"
    is WordForecast.Completed -> null
}

private fun wordForecastGates(forecast: WordForecast): List<String> =
    if (forecast is WordForecast.Insufficient) listOf("${forecast.activeDays}/$MIN_WORD_ACTIVE_DAYS 天") else emptyList()

private fun subjectForecastMethod(forecast: SubjectForecast): String? = when (forecast) {
    is SubjectForecast.Estimated -> if (forecast.pace == SubjectPace.RECENT) "近${RECENT_CHAPTER_WINDOW_DAYS}天" else "平均"
    else -> null
}

private fun subjectForecastGates(forecast: SubjectForecast): List<String> = when (forecast) {
    is SubjectForecast.Insufficient -> when (forecast.reason) {
        SubjectForecastGap.NO_CHAPTERS -> listOf("0/$MIN_CHAPTERS_FOR_FORECAST 章", "0/$MIN_CHAPTER_SPAN_DAYS 天")
        SubjectForecastGap.MISSING_FIRST_DATE -> listOf("${forecast.done}/$MIN_CHAPTERS_FOR_FORECAST 章", "缺首次日期")
        SubjectForecastGap.TOO_FEW_CHAPTERS, SubjectForecastGap.TOO_SHORT ->
            listOf("${forecast.done}/$MIN_CHAPTERS_FOR_FORECAST 章", "${forecast.elapsedDays ?: 0}/$MIN_CHAPTER_SPAN_DAYS 天")
    }
    else -> emptyList()
}

private fun hoursForecastMethod(forecast: HoursForecast): String? = when (forecast) {
    is HoursForecast.Estimated, is HoursForecast.Insufficient -> "近${FORECAST_WINDOW_DAYS}天"
}

private fun hoursForecastGates(forecast: HoursForecast): List<String> =
    if (forecast is HoursForecast.Insufficient) listOf("${forecast.activeDays}/$MIN_HOURS_ACTIVE_DAYS 天") else emptyList()

@Composable
private fun ScoreChart(scores: List<JSONObject>) {
    val c = webColors
    val textMeasurer = rememberTextMeasurer()
    val tickStyle = TextStyle(color = c.sub, fontSize = 9.sp)
    val referenceText = "个人参考线 ${ScoreChartMath.REFERENCE_SCORE.toInt()}"
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

private fun scoreAmount(value: Double): String =
    String.format(Locale.ROOT, if (value % 1.0 == 0.0) "%.0f" else "%.1f", value)

// Web vStats: .stat two-column grid, weekly rate, .barrow, .wbar and .heat.
@Composable
internal fun StatsScreen(model: CheckinViewModel) {
    val c = webColors
    val state = model.state
    val calculations = rememberStatsCalculations(model.plan, state, model.today)
    val rate = calculations.weeks.last().second.rate
    val cards = listOf(
        Triple("$rate%", "本周最低线完成率", c.accent),
        Triple("🔥 ${calculations.streak}", "连续打卡（天）", c.text),
        Triple("${calculations.activeDays}", "累计打卡（天）", c.text),
        Triple("${hours(calculations.totalHours)} h", "累计学习时长", c.greenStrong),
        Triple("🩹 ${calculations.makeupLeft}", "本月补签卡剩余", c.text),
        Triple("${calculations.examDays}", "距初试（天）", c.orange),
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
        Tip("上周 ${calculations.weeks[6].second.rate}% · 上上周 ${calculations.weeks[5].second.rate}%")
    }
    Section("科目时间分布", badge = "小时") {
        val empty = calculations.subjectHours.isEmpty()
        val shown = calculations.subjectHours.ifEmpty {
            listOf("西综" to 0.0, "英语" to 0.0, "政治" to 0.0, "其他" to 0.0)
        }
        val max = shown.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0
        shown.forEach { (subject, total) ->
            val color = when (subject) { "西综" -> c.subjectBlue; "英语" -> c.greenStrong; "政治" -> c.orange; else -> c.purple }
            BarRow(subject, "${hours(total)}h", (total / max).toFloat(), color)
        }
        if (empty) {
            Spacer(Modifier.height(8.dp))
            WebButton("去记录时长", { model.page = 0 }, Modifier.fillMaxWidth())
        }
    }
    Section("近 8 周学习时长") {
        WeeklyHoursChart(calculations.weeks)
    }
    Section("打卡热力图（计划全程）") { HeatMap(calculations.heatmap, model.today, model.plan.start) }
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
private fun WeeklyHoursChart(weeks: List<Pair<String, WeekStats>>) {
    val c = webColors
    val max = weeks.maxOf { maxOf(it.second.hours, it.second.target) }.coerceAtLeast(1.0)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(108.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            weeks.forEach { (monday, stats) ->
                val belowTarget = stats.hours < stats.target
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${hours(stats.hours)}h", fontSize = 8.5.sp, lineHeight = 12.sp, color = c.sub, maxLines = 1)
                    Canvas(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 1.dp)) {
                        val barWidth = size.width * .62f
                        val left = (size.width - barWidth) / 2f
                        val height = (stats.hours / max * size.height).toFloat().let {
                            if (stats.hours > 0) it.coerceAtLeast(2.dp.toPx()) else 0f
                        }
                        if (height > 0f) {
                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    if (belowTarget) listOf(Color(0xFFF59E0B), Color(0xFFFBBF24))
                                    else listOf(c.blue, c.progressHighlight),
                                ),
                                topLeft = Offset(left, size.height - height),
                                size = Size(barWidth, height),
                                cornerRadius = CornerRadius(3.dp.toPx()),
                            )
                        }
                        if (stats.target > 0) {
                            val y = (size.height * (1.0 - (stats.target / max).coerceIn(0.0, 1.0))).toFloat()
                            drawLine(
                                c.sub,
                                Offset(size.width * .08f, y),
                                Offset(size.width * .92f, y),
                                1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx())),
                            )
                        }
                    }
                    Text(shortDate(monday), fontSize = 8.5.sp, lineHeight = 12.sp, color = c.sub, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFF59E0B)))
                Text("未达目标", fontSize = 10.5.sp, color = c.sub)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.width(18.dp).height(8.dp)) {
                    drawLine(c.sub, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx())))
                }
                Text("阶段目标", fontSize = 10.5.sp, color = c.sub)
            }
        }
    }
}

@Composable
private fun HeatMap(heatmap: List<HeatmapWeek>, today: String, planStart: String) {
    val c = webColors
    val beforePlan = heatmap.isEmpty()
    if (beforePlan) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            CalendarIcon()
            Text("开营 ${shortDate(planStart)}", color = c.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Chip("计划前", c.sub, c.bg)
        }
        Spacer(Modifier.height(8.dp))
    }
    val shown = if (beforePlan) {
        val startMonday = StudyPlan.monday(planStart)
        (0 until 4).map { index ->
            val monday = StudyPlan.addDays(startMonday, index * 7L)
            HeatmapWeek(monday, (0..6).map { StudyPlan.addDays(monday, it.toLong()) to 0 })
        }
    } else heatmap
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            shown.forEachIndexed { index, week ->
                val monday = week.monday
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.width(11.dp).height(12.dp)) {
                        Text(if (index == 0 || monday.substring(5, 7) != StudyPlan.addDays(monday, -7).substring(5, 7)) "${monday.substring(5, 7).toInt()}月" else "",
                            Modifier.wrapContentWidth(Alignment.Start, unbounded = true), fontSize = 9.sp, lineHeight = 12.sp, maxLines = 1, softWrap = false, color = c.sub)
                    }
                    week.completed.forEach { (date, completed) ->
                        val color = when { date > today -> c.line.copy(alpha = .25f); completed == 0 -> c.line; completed <= 2 -> Color(0xFF86D99C); completed <= 4 -> Color(0xFF34A853); else -> Color(0xFF15803D) }
                        val shape = RoundedCornerShape(2.5.dp)
                        Box(Modifier.size(11.dp).clip(shape).background(color)
                            .then(if (date == planStart) Modifier.border(1.dp, c.accent, shape) else Modifier))
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(18.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, c.card))))
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        Text("少", fontSize = 10.5.sp, color = c.sub)
        listOf(c.line, Color(0xFF86D99C), Color(0xFF34A853), Color(0xFF15803D)).forEach { color ->
            Box(Modifier.size(11.dp).clip(RoundedCornerShape(2.5.dp)).background(color))
        }
        Text("多", fontSize = 10.5.sp, color = c.sub)
    }
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
    Banner("日期非官方", "warn") {
        WebLinkButton("研招网公告", "https://yz.chsi.com.cn/")
    }
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
                Chip("预计", c.orange, c.chipOrange)
                Chip(if (delta > 0) "还有 $delta 天" else if (delta == 0L) "就是今天" else "已过 ${-delta} 天",
                    if (delta < 0) c.sub else c.accent, if (delta < 0) c.line else c.chipBlue)
            }
            if (expanded) { Tip(milestone.getString(2)); Spacer(Modifier.height(8.dp)) }
            Divider()
        }
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
