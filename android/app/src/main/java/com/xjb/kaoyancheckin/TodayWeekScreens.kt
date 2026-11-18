package com.xjb.kaoyancheckin

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjb.kaoyancheckin.ui.theme.webColors
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

internal data class WeekStats(val rate: Int, val days: Int, val elapsed: Int, val hours: Double, val target: Double)

// Presentation-only equivalents of the web's weekRate/weekStats, using existing plan and records.
internal fun weekStats(plan: StudyPlan, state: JSONObject, today: String, week: String): WeekStats {
    var completed = 0
    var total = 0
    var met = 0
    var elapsed = 0
    var hours = 0.0
    var target = 0.0
    for (i in 0..6) {
        val date = StudyPlan.addDays(week, i.toLong())
        if (date > today) break
        val record = state.obj("days").optJSONObject(date)
        val all = plan.tasks(date, state, today)
        val phase = plan.phase(date, state)
        val minimumIds = phase.arr("tasks").values().filterIsInstance<JSONObject>().filter { it.optInt("min") == 1 }.map { it.optString("id") }.toSet()
        val tasks = if (phase.optInt("fs") == 1) all else all.filter { it.id in minimumIds }
        total += tasks.size
        completed += tasks.count { plan.isDone(record, it.id) }
        if (date < plan.start && record == null) continue
        elapsed++
        if (plan.satisfied(date, state)) met++
        hours += record?.optDouble("h", 0.0) ?: 0.0
        target += plan.goalHours(date, state, today)
    }
    return WeekStats(if (total == 0) 0 else (completed * 100.0 / total).roundToInt(), met, elapsed, hours, target)
}

