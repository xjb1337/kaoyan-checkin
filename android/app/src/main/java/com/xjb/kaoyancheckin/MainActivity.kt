package com.xjb.kaoyancheckin

import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjb.kaoyancheckin.ui.theme.WebTheme
import com.xjb.kaoyancheckin.ui.theme.webColors
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        setContent { WebTheme { CheckinApp() } }
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
    val c = webColors
    Column(Modifier.fillMaxSize().background(c.bg).imePadding()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        Header(model)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (!model.ready) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else key(model.page) {
                Column(Modifier.widthIn(max = 540.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (model.loadError.isNotEmpty()) Section("存档读取异常") {
                        Text(model.loadError, color = c.red)
                        WebButton("前往设置，导出原始存档或恢复", { model.page = 5 })
                    }
                    if (!CheckinCore.hasData(model.state) && model.page != 5) Section("从网页导出 JSON 导入") {
                        Tip("已有网页记录？先导出备份，在这里延续学习进度。也可以直接开始打卡。")
                        Spacer(Modifier.height(8.dp))
                        WebButton("前往设置导入", { model.page = 5 })
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
        TabBar(model.page) { model.page = it }
    }
}

// Web .hd: fixed header; the top inset is painted with the same page background.
@Composable
private fun Header(model: CheckinViewModel) {
    val c = webColors
    val phase = if (!model.ready) "" else if (model.today < model.plan.start) "${model.plan.start.substring(5, 7).toInt()}月${model.plan.start.substring(8).toInt()}日开始" else model.plan.phase(model.today, model.state).getString("name")
    val remaining = if (model.ready) daysBetween(model.today, model.plan.exam(model.state)) else 0
    Column(Modifier.fillMaxWidth().background(c.bg)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.widthIn(max = 540.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("考研打卡 · 2028", style = MaterialTheme.typography.titleLarge, color = c.text, maxLines = 1)
                Text(phase, style = MaterialTheme.typography.bodySmall, color = c.sub, maxLines = 1)
            }
            Text("本机记录", fontSize = 11.sp, color = c.sub, maxLines = 1)
            Text(if (remaining >= 0) "距初试 $remaining 天" else "初试已结束",
                Modifier.background(c.blue, androidx.compose.foundation.shape.CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
                color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Divider()
    }
}

// Web .tabbar / .tb: six equal emoji tabs without a Material indicator.
@Composable
private fun TabBar(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("今日", "进度", "周目标", "统计", "节点", "设置")
    val emoji = listOf("📋", "📈", "🎯", "📊", "🚩", "⚙️")
    val c = webColors
    Column(Modifier.fillMaxWidth().background(c.card), horizontalAlignment = Alignment.CenterHorizontally) {
        Divider()
        Row(Modifier.widthIn(max = 540.dp).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
            labels.forEachIndexed { index, label ->
                Column(Modifier.weight(1f).heightIn(min = 54.dp).selectable(selected == index, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(top = 7.dp, bottom = 5.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically)) {
                    Text(emoji[index], fontSize = 18.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
                    Text(label, color = if (selected == index) c.accent else c.sub, fontSize = 11.sp,
                        lineHeight = 16.sp, fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
    }
}
