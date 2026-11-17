package com.xjb.kaoyancheckin

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.content.Intent
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
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xjb.kaoyancheckin.ui.theme.WebTheme
import com.xjb.kaoyancheckin.ui.theme.webColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    private var openTodaySignal by mutableIntStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        if (CheckinWidget.isOpenToday(intent)) openTodaySignal++
        val themeFactory = ThemeViewModel.factory(application)
        setContent {
            // Keep all ViewModels outside the theme provider so changing colors preserves drafts and navigation.
            val model: CheckinViewModel = viewModel()
            val updates: UpdateViewModel = viewModel()
            val appearance: ThemeViewModel = viewModel(factory = themeFactory)
            WebTheme(theme = appearance.theme) {
                val colors = webColors
                SideEffect {
                    window.setBackgroundDrawable(ColorDrawable(colors.bg.toArgb()))
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(colors.bg.toArgb(), colors.bg.toArgb()) { colors.dark },
                        navigationBarStyle = SystemBarStyle.auto(colors.card.toArgb(), colors.card.toArgb()) { colors.dark },
                    )
                }
                CheckinApp(openTodaySignal, model, updates, appearance)
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (CheckinWidget.isOpenToday(intent)) openTodaySignal++
    }
}

@Composable
private fun CheckinApp(openTodaySignal: Int, model: CheckinViewModel, updates: UpdateViewModel, appearance: ThemeViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbar = remember { SnackbarHostState() }
    val notices = remember { SnackbarHostState() }
    var onboardingDismissed by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(context, lifecycle, model) {
        fun deliverWidgetNotice() {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                WidgetNotice.take(context)?.let { message ->
                    model.notify(message)
                    CheckinWidget.refreshAll(context)
                }
            }
        }
        val preferences = WidgetNotice.preferences(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == WidgetNotice.MESSAGE) deliverWidgetNotice()
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) deliverWidgetNotice()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        lifecycle.addObserver(observer)
        deliverWidgetNotice()
        onDispose {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
            lifecycle.removeObserver(observer)
        }
    }
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { model.resume(); model.refresh(); updates.onResume() }
                Lifecycle.Event.ON_PAUSE -> model.flushDrafts()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { model.flushDrafts(); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(openTodaySignal) {
        if (openTodaySignal > 0) { model.page = 0; model.selected = model.currentDay() }
    }
    UpdateDialog(updates, model::notify)
    LaunchedEffect(model) { model.events.collect { notices.showSnackbar(it) } }
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow { model.pendingUndo }.collectLatest { step ->
                if (step == null) return@collectLatest
                val remaining = UNDO_WINDOW_MS - (System.currentTimeMillis() - step.at)
                if (remaining <= 0 || remaining > UNDO_WINDOW_MS) {
                    model.dismissUndo(step.token)
                    return@collectLatest
                }
                val result = withTimeoutOrNull(minOf(6_000L, remaining)) {
                    snackbar.showSnackbar(step.message, actionLabel = "撤销", duration = SnackbarDuration.Indefinite)
                }
                if (result == SnackbarResult.ActionPerformed) model.undoTask(step)
                model.dismissUndo(step.token)
            }
        }
    }
    LaunchedEffect(lifecycle) {
        while (true) {
            delay(CheckinCore.todayRefreshDelayMillis())
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) model.resume()
        }
    }
    val c = webColors
    Column(Modifier.fillMaxSize().background(c.bg).imePadding()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        Header(model)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (!model.ready) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else key(model.page, openTodaySignal) {
                var planSubPage by rememberSaveable { mutableStateOf(0) }
                var reviewSubPage by rememberSaveable { mutableStateOf(0) }
                Column(Modifier.widthIn(max = 540.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (model.loadError.isNotEmpty()) Section("存档读取异常") {
                        Text(model.loadError, color = c.red)
                        WebButton("前往设置，导出原始存档或恢复", { model.page = 3 })
                    }
                    if (!CheckinCore.hasData(model.state) && model.page != 3 && !onboardingDismissed) Section {
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("开始使用", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = c.sub)
                            Text("✕", Modifier.clip(androidx.compose.foundation.shape.CircleShape)
                                .clickable { onboardingDismissed = true }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                color = c.sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WebButton("导入网页备份", { model.page = 3 }, Modifier.weight(1f))
                            WebButton("直接开始打卡", {
                                onboardingDismissed = true
                                model.page = 0
                            }, Modifier.weight(1f), primary = true)
                        }
                    }
                    when (model.page) {
                        0 -> TodayScreen(model)
                        1 -> {
                            SegmentedBar(listOf("本周目标", "阶段节点"), planSubPage) { planSubPage = it }
                            if (planSubPage == 0) WeekScreen(model) else MilestonesScreen(model)
                        }
                        2 -> {
                            SegmentedBar(listOf("进度预测", "统计趋势"), reviewSubPage) { reviewSubPage = it }
                            if (reviewSubPage == 0) ProgressScreen(model) else StatsScreen(model)
                        }
                        else -> SettingsScreen(model, updates, appearance)
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).widthIn(max = 540.dp).fillMaxWidth()) {
                UndoSnackbarHost(notices)
                UndoSnackbarHost(snackbar)
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

@Composable
internal fun TabBar(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("今日", "计划", "复盘", "设置")
    val icons = listOf(R.drawable.tab_today, R.drawable.tab_week, R.drawable.tab_progress, R.drawable.tab_settings)
    val c = webColors
    Column(Modifier.fillMaxWidth().background(c.card), horizontalAlignment = Alignment.CenterHorizontally) {
        Divider()
        Row(Modifier.widthIn(max = 540.dp).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
            labels.forEachIndexed { index, label ->
                val active = selected == index
                Column(Modifier.weight(1f).heightIn(min = 58.dp).selectable(active, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(top = 6.dp, bottom = 5.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
                    Box(Modifier.width(48.dp).height(28.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (active) c.chipBlue else androidx.compose.ui.graphics.Color.Transparent),
                        contentAlignment = Alignment.Center) {
                        Icon(painterResource(icons[index]), contentDescription = null, Modifier.size(20.dp),
                            tint = if (active) c.accent else c.sub)
                    }
                    Text(label, color = if (active) c.accent else c.sub, fontSize = 11.sp,
                        lineHeight = 14.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}
