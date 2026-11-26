package com.xjb.kaoyancheckin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjb.kaoyancheckin.ui.theme.webColors
import org.json.JSONObject

@Composable
internal fun SubjectPlanScreen(model: CheckinViewModel) {
    val c = webColors
    val state = model.state
    val plan = model.plan
    val today = model.today
    val context = LocalContext.current
    val focus = plan.focusSubject(today, state)
    val focusKey = focus?.getString("k")
    val enabled = model.loadError.isEmpty()
    val canSwitch = enabled && today >= plan.start && today < plan.exam(state)
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var action by rememberSaveable { mutableStateOf("options") }

    Section("当前主攻") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(focus?.getString("name") ?: "按原计划", color = c.text, style = MaterialTheme.typography.titleLarge)
            if (focus != null) {
                val done = plan.chapterDone(focus, state)
                val total = focus.arr("ch").length()
                Tip(if (done == total) "第一轮已完成" else "$done/$total 章")
                LinearProgressIndicator(
                    progress = { done.toFloat() / total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.line,
                )
                WebButton("切回原计划", { model.selectStudyFocus(null) }, Modifier.heightIn(min = 48.dp), enabled = canSwitch)
            } else {
                Tip(plan.phase(today, state).getString("name"))
            }
        }
    }
    val progress = remember(plan, state) {
        plan.subjects.map { plan.chapterDone(it, state) to it.arr("ch").length() }
    }
    val completed = progress.count { it.first == it.second }
    Section("西综第一轮", badge = "$completed/${plan.subjects.size} 科完成") {
        plan.subjects.forEachIndexed { index, subject ->
            if (index > 0) Divider()
            val key = subject.getString("k")
            val window = plan.subjectWindow(subject, state)
            val (done, total) = progress[index]
            Row(
                Modifier.fillMaxWidth().heightIn(min = 68.dp).clickable(enabled = enabled, role = Role.Button, onClickLabel = "查看科目操作") {
                    selectedKey = key
                    action = "options"
                }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(subject.getString("name"), color = c.text, fontWeight = FontWeight.SemiBold)
                    Tip("${window.getString("s")} — ${window.getString("e")}")
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    when {
                        done == total -> Chip("已完成", c.green, c.chipGreen)
                        key == focusKey -> Chip("主攻中", c.accent, c.chipBlue)
                    }
                    Tip("$done/$total 章")
                }
                Text("›", Modifier.clearAndSetSemantics {}, color = c.sub, style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    val subject = plan.subjects.firstOrNull { it.getString("k") == selectedKey } ?: return
    val key = subject.getString("k")
    val name = subject.getString("name")
    val dismiss = { selectedKey = null }
    val done = plan.chapterDone(subject, state)
    val total = subject.arr("ch").length()
    val completedDate = state.obj("ce").optString(key)
    val canFocus = canSwitch && subject.has("taskId") && key != focusKey && done < total
    when (action) {
        "dates" -> SubjectDatesDialog(plan.subjectWindow(subject, state), { action = "options" }) { start, end ->
            model.edit { setSubjectWindow(it, key, start, end) }
            dismiss()
        }
        "complete" -> ConfirmDialog("标记${name}第一轮完成？", "将勾选${name}的全部章节。", { action = "options" }) {
            model.edit { completeFirstRound(it, subject) }
        }
        else -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("$name · $done/$total 章") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canFocus) {
                        WebButton("设为当前主攻", {
                            model.selectStudyFocus(key)
                            dismiss()
                        }, Modifier.fillMaxWidth().heightIn(min = 48.dp), primary = true)
                    }
                    WebButton("调整学习日期", { action = "dates" }, Modifier.fillMaxWidth().heightIn(min = 48.dp))
                    if (done < total) {
                        WebButton("标记第一轮完成", { action = "complete" }, Modifier.fillMaxWidth().heightIn(min = 48.dp))
                    } else {
                        WebButton(
                            if (completedDate.isEmpty()) "补录完成日期" else "完成日期 $completedDate",
                            {
                                showDatePicker(context, completedDate.ifEmpty { today }, today) { date ->
                                    model.edit { setChapterCompletedDate(it, key, date) }
                                }
                            },
                            Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } },
        )
    }
}

@Composable
private fun SubjectDatesDialog(subject: JSONObject, dismiss: () -> Unit, save: (String, String) -> Unit) {
    val context = LocalContext.current
    var start by rememberSaveable(subject.getString("k")) { mutableStateOf(subject.getString("s")) }
    var end by rememberSaveable(subject.getString("k")) { mutableStateOf(subject.getString("e")) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("${subject.getString("name")}学习日期") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WebButton("开始日期 $start", { showDatePicker(context, start) { start = it } }, Modifier.fillMaxWidth().heightIn(min = 48.dp))
                WebButton("目标完成日期 $end", { showDatePicker(context, end) { end = it } }, Modifier.fillMaxWidth().heightIn(min = 48.dp))
                if (start > end) Text("目标完成日期不能早于开始日期", color = webColors.red)
            }
        },
        confirmButton = { TextButton(enabled = start <= end, onClick = { save(start, end) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } },
    )
}
