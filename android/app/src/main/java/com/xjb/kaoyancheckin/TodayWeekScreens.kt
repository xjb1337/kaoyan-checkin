package com.xjb.kaoyancheckin

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjb.kaoyancheckin.ui.theme.webColors
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
@Composable
internal fun TodayScreen(model: CheckinViewModel) {
    val state = model.state
    val date = model.selected
    val record = state.obj("days").optJSONObject(date) ?: StudyPlan.day()
    val phase = model.plan.phase(date, state)
    val enabled = date <= model.today && model.loadError.isEmpty()
    val isToday = date == model.today
    val tasks = model.plan.tasks(date, state, model.today)
    val nextDate = StudyPlan.nextWithinLimit(date, model.today, 1)
    var deleteTarget by remember(date) { mutableStateOf<String?>(null) }
    DateNav(date, { model.selected = StudyPlan.addDays(date, -1) }, { nextDate?.let { model.selected = it } },
        { model.selected = model.today }, "今天", isToday, true, canGoNext = nextDate != null, maxDate = model.today) {
        if (it <= model.today) model.selected = it
    }
    if (!isToday) Banner(if (date > model.today) "未来日期 · 仅供预览，点「今天」返回" else "正在补记 $date，点「今天」返回", "info")
    if (model.today < model.plan.start) Banner("🎯 ${shortDate(model.plan.start)} 正式开营，还有 ${daysBetween(model.today, model.plan.start)} 天 · 想提前开始也行", "info")
    if (CheckinCore.needsBackup(state, System.currentTimeMillis())) {
        Banner("💾 记得备份到手机外 ·", "info") { WebButton("去备份", { model.page = 5 }) }
    }
    val maximum = if (phase.optInt("fs") == 1) 1 else 2
    val met = model.plan.satisfied(date, state)
    when {
        date < model.plan.start && (date >= model.today || state.obj("days").optJSONObject(date) == null) ->
            Banner("计划从 ${model.plan.start} 开始，之前不计欠打卡。", "info")
        model.plan.isMakeup(date, state) -> Banner("🩹 已用补签卡保住这天（本月还剩 ${model.plan.makeupLeft(date.take(7), state)} 张）", "ok")
        met -> Banner("✓ 今日保底已完成 · 继续推进，或安心收工", "ok")
        else -> {
            val text = "⚡ 最低完成线 ${model.plan.minCount(date, state)}/$maximum　" + if (date < model.today) "这天还没达标，可以补记" else "先完成两项，今天就有进步"
            if (date >= model.plan.start && date < model.today && phase.optInt("fs") != 1 && model.plan.makeupLeft(date.take(7), state) > 0)
                Banner(text) { WebButton("🩹 用补签卡", { model.makeup(date) }, enabled = enabled) }
            else Banner(text)
        }
    }
    Section("今日任务 ${tasks.count { model.plan.isDone(record, it.id) }}/${tasks.size}${if (date > model.today) "（未来日期，仅供预览）" else ""}") {
        if (tasks.isEmpty() && date < model.plan.start) Tip("新计划尚未开始；可以先休息或自行准备学习资料。")
        else if (date >= model.plan.start) Tip("两项最低线优先；其余是阶段参考，按每周安排选择，不必每天全部完成。")
        val minimumIds = phase.arr("tasks").values().filterIsInstance<JSONObject>().filter { it.optInt("min") == 1 }.map { it.optString("id") }.toSet()
        tasks.forEach { task ->
            val deletable = task.id.startsWith("c:") || task.id.startsWith("r:")
            TaskRow(task.label, model.plan.isDone(record, task.id), enabled, task.tag, task.id in minimumIds,
                onDelete = if (deletable) ({ deleteTarget = task.id }) else null) {
                model.editDay(date) { model.plan.toggle(it, task.id) }
            }
        }
        AddField("加一条临时任务…", enabled) { name -> model.editDay(date) { day ->
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
        (listOf("西综", "英语", "政治", "其他") + record.obj("sh").namesList()).distinct().forEach { subject ->
            val current = record.obj("sh").optDouble(subject, 0.0)
            // Keep the stored precision in the editable field; only the summary rounds to one decimal.
            Divider()
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(subject, Modifier.weight(1f), fontSize = 14.sp, color = webColors.text)
                WebButton("−", { model.adjustHours(date, subject, -.5) }, Modifier.width(44.dp), enabled,
                    hourButton = true, accessibilityLabel = "减少${subject}半小时")
                AutoSaveInput(model, DraftField.Hours(date, subject), current.toString(), "$subject 小时",
                    Modifier.width(64.dp), enabled = enabled, numeric = true, textAlign = TextAlign.Center)
                WebButton("＋", { model.adjustHours(date, subject, .5) }, Modifier.width(44.dp), enabled,
                    hourButton = true, accessibilityLabel = "增加${subject}半小时")
            }
        }
        Tip("每次增减 0.5 小时，也可直接修改。总时长自动相加。")
        AddField("添加其他科目", enabled) { subject -> model.setHours(date, subject, 0.0) }
    }
    Section("三问复盘 · 写一句也可以") {
        listOf("g" to "今天最大的收获", "s" to "今天卡住的地方", "n" to "明天第一件事").forEachIndexed { index, (field, title) ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            AutoSaveField(model, DraftField.DayReview(date, field), title, record.obj("rw").optString(field), enabled, placeholder = "…")
        }
    }
    var stageExpanded by rememberSaveable { mutableStateOf(false) }
    Section {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable { stageExpanded = !stageExpanded }, verticalAlignment = Alignment.CenterVertically) {
            Text("阶段计划与安装帮助", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = webColors.text)
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
            Spacer(Modifier.height(8.dp))
            Tip("单词已背约1300个：每天复习 + 新词约40个，寒假结束前过完第一轮，3月起转为复习和真题生词。平时可参考英语25–30分钟、西综50–60分钟；寒假每天3–4小时，以西综为主。")
            Tip("平时每天1–2小时偏紧，周末尽量多学1小时；寒假是全年最长的整块时间，生化放在寒假完成。每月底对照章节进度调整，落后时先压缩新内容、保住复习。")
            Tip("本机保存不等于云备份。换手机前请导出 JSON，并保存到网盘或发给自己。")
        }
    }
    deleteTarget?.let { id ->
        ConfirmDialog("删除这条任务？", "只删除 $date 的当天记录，固定任务设置不会改变。",
            { deleteTarget = null }) { model.deleteCustomTask(date, id) }
    }
}