// Web vToday: .daynav, backup reminder, .minline, task list, hours, review and stage details.
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TodayScreen(model: CheckinViewModel) {
    val state = model.state
    val date = model.selected
    val today = model.today
    val record = remember(state, date) { state.obj("days").optJSONObject(date) ?: StudyPlan.day() }
    val phase = remember(model.plan, state, date, today) { model.plan.phase(date, state) }
    val enabled = date <= model.today && model.loadError.isEmpty()
    val isToday = date == model.today
    val tasks = remember(model.plan, state, date, today) { model.plan.tasks(date, state, today) }
    val nextDate = StudyPlan.nextWithinLimit(date, model.today, 1)
    var deleteTarget by remember(date) { mutableStateOf<String?>(null) }
    val tasksRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    DateNav(date, { model.selected = StudyPlan.addDays(date, -1) }, { nextDate?.let { model.selected = it } },
        { model.selected = model.today }, "今天", isToday, true, canGoNext = nextDate != null, maxDate = model.today,
        status = when {
            date > model.today -> "预览"
            !isToday -> "补记"
            else -> null
        },
        statusColor = if (date > model.today) webColors.accent else webColors.orange,
        statusBackground = if (date > model.today) webColors.chipBlue else webColors.chipOrange,
    ) {
        if (it <= model.today) model.selected = it
    }
    if (model.today < model.plan.start) Banner("🎯 开营 ${shortDate(model.plan.start)} · 还有 ${daysBetween(model.today, model.plan.start)} 天", "info")
    val needsBackup = remember(state) { CheckinCore.needsBackup(state, System.currentTimeMillis()) }
    if (needsBackup) {
        Banner("💾 记得备份到手机外 ·", "info") { WebButton("去备份", { model.page = 3 }) }
    }
    val maximum = if (phase.optInt("fs") == 1) 1 else 2
    val met = remember(model.plan, state, date, today) { model.plan.satisfied(date, state) }
    val past = date < model.today
    val minDone = remember(model.plan, state, date) { model.plan.minCount(date, state) }
    val doneTasks = remember(model.plan, record, tasks) { tasks.count { model.plan.isDone(record, it.id) } }
    val todayHours = record.optDouble("h", 0.0)
    val goalHours = remember(model.plan, state, date, today) { model.plan.goalHours(date, state, today) }
    val streak = remember(model.plan, state, today) { model.plan.streak(state, today) }
    if (date >= model.plan.start && date <= model.today && tasks.isNotEmpty()) {
        TodayHeroCard(
            phaseName = phase.optString("name"),
            streak = streak,
            minDone = minDone,
            minRequired = maximum,
            met = met,
            doneTasks = doneTasks,
            totalTasks = tasks.size,
            todayHours = todayHours,
            goalHours = goalHours,
        )
    }
    if (isToday && model.loadError.isEmpty()) {
        val summary = rememberTodayForecast(model.plan, state, today)
        if (summary.isNotEmpty()) {
            ForecastGlance(summary) { model.page = 2 }
        } else if (!met && (tasks.isEmpty() || doneTasks < tasks.size)) {
            Banner("预测 · 数据不足", "info") {
                WebButton("去打卡", { scope.launch { tasksRequester.bringIntoView() } })
            }
        }
    }
    when {
        date < model.plan.start || date > model.today -> Unit
        model.plan.isMakeup(date, state) -> Banner("🩹 已用补签卡保住这天（本月还剩 ${model.plan.makeupLeft(date.take(7), state)} 张）", "ok")
        past && !met -> {
            Banner("最低完成线 $minDone/$maximum", "warn") {
                Chip("未达标", webColors.red, webColors.chipRed)
                if (phase.optInt("fs") != 1 && model.plan.makeupLeft(date.take(7), state) > 0)
                    WebButton("🩹 用补签卡", { model.makeup(date) }, enabled = enabled)
            }
        }
    }
    val minBadge = when {
        date < model.plan.start || date > model.today -> null
        met -> "保底已达标"
        else -> "最低线 $minDone/$maximum"
    }
    Section(
        "今日任务 $doneTasks/${tasks.size}",
        badge = minBadge,
        modifier = Modifier.bringIntoViewRequester(tasksRequester),
    ) {
        if (tasks.isEmpty() && date < model.plan.start) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CalendarIcon()
                Text(shortDate(model.plan.start), color = webColors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Chip("开营", webColors.accent, webColors.chipBlue)
            }
        }
        val minimumIds = phase.arr("tasks").values().filterIsInstance<JSONObject>().filter { it.optInt("min") == 1 }.map { it.optString("id") }.toSet()
        val ordered = tasks.sortedByDescending { it.id in minimumIds }
        val minimumTasks = ordered.filter { it.id in minimumIds }
        val optionalTasks = ordered.filterNot { it.id in minimumIds }
        @Composable
        fun renderTask(task: StudyTask) {
            val deletable = task.id.startsWith("c:") || task.id.startsWith("r:")
            val quickHour: (@Composable RowScope.() -> Unit)? = if (enabled && date <= model.today && task.tag in listOf("西综", "英语")) {
                {
                    val currentHours = record.obj("sh").optDouble(task.tag, 0.0)
                    Box(Modifier.heightIn(min = 32.dp)
                        .clip(CircleShape)
                        .background(webColors.chipBlue)
                        .clickable(role = Role.Button) { model.adjustHours(date, task.tag, 0.5) }
                        .semantics { contentDescription = "${task.tag}增加0.5小时" }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center) {
                        Text("${hours(currentHours)}h · +0.5h", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = webColors.accent)
                    }
                }
            } else null
            TaskRow(task.label, model.plan.isDone(record, task.id), enabled, task.tag, task.id in minimumIds,
                minimum = task.id in minimumIds,
                onDelete = if (deletable) ({ deleteTarget = task.id }) else null,
                trailing = quickHour) {
                model.toggleTask(date, task.id)
            }
            if (task.id == "words") {
                Column(Modifier.fillMaxWidth().padding(start = 32.dp, top = 4.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("n" to "新词", "r" to "复习").forEach { (field, label) ->
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(label, fontSize = 12.sp, color = webColors.sub)
                                val count = record.obj("w").optInt(field, 0)
                                AutoSaveInput(model, DraftField.Words(date, field), if (count == 0) "" else count.toString(),
                                    "$label 数量", Modifier.fillMaxWidth(), enabled = enabled, integer = true)
                                if (enabled && date <= model.today) {
                                    val increments = if (field == "n") listOf(20, 40) else listOf(40, 80)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        increments.forEach { delta ->
                                            Box(Modifier.heightIn(min = 36.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(webColors.bg)
                                                .border(1.dp, webColors.line, RoundedCornerShape(6.dp))
                                                .clickable(role = Role.Button) { model.adjustWords(date, field, delta) }
                                                .semantics { contentDescription = "$label 增加 $delta" }
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                                contentAlignment = Alignment.Center) {
                                                Text("+$delta", fontSize = 11.5.sp, color = webColors.sub, fontWeight = FontWeight.Medium)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        minimumTasks.forEach { renderTask(it) }
        if (minimumTasks.isNotEmpty() && optionalTasks.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("其他任务", Modifier.weight(1f), color = webColors.sub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Chip("可选", webColors.sub, webColors.bg)
            }
        }
        optionalTasks.forEach { renderTask(it) }
        AddField("加一条临时任务…", enabled, draftKey = "custom-$date") { name -> model.editDay(date) { day ->
            require(day.arr("c").values().none { (it as JSONObject).optString("n") == name }) { "任务已存在" }
            day.arr("c").put(JSONObject().put("n", name).put("d", false))
        } }
    }
    Section("学习时长 · 按科目记录") {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(hours(record.optDouble("h", 0.0)), fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, color = webColors.text)
            Text("小时 / 日目标 ${hours(model.plan.goalHours(date, state, model.today))}h", Modifier.padding(bottom = 4.dp), fontSize = 13.sp, color = webColors.sub)
        }
        Spacer(Modifier.height(12.dp))
        val defaultSubjects = remember { listOf("西综", "英语", "政治", "其他") }
        val subjects = remember(record) { (defaultSubjects + record.obj("sh").namesList()).distinct() }
        subjects.forEach { subject ->
            val current = record.obj("sh").optDouble(subject, 0.0)
            val isCustomSubject = subject !in defaultSubjects
            // Keep the stored precision in the editable field; only the summary rounds to one decimal.
            Divider()
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(subject, Modifier.weight(1f), fontSize = 14.sp, color = webColors.text)
                if (isCustomSubject && current == 0.0) {
                    WebButton("✕", {
                        model.editDay(date) { day -> day.obj("sh").remove(subject) }
                    }, enabled = enabled, accessibilityLabel = "删除科目$subject")
                }
                WebButton("−", { model.adjustHours(date, subject, -.5) }, Modifier.width(44.dp), enabled,
                    hourButton = true, accessibilityLabel = "减少${subject}半小时")
                AutoSaveInput(model, DraftField.Hours(date, subject), current.toString(), "$subject 小时",
                    Modifier.widthIn(min = 76.dp, max = 96.dp), enabled = enabled, numeric = true, textAlign = TextAlign.Center)
                WebButton("＋", { model.adjustHours(date, subject, .5) }, Modifier.width(44.dp), enabled,
                    hourButton = true, accessibilityLabel = "增加${subject}半小时")
            }
        }
        AddField("添加其他科目", enabled, draftKey = "subject-$date") { subject ->
            if (subject in subjects) {
                model.notify("科目「$subject」已存在")
            } else {
                model.setHours(date, subject, 0.0)
            }
        }
    }
    val filledReviewCount = listOf("g", "s", "n").count { record.obj("rw").optString(it).isNotBlank() }
    var reviewExpanded by rememberSaveable(date) { mutableStateOf(filledReviewCount > 0) }
    Section {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .clickable(role = Role.Button) { reviewExpanded = !reviewExpanded }
            .semantics { stateDescription = if (reviewExpanded) "已展开" else "已折叠" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("三问复盘", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = webColors.text)
            Chip(if (filledReviewCount > 0) "已填 $filledReviewCount/3" else "选填",
                if (filledReviewCount > 0) webColors.accent else webColors.sub,
                if (filledReviewCount > 0) webColors.chipBlue else webColors.bg)
            Text(if (reviewExpanded) "▾" else "▸", color = webColors.sub, fontSize = 14.sp)
        }
        if (reviewExpanded) {
            Spacer(Modifier.height(8.dp))
            listOf("g" to "今天最大的收获", "s" to "今天卡住的地方", "n" to "明天第一件事").forEachIndexed { index, (field, title) ->
                if (index > 0) Spacer(Modifier.height(8.dp))
                AutoSaveField(model, DraftField.DayReview(date, field), title, record.obj("rw").optString(field), enabled, placeholder = "…")
            }
        }
    }
    var stageExpanded by rememberSaveable { mutableStateOf(false) }
    Section {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .clickable(role = Role.Button) { stageExpanded = !stageExpanded }
            .semantics {
                contentDescription = if (stageExpanded) "收起阶段计划" else "展开阶段计划"
                stateDescription = if (stageExpanded) "已展开" else "已折叠"
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("阶段计划", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = webColors.text)
            Text(if (stageExpanded) "▾" else "▸", color = webColors.sub, fontSize = 14.sp)
        }
        if (stageExpanded) {
            val shownPhase = if (date < model.plan.start && (date >= model.today || state.obj("days").optJSONObject(date) == null))
                model.plan.data.arr("PHASES").getJSONObject(0) else phase
            val start = shownPhase.getString("s")
            val end = shownPhase.getString("e")
            val length = (daysBetween(start, end) + 1).coerceAtLeast(1)
            val passed = (daysBetween(start, date) + 1).coerceIn(0, length)
            val percent = (passed.toDouble() / length * 100).roundToInt()
            Text(shownPhase.getString("name"), fontWeight = FontWeight.Bold, color = webColors.text)
            Tip("$start ~ $end")
            Tip("${shownPhase.optString("desc")} · 已过 $percent% · 日目标 ${hours(shownPhase.optDouble("gh", 1.5))}h")
            Spacer(Modifier.height(10.dp))
            ProgressBar(percent / 100f)
        }
    }
    deleteTarget?.let { id ->
        ConfirmDialog("删除这条任务？", "只删除 $date 的当天记录，固定任务设置不会改变。",
            { deleteTarget = null }) {
            if (date == model.today && CheckinCore.today() != model.today) {
                model.resume()
                model.notify("日期已切换，请确认后再删除")
            } else {
                model.deleteCustomTask(date, id)
            }
        }
    }
}

@Composable
private fun TodayHeroCard(
    phaseName: String,
    streak: Int,
    minDone: Int,
    minRequired: Int,
    met: Boolean,
    doneTasks: Int,
    totalTasks: Int,
    todayHours: Double,
    goalHours: Double,
) {
    val c = webColors
    val gradient = Brush.linearGradient(listOf(c.blue, lerp(c.blue, Color.Black, 0.24f)))
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(gradient)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(phaseName, Modifier.weight(1f), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Box(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 9.dp, vertical = 3.dp)) {
                Text(if (met) "连续 $streak 天 · 已达标" else "连续 $streak 天", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "$minDone/$minRequired" to "最低线",
                "$doneTasks/$totalTasks" to "今日任务",
                "${hours(todayHours)}/${hours(goalHours)}h" to "学习时长",
            ).forEach { (value, label) ->
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    Text(label, color = Color.White.copy(alpha = 0.82f), fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        val ratio = if (totalTasks == 0) 0f else (doneTasks.toFloat() / totalTasks).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(ratio).background(Color.White, CircleShape))
        }
    }
}

@Composable
private fun ForecastGlance(items: List<ForecastSummaryItem>, openDetails: () -> Unit) {
    val c = webColors
    Column(
        Modifier.fillMaxWidth()
            .background(c.card, RoundedCornerShape(12.dp))
            .border(1.dp, c.line, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = openDetails)
            .semantics { contentDescription = "查看学习进度预测" }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("学习预测", Modifier.weight(1f), color = c.sub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("详情 ›", color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        val prioritized = remember(items) { items.sortedBy { if (it.tone == ForecastTone.BAD) 0 else 1 }.take(2) }
        prioritized.forEach { item ->
            val (color, background) = when (item.tone) {
                ForecastTone.GOOD -> c.green to c.chipGreen
                ForecastTone.BAD -> c.red to c.chipRed
                ForecastTone.NEUTRAL -> c.sub to c.bg
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(item.text, color = c.text, fontSize = 12.5.sp, lineHeight = 18.sp)
                    item.method?.let { Text(it, color = c.sub, fontSize = 10.sp, lineHeight = 14.sp) }
                }
                Chip(item.status, color, background)
            }
        }
    }
}

// Web vWeek: date navigation, weekly rate, must-do rows, flexible tasks and Sunday review.
@Composable
internal fun WeekScreen(model: CheckinViewModel) {
    val week = model.week
    val currentWeek = StudyPlan.monday(model.today)
    val record = remember(model.state, week) { model.state.obj("weeks").optJSONObject(week) ?: StudyPlan.week() }
    val enabled = week <= currentWeek && model.loadError.isEmpty()
    val stats = remember(model.plan, model.state, model.today, week) { weekStats(model.plan, model.state, model.today, week) }
    val nextWeek = StudyPlan.nextWithinLimit(week, currentWeek, 7)
    var confirmCopyGoals by remember(week) { mutableStateOf(false) }
    var deleteFlexTarget by remember(week) { mutableStateOf<Pair<Int, String>?>(null) }
    DateNav(week, { model.week = StudyPlan.addDays(week, -7) }, { nextWeek?.let { model.week = it } },
        { model.week = currentWeek }, "本周", week == currentWeek, false, canGoNext = nextWeek != null)
    if (!enabled) Banner("未来周目标仅供预览", "info")
    Section {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("本周最低线完成率", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = webColors.text)
            Text("${stats.rate}%", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = webColors.accent)
        }
        Spacer(Modifier.height(10.dp))
        ProgressBar(stats.rate / 100f)
        Spacer(Modifier.height(8.dp))
        Tip("打卡达标 ${stats.days}/${stats.elapsed} 天 · 时长 ${hours(stats.hours)}h / 目标 ${hours(stats.target)}h")
    }
    val prevWeek = StudyPlan.addDays(week, -7)
    val prevRecord = remember(model.state, prevWeek) { model.state.obj("weeks").optJSONObject(prevWeek) }
    val prevHasGoals = remember(prevRecord) {
        (0..2).any { prevRecord?.arr("must")?.optString(it)?.isNotBlank() == true }
    }
    Section("必须完成（周目标 ×3）") {
        repeat(3) { index ->
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                        .toggleable(record.arr("md").optBoolean(index), enabled = enabled, role = Role.Checkbox,
                            onValueChange = { model.editWeek(week) { it.arr("md").put(index, !it.arr("md").optBoolean(index)) } })
                        .semantics { contentDescription = "完成目标 ${index + 1}" },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    RoundCheck(record.arr("md").optBoolean(index))
                }
                Box(Modifier.weight(1f)) {
                    AutoSaveField(model, DraftField.WeekGoal(week, index), "目标 ${index + 1}", record.arr("must").optString(index), enabled,
                        showLabel = false, placeholder = "目标 ${index + 1}，如：生理第 3-4 章网课+章节题")
                }
            }
            Divider()
        }
        Spacer(Modifier.height(4.dp))
        WebButton("复制上周目标", {
            if (!prevHasGoals) model.notify("上周没有目标") else confirmCopyGoals = true
        }, enabled = enabled)
    }
    Section("弹性任务", badge = "可选") {
        record.arr("ex").values().forEachIndexed { index, value ->
            val task = value as JSONObject
            val taskName = task.getString("n")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TaskRow(taskName, task.getBoolean("d"), enabled) {
                        model.editWeek(week) { it.arr("ex").getJSONObject(index).put("d", !task.getBoolean("d")) }
                    }
                }
                WebButton("✕", { deleteFlexTarget = index to taskName }, enabled = enabled,
                    accessibilityLabel = "删除$taskName")
            }
        }
        AddField("加一条弹性任务…", enabled, draftKey = "flex-$week") { name ->
            model.editWeek(week) { w ->
                val ex = w.arr("ex")
                require(ex.values().none { (it as JSONObject).optString("n") == name }) { "任务已存在" }
                ex.put(JSONObject().put("n", name).put("d", false))
            }
        }
    }
    Section("周日复盘（30 分钟）") {
        AutoSaveField(model, DraftField.WeekReview(week), "本周回顾与下周调整", record.optString("review"), enabled, singleLine = false, showLabel = false,
            placeholder = "本周完成度如何？没完成的原因？下周怎么调整？")
    }
    // Web .rep: the report is derived from the records already exposed by the ViewModel.
    Section("自动周报") {
        val context = LocalContext.current
        val completed = (0..2).count { record.arr("md").optBoolean(it) }
        val reviewText = model.draftValue(DraftField.WeekReview(week), record.optString("review"))
        val weekEnd = minOf(StudyPlan.addDays(week, 6), model.today)
        val isHistoricalWeek = weekEnd < model.today
        val report = remember(model.plan, model.state, model.today, week, stats, completed, reviewText) {
            val chapters = model.plan.subjects.mapNotNull { subject ->
                val count = if (!isHistoricalWeek) {
                    model.plan.chapterDone(subject, model.state)
                } else {
                    val key = subject.getString("k")
                    val flags = model.state.obj("ch").arr(key)
                    val dates = model.state.obj("cd").optJSONObject(key)
                    (0 until subject.arr("ch").length()).count { idx ->
                        flags.optBoolean(idx) && dates?.optString(idx.toString())?.let { it.isNotEmpty() && it <= weekEnd } == true
                    }
                }
                if (count == 0) null else "${subject.getString("name")} $count/${subject.arr("ch").length()}章"
            }
            val weekStreak = model.plan.streak(model.state, weekEnd)
            val goalStatus = if (stats.hours >= stats.target) "达标" else "欠 ${hours(stats.target - stats.hours)}h"
            val chapterPrefix = if (isHistoricalWeek) "截至 ${shortDate(weekEnd)} 章节" else "章节"
            val streakLabel = if (isHistoricalWeek) "当时连续打卡 $weekStreak 天" else "连续打卡 $weekStreak 天"
            "【考研周报 ${shortDate(week)}~${shortDate(StudyPlan.addDays(week, 6))}】\n" +
                "打卡 ${stats.days}/${stats.elapsed} 天｜时长 ${hours(stats.hours)}h（目标 ${hours(stats.target)}h，$goalStatus）\n" +
                "周目标完成 $completed/3｜周日复盘：${if (reviewText.isBlank()) "未填" else "已填"}\n" +
                "$chapterPrefix：${chapters.ifEmpty { listOf("暂无进度") }.joinToString("、")}\n$streakLabel。"
        }
        Text(report, Modifier.fillMaxWidth().background(webColors.bg, RoundedCornerShape(10.dp)).padding(11.dp),
            color = webColors.text, fontSize = 12.5.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(8.dp))
        WebButton("复制周报文字", {
            model.flushDraft(DraftField.WeekReview(week))
            val copied = runCatching {
                val clipboard = context.getSystemService(ClipboardManager::class.java) ?: error("剪贴板不可用")
                clipboard.setPrimaryClip(ClipData.newPlainText("考研周报", report))
            }.isSuccess
            model.notify(if (copied) "周报已复制" else "复制失败")
        })
    }
    if (confirmCopyGoals) {
        ConfirmDialog("复制上周目标？", "将用上周目标覆盖本周的 3 条目标，并重置完成状态。",
            { confirmCopyGoals = false }) { model.copyPreviousWeekGoals(week) }
    }
    deleteFlexTarget?.let { (index, name) ->
        ConfirmDialog("删除这条弹性任务？", "将从本周记录中删除「$name」。",
            { deleteFlexTarget = null }) {
            model.editWeek(week) { w ->
                val ex = w.arr("ex")
                if (index in 0 until ex.length() && ex.optJSONObject(index)?.optString("n") == name) {
                    ex.remove(index)
                }
            }
        }
    }
}
