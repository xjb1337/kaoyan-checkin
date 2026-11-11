package com.xjb.kaoyancheckin

import android.app.DatePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjb.kaoyancheckin.ui.theme.webColors
import java.time.LocalDate
import java.util.Locale

internal fun hours(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
internal fun shortDate(date: String): String = date.substring(5).replace('-', '/')
internal fun daysBetween(from: String, to: String): Long = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to))

// Web .card / h3: shared surface, hairline, shadow, and section heading.
@Composable
internal fun Section(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = webColors
    Column(
        Modifier.fillMaxWidth()
            .shadow(if (c.dark) 0.dp else 1.dp, RoundedCornerShape(16.dp))
            .background(c.card, RoundedCornerShape(16.dp))
            .border(1.dp, c.line, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.sub)
            Spacer(Modifier.height(10.dp))
        }
        content()
    }
}

@Composable
internal fun Tip(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = webColors.sub)
}

@Composable
internal fun Divider() { Spacer(Modifier.fillMaxWidth().height(1.dp).background(webColors.line)) }

// Web .btn and .bigbtn; disabled controls keep their size.
@Composable
internal fun WebButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    big: Boolean = false, primary: Boolean = false, danger: Boolean = false, hourButton: Boolean = false,
    accessibilityLabel: String? = null,
) {
    val c = webColors
    val bg = when { danger -> Color(0xFFEF4444).copy(alpha = .12f); primary -> c.blue; big || hourButton -> c.bg; else -> c.card }
    val fg = when { danger -> c.red; primary -> Color.White; else -> c.text }
    val shape = RoundedCornerShape(if (big || hourButton) 12.dp else 10.dp)
    Box(modifier.defaultMinSize(minWidth = 44.dp, minHeight = if (big) 46.dp else 44.dp)
        .alpha(if (enabled) 1f else .35f).clip(shape).background(bg)
        .then(if (!primary && !danger) Modifier.border(1.dp, c.line, shape) else Modifier)
        .semantics { if (accessibilityLabel != null) contentDescription = accessibilityLabel }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = if (big) 12.dp else 10.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, color = fg, fontSize = if (hourButton) 20.sp else if (big) 15.sp else 13.sp,
            lineHeight = 20.sp, fontWeight = if (big) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center)
    }
}

// Web .ck. The containing row carries the toggle semantics and a generous touch target.
@Composable
internal fun RoundCheck(checked: Boolean) {
    val c = webColors
    Canvas(Modifier.size(22.dp)) {
        val stroke = 2.dp.toPx()
        drawCircle(if (checked) c.blue else Color.Transparent)
        drawCircle(if (checked && !c.dark) c.blue else if (checked) c.accent else Color(0xFFCBD5E1), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        if (checked) {
            drawLine(Color.White, Offset(size.width * .25f, size.height * .52f), Offset(size.width * .43f, size.height * .70f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(size.width * .43f, size.height * .70f), Offset(size.width * .77f, size.height * .31f), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

@Composable
internal fun Chip(text: String, color: Color, background: Color, modifier: Modifier = Modifier) {
    Text(text, modifier.clip(CircleShape).background(background).padding(horizontal = 8.dp, vertical = 2.dp),
        color = color, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1)
}

@Composable
internal fun TaskRow(label: String, checked: Boolean, enabled: Boolean = true, tag: String? = null, bold: Boolean = false,
                     onDelete: (() -> Unit)? = null, onChange: () -> Unit) {
    val c = webColors
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 58.dp)
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onChange() })
            .padding(horizontal = 2.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            RoundCheck(checked)
            Text(label, Modifier.weight(1f), fontSize = 15.sp, lineHeight = 22.sp,
                fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                color = if (checked) c.sub else c.text,
                textDecoration = if (checked) TextDecoration.LineThrough else TextDecoration.None)
            if (tag != null) {
                val (fg, bg) = when (tag) {
                    "西综" -> c.blueTag to c.chipBlue
                    "英语" -> c.greenTag to c.chipGreen
                    "政治" -> c.orangeTag to c.chipOrange
                    "复试" -> c.redTag to c.chipRed
                    else -> c.purpleTag to c.chipPurple
                }
                Chip(tag, fg, bg)
            }
        }
        if (onDelete != null) {
            WebButton("✕", onDelete, Modifier.padding(start = 4.dp, end = 2.dp), enabled = enabled,
                accessibilityLabel = "删除$label")
        }
    }
    Divider()
}

// Web input/textarea; callers retain the existing explicit-save behavior.
@Composable
internal fun WebInput(
    value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, numeric: Boolean = false, singleLine: Boolean = true,
    minHeight: androidx.compose.ui.unit.Dp = 44.dp, textAlign: TextAlign = TextAlign.Start,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = webColors
    val focus = LocalFocusManager.current
    BasicTextField(value = value, onValueChange = onValueChange, enabled = enabled, singleLine = singleLine,
        modifier = modifier.heightIn(min = minHeight).semantics { contentDescription = placeholder },
        textStyle = androidx.compose.ui.text.TextStyle(color = c.text, fontSize = 16.sp, lineHeight = 23.sp, textAlign = textAlign),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
            imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        decorationBox = { inner ->
            Row(Modifier.fillMaxWidth().heightIn(min = minHeight).clip(RoundedCornerShape(10.dp)).background(c.bg)
                .border(1.dp, c.line, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = if (textAlign == TextAlign.Center) Alignment.Center else Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, fontSize = 16.sp, lineHeight = 23.sp, color = c.sub.copy(alpha = .8f))
                    inner()
                }
                trailing?.invoke()
            }
        })
}

@Composable
internal fun SaveField(label: String, saved: String, identity: String, enabled: Boolean = true, numeric: Boolean = false,
    singleLine: Boolean = true, showLabel: Boolean = true, placeholder: String = label,
    trailing: (@Composable () -> Unit)? = null, save: (String) -> Unit) {
    var draft by rememberSaveable(identity, saved) { mutableStateOf(saved) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showLabel) Text(label, fontSize = 11.5.sp, color = webColors.sub)
        WebInput(draft, { draft = it }, placeholder, Modifier.fillMaxWidth(), enabled, numeric, singleLine,
            minHeight = if (singleLine) 44.dp else 70.dp, trailing = trailing)
        if (enabled && draft != saved) WebButton("保存$label", { save(draft) })
    }
}