// Web vWeek: date navigation, weekly rate, must-do rows, flexible tasks and Sunday review.
@Composable
internal fun WeekScreen(model: CheckinViewModel) {
    val week = model.week
    val currentWeek = StudyPlan.monday(model.today)
    val record = model.state.obj("weeks").optJSONObject(week) ?: StudyPlan.week()
    val enabled = week <= currentWeek && model.loadError.isEmpty()
    val stats = weekStats(model.plan, model.state, model.today, week)
    val nextWeek = StudyPlan.nextWithinLimit(week, currentWeek, 7)
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
    Section("必须完成（周目标 ×3）") {
        repeat(3) { index ->
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(32.dp).toggleable(record.arr("md").optBoolean(index), enabled = enabled, role = Role.Checkbox,
                    onValueChange = { model.editWeek(week) { it.arr("md").put(index, !it.arr("md").optBoolean(index)) } }), contentAlignment = Alignment.CenterStart) {
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
        WebButton("复制上周目标", { model.copyPreviousWeekGoals(week) }, enabled = enabled)
    }
    Section("弹性任务") {
        if (record.arr("ex").length() == 0) Tip("本周可做可不做的，放这里")
        record.arr("ex").values().forEachIndexed { index, value ->
            val task = value as JSONObject
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TaskRow(task.getString("n"), task.getBoolean("d"), enabled) {
                        model.editWeek(week) { it.arr("ex").getJSONObject(index).put("d", !task.getBoolean("d")) }
                    }
                }
                WebButton("✕", { model.editWeek(week) { it.arr("ex").remove(index) } }, enabled = enabled)
            }
        }
        AddField("加一条弹性任务…", enabled) { name -> model.editWeek(week) { it.arr("ex").put(JSONObject().put("n", name).put("d", false)) } }
    }
    Section("周日复盘（30 分钟）") {
        AutoSaveField(model, DraftField.WeekReview(week), "本周回顾与下周调整", record.optString("review"), enabled, singleLine = false, showLabel = false,
            placeholder = "本周完成度如何？没完成的原因？下周怎么调整？")
    }
    // Web .rep: the report is derived from the records already exposed by the ViewModel.
    Section("自动周报") {
        val context = LocalContext.current
        val completed = (0..2).count { record.arr("md").optBoolean(it) }
        val chapters = model.plan.subjects.mapNotNull { subject ->
            val count = model.plan.chapterDone(subject, model.state)
            if (count == 0) null else "${subject.getString("name")} $count/${subject.arr("ch").length()}章"
        }
        val goalStatus = if (stats.hours >= stats.target) "达标" else "欠 ${hours(stats.target - stats.hours)}h"
        val report = "【考研周报 ${shortDate(week)}~${shortDate(StudyPlan.addDays(week, 6))}】\n" +
            "打卡 ${stats.days}/${stats.elapsed} 天｜时长 ${hours(stats.hours)}h（目标 ${hours(stats.target)}h，$goalStatus）\n" +
            "周目标完成 $completed/3｜周日复盘：${if (record.optString("review").isBlank()) "未填" else "已填"}\n" +
            "章节：${chapters.ifEmpty { listOf("暂无进度") }.joinToString("、")}\n连续打卡 ${model.plan.streak(model.state, model.today)} 天。"
        Text(report, Modifier.fillMaxWidth().background(webColors.bg, RoundedCornerShape(10.dp)).padding(11.dp),
            color = webColors.text, fontSize = 12.5.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(8.dp))
        WebButton("复制周报文字", {
            val copied = runCatching {
                val clipboard = context.getSystemService(ClipboardManager::class.java) ?: error("剪贴板不可用")
                clipboard.setPrimaryClip(ClipData.newPlainText("考研周报", report))
            }.isSuccess
            model.notify(if (copied) "周报已复制" else "复制失败")
        })
    }
}
