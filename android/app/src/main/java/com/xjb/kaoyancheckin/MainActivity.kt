package com.xjb.kaoyancheckin

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme(
                primary = Color(0xFFA8C8FF), secondary = Color(0xFF9ED6CD), surface = Color(0xFF111820), background = Color(0xFF111820)
            ) else lightColorScheme(
                primary = Color(0xFF234E88), secondary = Color(0xFF236A60), surface = Color(0xFFF4F7FC), background = Color(0xFFF4F7FC)
            )
            MaterialTheme(colorScheme = colors) { CheckinApp() }
        }
    }
}

@Composable
private fun CheckinApp(model: CheckinViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.resume() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(model) { model.events.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
    LaunchedEffect(lifecycle) {
        while (true) { delay(30_000); if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) model.resume() }
    }
    val labels = listOf("今日", "进度", "周目标", "统计", "节点", "设置")
    val icons = listOf(Icons.Outlined.Today, Icons.AutoMirrored.Outlined.MenuBook, Icons.Outlined.Checklist, Icons.Outlined.BarChart, Icons.Outlined.Flag, Icons.Outlined.Settings)
    Scaffold(bottomBar = {
        NavigationBar {
            labels.forEachIndexed { index, label ->
                NavigationBarItem(selected = model.page == index, onClick = { model.page = index },
                    icon = { Icon(icons[index], contentDescription = null) }, label = { Text(label, maxLines = 1) })
            }
        }
    }) { insets ->
        if (!model.ready) Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else key(model.page) {
            LazyColumn(Modifier.fillMaxSize().padding(insets).imePadding(), state = rememberLazyListState(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                item {
                    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("考研打卡 / ${labels[model.page]}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("每天留一点证据，慢慢走到考场。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (model.loadError.isNotEmpty()) Section("存档读取异常") {
                            Text(model.loadError, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { model.page = 5 }) { Text("前往设置，导出原始存档或恢复") }
                        }
                        if (!CheckinCore.hasData(model.state) && model.page != 5) Section("从网页导出 JSON 导入") {
                            Text("已有网页记录？先导出备份，在这里延续学习进度。也可以直接开始打卡。")
                            Button(onClick = { model.page = 5 }) { Text("前往设置导入") }
                        }
                        when (model.page) {
                            0 -> TodayScreen(model)
                            1 -> ProgressScreen(model)
                            2 -> WeekScreen(model)
                            3 -> StatsScreen(model)
                            4 -> MilestonesScreen(model)
                            else -> SettingsScreen(model)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth(), elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onChange() }),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SaveField(label: String, saved: String, identity: String, enabled: Boolean = true, numeric: Boolean = false, save: (String) -> Unit) {
    var draft by rememberSaveable(identity, saved) { mutableStateOf(saved) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text(label) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth(), minLines = if (numeric) 1 else 2,
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text))
        if (enabled && draft != saved) TextButton(onClick = { save(draft) }) { Text("保存$label") }
    }
}

@Composable
private fun AddField(label: String, enabled: Boolean = true, add: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text(label) }, enabled = enabled, modifier = Modifier.fillMaxWidth())
    TextButton(enabled = enabled && draft.isNotBlank(), onClick = { add(draft.trim()); draft = "" }) { Text("添加") }
}

@Composable
private fun DateNav(date: String, previous: () -> Unit, next: () -> Unit, current: () -> Unit, currentLabel: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = previous) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "上一段日期") }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(date, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = current) { Text(currentLabel) }
        }
        IconButton(onClick = next) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "下一段日期") }
    }
}