@Composable
internal fun AddField(placeholder: String, enabled: Boolean = true, button: String = "添加", add: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        WebInput(draft, { draft = it }, placeholder, Modifier.weight(1f), enabled = enabled)
        WebButton(button, enabled = enabled && draft.isNotBlank(), onClick = { add(draft.trim()); draft = "" })
    }
}

// Web .daynav: date picker is native while keeping the web row's appearance.
@Composable
internal fun DateNav(date: String, previous: () -> Unit, next: () -> Unit, current: () -> Unit,
    currentLabel: String, isCurrent: Boolean, datePicker: Boolean, canGoNext: Boolean = true, maxDate: String? = null,
    onDate: (String) -> Unit = {}) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        WebButton("‹", previous, Modifier.width(44.dp))
        if (datePicker) {
            val value = LocalDate.parse(date)
            val limit = maxDate?.let { LocalDate.parse(it) }
            val pickerDate = if (limit != null && value > limit) limit else value
            Row(Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
                .background(webColors.card).border(1.dp, webColors.line, RoundedCornerShape(10.dp))
                .clickable {
                    val dialog = DatePickerDialog(context, { _, year, month, day ->
                        val picked = LocalDate.of(year, month + 1, day)
                        if (limit == null || picked <= limit) onDate(picked.toString())
                    }, pickerDate.year, pickerDate.monthValue - 1, pickerDate.dayOfMonth)
                    if (limit != null) {
                        val maximum = java.util.Calendar.getInstance().apply {
                            clear()
                            set(limit.year, limit.monthValue - 1, limit.dayOfMonth)
                        }
                        dialog.datePicker.maxDate = maximum.timeInMillis
                    }
                    dialog.show()
                }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${shortDate(date)}/${date.take(4)}", Modifier.weight(1f), textAlign = TextAlign.Center,
                    color = webColors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                CalendarIcon()
            }
        } else {
            Text("${shortDate(date)} ~ ${shortDate(StudyPlan.addDays(date, 6))}", Modifier.widthIn(min = 130.dp),
                textAlign = TextAlign.Center, color = webColors.text, fontSize = 15.sp, maxLines = 1)
        }
        WebButton("›", { if (canGoNext) next() }, Modifier.width(44.dp), enabled = canGoNext)
        WebButton(currentLabel, current, enabled = !isCurrent)
    }
}

@Composable
internal fun ConfirmDialog(title: String, message: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { confirm(); dismiss() }) { Text("确认") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable
internal fun CalendarIcon(modifier: Modifier = Modifier) {
    val color = webColors.text
    Canvas(modifier.size(18.dp)) {
        val stroke = 1.5.dp.toPx()
        val left = size.width * .14f
        val top = size.height * .19f
        val right = size.width * .86f
        val bottom = size.height * .88f
        drawRoundRect(color, topLeft = Offset(left, top), size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawLine(color, Offset(left, size.height * .38f), Offset(right, size.height * .38f), stroke)
        drawLine(color, Offset(size.width * .32f, size.height * .08f), Offset(size.width * .32f, size.height * .28f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * .68f, size.height * .08f), Offset(size.width * .68f, size.height * .28f), stroke, cap = StrokeCap.Round)
    }
}

@Composable
internal fun Banner(text: String, type: String = "warn", action: (@Composable RowScope.() -> Unit)? = null) {
    val c = webColors
    val (fg, bg) = when (type) {
        "info" -> c.accent to c.blue.copy(alpha = .12f)
        "ok" -> c.green to Color(0xFF16A34A).copy(alpha = .14f)
        else -> c.amber to c.orange.copy(alpha = .12f)
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, Modifier.weight(1f), color = fg, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
        action?.invoke(this)
    }
}

@Composable
internal fun ProgressBar(progress: Float, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 6.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(webColors.line)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f))
            .background(Brush.horizontalGradient(listOf(Color(0xFF2563EB), Color(0xFF16A34A))), CircleShape))
    }
}

@Composable
internal fun BarRow(label: String, value: String, progress: Float, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(44.dp), color = webColors.sub, fontSize = 12.5.sp)
        Box(Modifier.weight(1f).height(10.dp).clip(CircleShape).background(webColors.line)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(color, CircleShape))
        }
        Text(value, Modifier.width(46.dp), textAlign = TextAlign.End, color = webColors.sub, fontSize = 11.5.sp, maxLines = 1)
    }
}
