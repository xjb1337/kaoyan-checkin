package com.xjb.kaoyancheckin

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.xjb.kaoyancheckin.ui.theme.AppTheme
import com.xjb.kaoyancheckin.ui.theme.paletteFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Instant

private const val TOGGLE = "com.xjb.kaoyancheckin.WIDGET_TOGGLE"
private const val MIDNIGHT = "com.xjb.kaoyancheckin.WIDGET_MIDNIGHT"
private const val OPEN_TODAY = "com.xjb.kaoyancheckin.OPEN_TODAY"

internal object WidgetRefresh {
    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, WidgetClockReceiver::class.java).setAction(MIDNIGHT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(context: Context) {
        if (AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, CheckinWidget::class.java)).isEmpty()) return
        val alarm = context.getSystemService(AlarmManager::class.java)
        val whenMillis = nextShanghaiMidnight(Instant.now()).toEpochMilli()
        try {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, alarmIntent(context))
        } catch (_: SecurityException) {
            // Devices may deny exact alarms; still request the closest available wakeup.
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, alarmIntent(context))
        }
    }

    fun cancel(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
}

class CheckinWidget : AppWidgetProvider() {
    override fun onEnabled(context: Context) { WidgetRefresh.schedule(context) }
    override fun onDisabled(context: Context) { WidgetRefresh.cancel(context) }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        runCatching { WidgetRefresh.schedule(context) }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { render(context, manager, ids) } finally { pending.finish() }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        onUpdate(context, manager, intArrayOf(id))
    }

    companion object {
        private val renderMutex = Mutex()

        fun refreshAll(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, CheckinWidget::class.java))
            if (ids.isNotEmpty()) app.sendBroadcast(Intent(app, CheckinWidget::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
        }

        internal suspend fun render(context: Context, manager: AppWidgetManager, ids: IntArray) = renderMutex.withLock {
            val snapshot = runCatching {
                val raw = CheckinRepository.get(context).read()
                val state = if (raw == null) CheckinCore.emptyState() else CheckinCore.parseBackupDocument(raw)
                val plan = StudyPlan(JSONObject(context.assets.open("plan.json").bufferedReader().use { it.readText() }))
                widgetSnapshot(plan, state, CheckinCore.today())
            }.getOrNull()
            ids.forEach { id ->
                val options = manager.getAppWidgetOptions(id)
                manager.updateAppWidget(id, views(context, id, snapshot,
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).takeIf { it > 0 } ?: 250,
                    options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110).takeIf { it > 0 } ?: 110))
            }
        }

        internal fun views(context: Context, widgetId: Int, snapshot: WidgetSnapshot?, width: Int, height: Int): RemoteViews {
            val theme = ThemeRepository(context).load()
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            // Distinct host layout IDs force reinflation when a live process observes a night-mode change.
            // Each host also carries both palettes; its resource-selected branch works after the provider
            // process is killed and the launcher later reinflates the saved RemoteViews.
            val host = RemoteViews(context.packageName, if (night) R.layout.checkin_widget_host_dark else R.layout.checkin_widget_host_light)
            host.removeAllViews(R.id.widget_light)
            host.removeAllViews(R.id.widget_dark)
            host.addView(R.id.widget_light, themedViews(context, widgetId, snapshot, width, height, theme, false))
            host.addView(R.id.widget_dark, themedViews(context, widgetId, snapshot, width, height, theme, true))
            return host
        }

        private fun themedViews(
            context: Context, widgetId: Int, snapshot: WidgetSnapshot?, width: Int, height: Int,
            theme: AppTheme, dark: Boolean,
        ): RemoteViews {
            val c = paletteFor(theme, dark)
            val density = context.resources.displayMetrics.density
            val layout = widgetLayout(width, height, snapshot?.required ?: 2)
            val status = widgetStatus(snapshot)
            val views = RemoteViews(context.packageName, R.layout.checkin_widget)
            val open = Intent(context, MainActivity::class.java).setAction(OPEN_TODAY)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val openIntent = PendingIntent.getActivity(context, widgetId, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, openIntent)
            views.setImageViewBitmap(R.id.widget_backdrop, WidgetArtwork.backdrop(width, height, density, c))
            views.setImageViewBitmap(R.id.widget_header_bg, WidgetArtwork.header(width, density, c, status, snapshot?.satisfied == true))
            views.setImageViewBitmap(R.id.widget_streak_bg, WidgetArtwork.badge(density, c))
            views.setTextViewText(R.id.widget_streak, snapshot?.let { "连续 ${it.streak} 天" } ?: "打开应用")
            views.setTextViewText(R.id.widget_summary, status.summary)
            views.setTextColor(R.id.widget_title, c.text.toArgb())
            val summaryColor = if (snapshot?.satisfied == true) {
                if (dark) c.greenTag else Color(0xFF047857)
            } else c.text
            views.setTextColor(R.id.widget_summary, summaryColor.toArgb())
            views.setTextColor(R.id.widget_streak, c.accent.toArgb())
            views.setTextColor(R.id.widget_empty, c.sub.toArgb())
            views.setViewVisibility(R.id.widget_title, if (layout.showTitle) View.VISIBLE else View.GONE)
            views.removeAllViews(R.id.widget_tasks)
            val visibleTasks = if (layout.compact) emptyList() else snapshot?.tasks.orEmpty().take(layout.columns * layout.maxRows)
            views.setViewVisibility(R.id.widget_tasks, if (visibleTasks.isNotEmpty()) View.VISIBLE else View.GONE)
            val showEmpty = !layout.compact && visibleTasks.isEmpty()
            views.setViewVisibility(R.id.widget_empty, if (showEmpty) View.VISIBLE else View.GONE)
            if (showEmpty) views.setTextViewText(R.id.widget_empty, when {
                snapshot == null -> "点击打开应用处理"
                snapshot.beforePlan -> "计划开始后显示今日任务"
                else -> "打开应用查看今日任务"
            })
            if (snapshot != null) {
                val checked = WidgetArtwork.check(density, c, true)
                val unchecked = WidgetArtwork.check(density, c, false)
                visibleTasks.chunked(layout.columns).forEach { tasks ->
                    val row = RemoteViews(context.packageName, R.layout.checkin_widget_row)
                    tasks.forEach { task ->
                        val item = RemoteViews(context.packageName, R.layout.checkin_widget_task)
                        item.setImageViewBitmap(R.id.widget_check, if (task.done) checked else unchecked)
                        item.setTextViewText(R.id.widget_task_text, task.label)
                        item.setTextColor(R.id.widget_task_text, (if (task.done) c.sub else c.text).toArgb())
                        item.setContentDescription(R.id.widget_task, "${task.label}，${if (task.done) "已完成，点击取消" else "未完成，点击完成"}")
                        val intent = Intent(context, WidgetActionReceiver::class.java).setAction(TOGGLE)
                            .setData(Uri.Builder().scheme("checkin").authority("widget").appendPath(widgetId.toString())
                                .appendPath(snapshot.date).appendPath(task.id).build())
                        item.setOnClickPendingIntent(R.id.widget_task, PendingIntent.getBroadcast(context, 0, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                        row.addView(R.id.widget_row, item)
                    }
                    views.addView(R.id.widget_tasks, row)
                }
            }
            return views
        }

        internal fun isOpenToday(intent: Intent?): Boolean = intent?.action == OPEN_TODAY
    }
}

class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TOGGLE) return
        val parts = intent.data?.pathSegments ?: return
        if (parts.size != 3) return
        val date = parts[1]
        val id = parts[2]
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val plan = StudyPlan(JSONObject(context.assets.open("plan.json").bufferedReader().use { it.readText() }))
                CheckinRepository.get(context).update { state ->
                    val today = CheckinCore.today()
                    require(date == today) { "日期已变化，请重新操作" }
                    require(widgetSnapshot(plan, state, today).tasks.any { it.id == id }) { "任务已变化，请重新操作" }
                    toggleDayTask(state, plan, date, today, id, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    Toast.makeText(context, "小组件未保存，请打开应用检查存档", Toast.LENGTH_LONG).show()
                }
            } finally {
                try { CheckinWidget.refreshAll(context) } finally { pending.finish() }
            }
        }
    }
}

class WidgetClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(MIDNIGHT, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_DATE_CHANGED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        runCatching { WidgetRefresh.schedule(context) }
        runCatching { CheckinWidget.refreshAll(context) }
    }
}