@Composable
private fun TodayScreen(model: CheckinViewModel) {
    val state = model.state
    val date = model.selected
    val record = state.obj("days").optJSONObject(date) ?: StudyPlan.day()
    val phase = model.plan.phase(date, state)
    val enabled = date <= model.today && model.loadError.isEmpty()
    DateNav(date, { model.selected = StudyPlan.addDays(date, -1) }, { model.selected = StudyPlan.addDays(date, 1) }, { model.selected = model.today }, "回到今天")
    if (date > model.today) Text("未来日期只读，可以预览计划。", color = MaterialTheme.colorScheme.secondary)
    Section(phase.getString("name")) {
        Text(phase.getString("desc"))
        val max = if (phase.optInt("fs") == 1) 1 else 2
        val met = model.plan.satisfied(date, state)
        Text(if (model.plan.isMakeup(date, state)) "已补签，保住这一天" else if (met) "最低线已完成" else "最低线 ${model.plan.minCount(date, state)}/$max", fontWeight = FontWeight.Bold)
        LinearProgressIndicator(progress = { model.plan.minCount(date, state).toFloat() / max }, modifier = Modifier.fillMaxWidth())
        if (date >= model.plan.start && date < model.today && !met && phase.optInt("fs") != 1) {
            OutlinedButton(onClick = { model.makeup(date) }, enabled = enabled && model.plan.makeupLeft(date.take(7), state) > 0) {
                Text("用补签卡（本月剩 ${model.plan.makeupLeft(date.take(7), state)} 张）")
            }
        }
        Text("连续 ${model.plan.streak(state, model.today)} 天 · 距初试 ${ChronoUnit.DAYS.between(LocalDate.parse(model.today), LocalDate.parse(model.plan.exam(state)))} 天")
    }
    Section("今日清单") {
        model.plan.tasks(date, state, model.today).forEach { task ->
            CheckRow(task.label, model.plan.isDone(record, task.id), enabled) { model.editDay(date) { model.plan.toggle(it, task.id) } }
        }
        AddField("只在这天添加任务", enabled) { name -> model.editDay(date) { day ->
            require(day.arr("c").values().none { (it as JSONObject).optString("n") == name }) { "任务已存在" }
            day.arr("c").put(JSONObject().put("n", name).put("d", false))
        } }
    }
    Section("学习时长 · 合计 ${record.optDouble("h", 0.0)} 小时") {
        Text("每科 0–24 小时，全天总计不超过 24 小时。保存后计入统计。")
        (listOf("西综", "英语", "政治", "其他") + record.obj("sh").namesList()).distinct().forEach { subject ->
            SaveField("$subject / 小时", record.obj("sh").optDouble(subject, 0.0).toString(), "$date-hours-$subject", enabled, true) { value ->
                model.setHours(date, subject, value.toDoubleOrNull() ?: Double.NaN)
            }
        }
        AddField("添加其他科目", enabled) { subject -> model.setHours(date, subject, 0.0) }
    }
    Section("三问复盘") {
        listOf("g" to "今天最大的收获", "s" to "今天卡住的地方", "n" to "明天第一件事").forEach { (field, title) ->
            SaveField(title, record.obj("rw").optString(field), "$date-review-$field", enabled) { value -> model.editDay(date) { it.obj("rw").put(field, value) } }
        }
    }
}

