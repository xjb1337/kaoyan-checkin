package com.xjb.kaoyancheckin

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
        if (savedInstanceState == null && CheckinWidget.isOpenToday(intent)) {
            openTodaySignal++
            intent?.action = null
        }
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
        if (CheckinWidget.isOpenToday(intent)) {
            openTodaySignal++
            intent.action = null
        }
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
    DisposableEffect(lifecycle, model, context) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    model.resume()
                    model.refresh()
                    updates.onResume()
                    runCatching { CheckinWidget.refreshAll(context) }
                }
                Lifecycle.Event.ON_PAUSE -> model.flushDrafts()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { model.flushDrafts(); lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = model.page != 0 || model.selected != model.today) {
        if (model.page != 0) model.page = 0 else model.selected = model.today
    }
    LaunchedEffect(openTodaySignal) {
        if (openTodaySignal > 0) { model.page = 0; model.selected = model.currentDay() }
    }
    UpdateDialog(updates, model::notify)
    LaunchedEffect(model) { model.events.collectLatest { notices.showSnackbar(it) } }
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
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(CheckinCore.todayRefreshDelayMillis())
                model.resume()
            }
        }
    }
    val c = webColors
    var planSubPage by rememberSaveable { mutableIntStateOf(0) }
    var reviewSubPage by rememberSaveable { mutableIntStateOf(0) }
    val openStudyPlan = {
        planSubPage = 2
        model.page = 1
    }
    val hasData = remember(model.state) { CheckinCore.hasData(model.state) }
    Column(Modifier.fillMaxSize().background(c.bg).imePadding()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        Header(model)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (!model.ready) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else {
                val scrollKey = when (model.page) {
                    1 -> "plan-$planSubPage"
                    2 -> "review-$reviewSubPage"
                    else -> "tab-${model.page}"
                }
                val scrollState = key(scrollKey, openTodaySignal) { rememberScrollState() }
                Column(Modifier.widthIn(max = 540.dp).fillMaxWidth().verticalScroll(scrollState)
                    .padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (model.loadError.isNotEmpty()) Section("存档读取异常") {
                        Text(model.loadError, color = c.red)
                        WebButton("前往设置，导出原始存档或恢复", { model.page = 3 })
                    }
                    if (!hasData && model.page != 3 && !onboardingDismissed) Section {
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("开始使用", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = c.sub)
                            Box(
                                Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .clickable(role = Role.Button) { onboardingDismissed = true }
                                    .semantics { contentDescription = "关闭引导" },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("✕", color = c.sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
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
                        0 -> TodayScreen(model, openStudyPlan) {
                            reviewSubPage = 0
                            model.page = 2
                        }
                        1 -> {
                            SegmentedBar(listOf("本周目标", "阶段节点", "学习计划"), planSubPage) { planSubPage = it }
                            when (planSubPage) {
                                0 -> WeekScreen(model)
                                1 -> MilestonesScreen(model)
                                else -> SubjectPlanScreen(model)
                            }
                        }
                        2 -> {
                            SegmentedBar(listOf("进度预测", "统计趋势"), reviewSubPage) { reviewSubPage = it }
                            if (reviewSubPage == 0) ProgressScreen(model, openStudyPlan) else StatsScreen(model)
                        }
                        else -> SettingsScreen(model, updates, appearance)
                    }
                }
            }
            val hasSnackbar = notices.currentSnackbarData != null || snackbar.currentSnackbarData != null
            Column(
                Modifier.align(Alignment.BottomCenter).widthIn(max = 540.dp).fillMaxWidth()
                    .then(
                        if (hasSnackbar) {
                            Modifier.background(
                                Brush.verticalGradient(
                                    0f to c.bg.copy(alpha = 0f),
                                    0.22f to c.bg.copy(alpha = 0.88f),
                                    0.50f to c.bg,
                                    1f to c.bg,
                                ),
                            ).padding(top = 24.dp)
                        } else {
                            Modifier
                        }
                    ),
            ) {
                UndoSnackbarHost(notices, showScrim = false)
                UndoSnackbarHost(snackbar, showScrim = false)
            }
        }
        TabBar(model.page) { model.page = it }
    }
}

// Web .hd: fixed header; the top inset is painted with the same page background.
@Composable
private fun Header(model: CheckinViewModel) {
    val c = webColors
    val remaining = remember(model.ready, model.today, model.state) {
        if (model.ready) daysBetween(model.today, model.plan.exam(model.state)) else 0
    }
    val examYear = remember(model.ready, model.state) {
        if (model.ready) model.plan.exam(model.state).take(4) else ""
    }
    Column(Modifier.fillMaxWidth().background(c.bg)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.widthIn(max = 540.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (examYear.isNotEmpty()) "考研打卡 · $examYear" else "考研打卡",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = c.text,
            )
            if (model.ready) {
                Text(if (remaining >= 0) "距初试 $remaining 天" else "初试已结束",
                    Modifier.widthIn(max = 140.dp).background(c.blue, androidx.compose.foundation.shape.CircleShape)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
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
