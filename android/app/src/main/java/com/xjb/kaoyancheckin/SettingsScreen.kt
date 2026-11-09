package com.xjb.kaoyancheckin

import android.app.DatePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjb.kaoyancheckin.ui.theme.webColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
private fun ConfirmDialog(title: String, message: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { confirm(); dismiss() }) { Text("确认") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

// Web vSet: exam, recurring tasks, makeup cards, backup/recovery and danger controls.
@Composable
internal fun SettingsScreen(model: CheckinViewModel) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
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
        SaveField("初试日期", model.plan.exam(model.state), "exam", enabled, showLabel = false, placeholder = "YYYY-MM-DD",
            trailing = {
                Box(Modifier.size(32.dp).semantics { contentDescription = "选择初试日期" }.clickable(enabled = enabled) {
                focus.clearFocus()
                val value = LocalDate.parse(model.plan.exam(model.state))
                DatePickerDialog(context, { _, year, month, day ->
                    val date = LocalDate.of(year, month + 1, day).toString()
                    model.edit { it.obj("settings").put("exam", date) }
                }, value.year, value.monthValue - 1, value.dayOfMonth).show()
                }, contentAlignment = Alignment.Center) { CalendarIcon() }
            }) { date ->
            if (!CheckinCore.validDate(date)) model.notify("初试日期格式错误") else model.edit { it.obj("settings").put("exam", date) }
        }
        Spacer(Modifier.height(8.dp))
        Tip("默认 2027-12-25 仅为规划占位，并非官方确认日期。请以正式公告为准。修改会影响倒计时与冲刺区间。")
    }
    Section("固定每日任务（自定义）") {
        if (model.state.arr("recur").length() == 0) Tip("没有固定任务。适合放\"跟导师课题推进\"这类长期项。")
        model.state.arr("recur").values().forEach { item ->
            val name = item as String
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), color = webColors.text)
                WebButton("停用", { model.retireTask(name) }, enabled = enabled)
            }
            Divider()
        }
        AddField("任务名…", enabled, "加入每天") { name -> model.edit {
            require(name.length <= 200) { "任务名请控制在 200 字以内" }
            require(name !in it.arr("recur").values()) { "任务已存在" }
            it.arr("recur").put(name)
        } }
    }
    Section("补签卡") {
        val left = model.plan.makeupLeft(model.today.take(7), model.state)
        Tip("每月 2 张。某天漏了最低线，可在「今日」页切到那天点「🩹 用补签卡」，连胜即可保住。\n本月已用 ${2 - left} 张，剩余 $left 张。")
    }
    Section("备份与恢复") {
        val backupDate = model.state.optLong("bk").let { if (it == 0L) "从未备份" else java.time.Instant.ofEpochMilli(it).atZone(CheckinCore.shanghai).toLocalDate().toString() }
        val needsBackup = CheckinCore.needsBackup(model.state, System.currentTimeMillis())
        val c = webColors
        Text(buildAnnotatedString {
            append("数据存在本机。上次备份：")
            if (model.state.optLong("bk") == 0L) withStyle(SpanStyle(color = c.amber, fontWeight = FontWeight.Bold)) { append(backupDate) }
            else append(backupDate)
            append("。")
            if (needsBackup) withStyle(SpanStyle(color = c.amber, fontWeight = FontWeight.Bold)) { append("建议现在备份一次。") }
        }, color = c.sub, fontSize = 13.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(10.dp))
        WebButton(if (enabled) "⬇️ 导出备份（下载 JSON）" else "⬇️ 导出原始存档 JSON", {
            model.exportSnapshot = model.exportText()
            export.launch("kaoyan-checkin-${CheckinCore.today()}.json")
        }, Modifier.fillMaxWidth(), big = true)
        if (backupWritten && enabled) {
            Spacer(Modifier.height(10.dp))
            WebButton("已确认另存到手机外", { model.confirmBackup(); backupWritten = false }, Modifier.fillMaxWidth(), big = true)
        }
        Spacer(Modifier.height(14.dp))
        Text("备份提醒周期", fontSize = 11.5.sp, color = webColors.sub)
        var menu by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxWidth()) {
            val shape = RoundedCornerShape(10.dp)
            Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(shape).background(c.bg).border(1.dp, c.line, shape)
                .clickable(enabled = enabled) { menu = true }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${model.state.obj("settings").optInt("backupDays", 7)} 天", Modifier.weight(1f), color = c.text, fontSize = 16.sp)
                Text("⌄", color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                listOf(7, 14, 30).forEach { days -> DropdownMenuItem(text = { Text("$days 天") }, onClick = {
                    menu = false; model.edit { it.obj("settings").put("backupDays", days) }
                }) }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("从 JSON 文件恢复", fontSize = 11.5.sp, color = webColors.sub)
        WebButton("选择网页导出的 JSON 文件", { pick.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, Modifier.fillMaxWidth(), big = true)
        Spacer(Modifier.height(12.dp))
        Text("也可以粘贴备份文本", color = webColors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        WebInput(pasted, { pasted = it }, "把备份 JSON 粘贴到这里…", Modifier.fillMaxWidth(), singleLine = false, minHeight = 70.dp)
        Spacer(Modifier.height(10.dp))
        WebButton("检查备份内容", { model.stage(pasted) }, Modifier.fillMaxWidth(), enabled = pasted.isNotBlank(), big = true)
        model.preview?.let { preview ->
            Spacer(Modifier.height(10.dp))
            Text("已检查：${preview.obj("days").length()} 天记录 · ${preview.arr("scores").length()} 次模考 · ${preview.obj("weeks").length()} 周目标", fontWeight = FontWeight.Bold, color = webColors.text)
            val dates = preview.obj("days").namesList().sorted()
            if (dates.isNotEmpty()) Tip("${dates.first()} 至 ${dates.last()}")
            Tip("合并：每天、每周取较新记录；章节、成绩等按整份备份时间选择。完整恢复：整份替换。导入前保留一份本机恢复副本。")
            if (!enabled) Tip("本机存档异常，导入将使用完整恢复，并保留原始存档。")
            Spacer(Modifier.height(10.dp))
            WebButton("合并这份备份", { confirm = "merge" }, Modifier.fillMaxWidth(), big = true, primary = true)
            Spacer(Modifier.height(10.dp))
            WebButton("完整恢复这份备份", { confirm = "replace" }, Modifier.fillMaxWidth(), big = true)
        }
        Spacer(Modifier.height(10.dp))
        WebButton("恢复上次导入前的副本", { confirm = "previous" }, Modifier.fillMaxWidth(), big = true)
        Spacer(Modifier.height(18.dp))
        Text("危险操作", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = webColors.text)
        Spacer(Modifier.height(8.dp))
        Tip("清空打卡、章节、成绩、补签与恢复副本；保留固定任务和设置。请先导出备份。")
        Spacer(Modifier.height(10.dp))
        WebButton("清空打卡数据", { confirm = "wipe" }, Modifier.fillMaxWidth(), enabled, big = true, danger = true)
    }
    Section("同步状态") {
        Tip("⚪ 独立模式：数据保存在本机，不会自动同步到网页。换手机或清除数据前，请导出 JSON 并保存到手机外。")
    }
    if (confirm.isNotEmpty()) {
        val action = confirm
        val title = when (action) { "merge" -> "确认合并？"; "replace" -> "完整替换当前记录？"; "previous" -> "恢复上次导入前副本？"; else -> "确定清空打卡数据？" }
        ConfirmDialog(title, if (action == "wipe") "清空不可撤销。是否已将 JSON 备份另存到手机外？" else "当前数据会保留为新的本机恢复副本。每次只保留上一份，不是备份历史。",
            { confirm = "" }) { when (action) { "merge", "replace" -> model.import(action); "previous" -> model.restorePrevious(); "wipe" -> model.wipe() } }
    }
}