@Composable
private fun ProgressScreen(model: CheckinViewModel) {
    val enabled = model.loadError.isEmpty()
    model.plan.subjects.forEach { subject ->
        val subjectKey = subject.getString("k")
        val chapters = subject.arr("ch")
        val checks = model.state.obj("ch").arr(subjectKey)
        var expanded by rememberSaveable(subjectKey) { mutableStateOf(false) }
        Section("${subject.getString("name")} · ${checks.values().count { it == true }}/${chapters.length()} 章") {
            Text("${subject.getString("s")} 至 ${subject.getString("e")}")
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起章节" else "展开章节") }
            if (expanded) chapters.values().forEachIndexed { index, chapter ->
                CheckRow(chapter as String, checks.optBoolean(index), enabled) { model.edit { state ->
                    val flags = state.obj("ch").arr(subjectKey)
                    while (flags.length() <= index) flags.put(false)
                    flags.put(index, !flags.optBoolean(index))
                    state.obj("ch").put(subjectKey, flags)
                } }
            }
        }
    }
    Section("模考成绩 / 西综 300 分") {
        var date by rememberSaveable { mutableStateOf(model.today) }
        var name by rememberSaveable { mutableStateOf("") }
        var total by rememberSaveable { mutableStateOf("") }
        OutlinedTextField(date, { date = it }, label = { Text("考试日期 YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth(), enabled = enabled)
        OutlinedTextField(name, { name = it }, label = { Text("试卷名称") }, modifier = Modifier.fillMaxWidth(), enabled = enabled)
        OutlinedTextField(total, { total = it }, label = { Text("总分 0–300") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), enabled = enabled)
        Button(enabled = enabled, onClick = {
            val score = total.toDoubleOrNull()
            if (!CheckinCore.validDate(date) || date > model.today) model.notify("请填写今天或过去的有效考试日期")
            else if (name.length > 200) model.notify("卷子名称请控制在 200 字以内")
            else if (score == null || !score.isFinite() || score !in 0.0..300.0) model.notify("请填 0-300 的总分")
            else { model.edit { it.arr("scores").put(JSONObject().put("id", UUID.randomUUID().toString()).put("d", date).put("n", name).put("tot", score).put("p", JSONObject())) }; total = "" }
        }) { Text("保存模考") }
        model.state.arr("scores").values().reversed().forEach { value ->
            val score = value as JSONObject
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${score.getString("d")}  ${score.optString("n")}")
                    Text("${score.getDouble("tot")} 分", fontWeight = FontWeight.Bold)
                    if (score.obj("p").length() > 0) Text(score.obj("p").namesList().joinToString(" / ") { "$it ${score.obj("p").get(it)}" })
                }
                TextButton(enabled = enabled, onClick = { model.edit { state -> state.put("scores", JSONArray(state.arr("scores").values().filter { (it as JSONObject).getString("id") != score.getString("id") })) } }) { Text("删除") }
            }
        }
    }
}

@Composable
private fun WeekScreen(model: CheckinViewModel) {
    val week = model.week
    val record = model.state.obj("weeks").optJSONObject(week) ?: StudyPlan.week()
    val enabled = week <= StudyPlan.monday(model.today) && model.loadError.isEmpty()
    DateNav(week, { model.week = StudyPlan.addDays(week, -7) }, { model.week = StudyPlan.addDays(week, 7) }, { model.week = StudyPlan.monday(model.today) }, "回到本周")
    Text("周一 $week 至周日 ${StudyPlan.addDays(week, 6)}${if (!enabled) " · 只读" else ""}")
    Section("本周三件必做") {
        repeat(3) { index ->
            CheckRow("目标 ${index + 1} 已完成", record.arr("md").optBoolean(index), enabled) { model.editWeek(week) { it.arr("md").put(index, !it.arr("md").optBoolean(index)) } }
            SaveField("目标 ${index + 1}", record.arr("must").optString(index), "$week-must-$index", enabled) { value -> model.editWeek(week) { it.arr("must").put(index, value) } }
        }
    }
    Section("额外任务") {
        record.arr("ex").values().forEachIndexed { index, value ->
            val task = value as JSONObject
            CheckRow(task.getString("n"), task.getBoolean("d"), enabled) { model.editWeek(week) { it.arr("ex").getJSONObject(index).put("d", !task.getBoolean("d")) } }
            TextButton(enabled = enabled, onClick = { model.editWeek(week) { it.arr("ex").remove(index) } }) { Text("删除这项额外任务") }
        }
        AddField("额外任务", enabled) { name -> model.editWeek(week) { it.arr("ex").put(JSONObject().put("n", name).put("d", false)) } }
    }
    Section("周复盘") {
        SaveField("本周回顾与下周调整", record.optString("review"), "$week-review", enabled) { value -> model.editWeek(week) { it.put("review", value) } }
    }
}

@Composable
private fun StatsScreen(model: CheckinViewModel) {
    val state = model.state
    val dates = state.obj("days").namesList().filter { it <= model.today }
    val hours = dates.sumOf { state.obj("days").obj(it).optDouble("h", 0.0) }
    Section("学习总览") {
        Text("累计 $hours 小时", style = MaterialTheme.typography.headlineSmall)
        val trackedDates = (dates + state.arr("mk").values().map { it as String }).distinct().filter { it <= model.today }
        Text("最低线达标 ${trackedDates.count { model.plan.satisfied(it, state) }} 天 · 连续 ${model.plan.streak(state, model.today)} 天")
        Text("${state.arr("scores").length()} 次模考 · ${state.obj("weeks").length()} 周目标")
        Text("本月补签剩 ${model.plan.makeupLeft(model.today.take(7), state)} 张")
    }
    Section("分科学习时长") {
        val totals = linkedMapOf<String, Double>()
        dates.forEach { date -> state.obj("days").obj(date).obj("sh").let { subjects -> subjects.namesList().forEach { totals[it] = (totals[it] ?: 0.0) + subjects.getDouble(it) } } }
        if (totals.isEmpty()) Text("还没有时长记录，在今日页按科目填写。")
        totals.forEach { (subject, total) ->
            Text("$subject  ${String.format(java.util.Locale.ROOT, "%.2f", total)} 小时")
            LinearProgressIndicator(progress = { if (hours == 0.0) 0f else (total / hours).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
    }
    Section("最近 14 天 / 最低线") {
        (13L downTo 0L).forEach { offset ->
            val date = StudyPlan.addDays(model.today, -offset)
            val met = model.plan.satisfied(date, state)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(date)
                Text(if (model.plan.isMakeup(date, state)) "补签" else if (met) "已达标" else "未达标")
            }
            LinearProgressIndicator(progress = { if (met) 1f else 0f }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MilestonesScreen(model: CheckinViewModel) {
    Section("按实际进度调整") {
        Text("每天 1–2 小时，先保证英语与西综两项最低线。每月核对章节和可投入时间，不必堆积补课。")
        Text("所有报名、考试、出分日期均为规划提醒，尚未官方确认，请以正式公告为准。")
    }
    val phases = model.plan.data.arr("PHASES").values().map { it as JSONObject } + model.plan.phase("2027-12-01", model.state) + model.plan.data.obj("PH5")
    Section("阶段路线") {
        phases.distinctBy { it.getString("id") }.forEach { phase ->
            Text(phase.getString("name"), fontWeight = FontWeight.Bold)
            Text("${phase.optString("s", model.plan.exam(model.state))} — ${phase.optString("e", "2028-08-31")}")
            Text(phase.getString("desc"))
            HorizontalDivider()
        }
    }
    model.plan.milestones.sortedBy { it.getString(0).ifEmpty { model.plan.exam(model.state) } }.forEach { milestone ->
        val date = milestone.getString(0).ifEmpty { model.plan.exam(model.state) }
        Section(milestone.getString(1)) {
            Text(date, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(milestone.getString(2))
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, message: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { confirm(); dismiss() }) { Text("确认") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable
private fun SettingsScreen(model: CheckinViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var pasted by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var backupWritten by rememberSaveable { mutableStateOf(false) }
    val enabled = model.loadError.isEmpty()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot = model.exportSnapshot
        model.exportSnapshot = null
        if (uri != null && snapshot != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法打开目标文件")
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(snapshot) }
            } }.onSuccess { backupWritten = true; model.notify("JSON 已写入，请确认已另存到手机外") }
                .onFailure { model.notify("导出失败：${it.message}") }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val stream = context.contentResolver.openInputStream(uri) ?: error("无法打开文件")
                stream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(8192)
                    val result = StringBuilder()
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) break
                        result.append(buffer, 0, count)
                        require(result.length <= CheckinCore.MAX_TEXT) { "备份超过 5 MB，请检查是否选错文件" }
                    }
                    result.toString()
                }
            } }.onSuccess(model::stage).onFailure { model.notify("无法读取：${it.message}") }
        }
    }
    Section("初试日期") {
        SaveField("初试日期 YYYY-MM-DD", model.plan.exam(model.state), "exam", enabled) { date ->
            if (!CheckinCore.validDate(date)) model.notify("初试日期格式错误") else model.edit { it.obj("settings").put("exam", date) }
        }
        Text("默认 2027-12-25 仅为规划占位，并非官方确认日期。修改会影响倒计时与冲刺区间。")
    }
    Section("固定每日任务") {
        model.state.arr("recur").values().forEach { name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name as String, Modifier.weight(1f))
                TextButton(enabled = enabled, onClick = { model.retireTask(name) }) { Text("停用") }
            }
        }
        AddField("加入每天的任务", enabled) { name -> model.edit {
            require(name.length <= 200) { "任务名请控制在 200 字以内" }
            require(name !in it.arr("recur").values()) { "任务已存在" }
            it.arr("recur").put(name)
        } }
    }
    Section("备份与恢复") {
        Text(if (CheckinCore.needsBackup(model.state, System.currentTimeMillis())) "建议现在备份一次。" else "本机保存不会自动同步到网页，请定期备份。")
        Text("上次确认备份：" + if (model.state.optLong("bk") == 0L) "从未" else java.time.Instant.ofEpochMilli(model.state.getLong("bk")).atZone(CheckinCore.shanghai).toLocalDate().toString())
        Button(onClick = {
            model.exportSnapshot = model.exportText()
            export.launch("kaoyan-checkin-${CheckinCore.today()}.json")
        }) { Text(if (enabled) "导出备份 JSON" else "导出原始存档 JSON") }
        if (backupWritten && enabled) OutlinedButton(onClick = { model.confirmBackup(); backupWritten = false }) { Text("已确认另存到手机外") }
        Text("备份提醒周期")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 14, 30).forEach { days -> FilterChip(selected = model.state.obj("settings").optInt("backupDays") == days,
                onClick = { model.edit { it.obj("settings").put("backupDays", days) } }, enabled = enabled, label = { Text("$days 天") }) }
        }
        HorizontalDivider()
        OutlinedButton(onClick = { pick.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }) { Text("选择网页导出的 JSON 文件") }
        OutlinedTextField(pasted, { pasted = it }, label = { Text("或粘贴备份 JSON") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6)
        TextButton(onClick = { model.stage(pasted) }, enabled = pasted.isNotBlank()) { Text("检查备份内容") }
        model.preview?.let { preview ->
            Text("已检查：${preview.obj("days").length()} 天记录 · ${preview.arr("scores").length()} 次模考 · ${preview.obj("weeks").length()} 周目标", fontWeight = FontWeight.Bold)
            val dates = preview.obj("days").namesList().sorted()
            if (dates.isNotEmpty()) Text("${dates.first()} 至 ${dates.last()}")
            Text("合并：每天、每周取较新记录；章节、成绩等按整份备份时间选择。完整恢复：整份替换。导入前保留一份本机恢复副本。")
            if (!enabled) Text("本机存档异常，导入将使用完整恢复，并保留原始存档。")
            Button(onClick = { confirm = "merge" }) { Text("合并这份备份") }
            OutlinedButton(onClick = { confirm = "replace" }) { Text("完整恢复这份备份") }
        }
        OutlinedButton(onClick = { confirm = "previous" }) { Text("恢复上次导入前的副本") }
    }
    Section("危险操作") {
        Text("清空打卡、章节、成绩、补签与恢复副本；保留固定任务和设置。请先导出备份。")
        OutlinedButton(enabled = enabled, onClick = { confirm = "wipe" }) { Text("清空打卡数据", color = MaterialTheme.colorScheme.error) }
    }
    Text("考研打卡 0.1.0 · 仅本机离线保存 · 兼容网页 schemaVersion 1 / 2", style = MaterialTheme.typography.bodySmall)
    if (confirm.isNotEmpty()) {
        val action = confirm
        val title = when (action) { "merge" -> "确认合并？"; "replace" -> "完整替换当前记录？"; "previous" -> "恢复上次导入前副本？"; else -> "确定清空打卡数据？" }
        ConfirmDialog(title, if (action == "wipe") "清空不可撤销。是否已将 JSON 备份另存到手机外？" else "当前数据会保留为新的本机恢复副本。每次只保留上一份，不是备份历史。",
            { confirm = "" }) { when (action) { "merge", "replace" -> model.import(action); "previous" -> model.restorePrevious(); "wipe" -> model.wipe() } }
    }
}
