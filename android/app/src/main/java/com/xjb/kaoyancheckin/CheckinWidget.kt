package com.xjb.kaoyancheckin

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
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
            val views = RemoteViews(context.packageName, R.layout.checkin_widget)
            val open = Intent(context, MainActivity::class.java).setAction(OPEN_TODAY)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val openIntent = PendingIntent.getActivity(context, widgetId, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, openIntent)
            views.setTextViewText(R.id.widget_streak, snapshot?.let { "连续 ${it.streak} 天" } ?: "打开应用")
            val summary = when {
                snapshot == null -> "存档无法读取，请打开应用处理"
                snapshot.beforePlan -> "计划尚未开始"
                snapshot.satisfied -> "最低线 ${snapshot.completed}/${snapshot.required} · 已达标"
                else -> "最低线 ${snapshot.completed}/${snapshot.required}"
            }
            views.setTextViewText(R.id.widget_summary, summary)
            views.removeAllViews(R.id.widget_tasks)
            val compact = width < 200 || height < 90
            views.setViewVisibility(R.id.widget_title, if (width < 200) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_tasks, if (compact || snapshot == null) View.GONE else View.VISIBLE)
            if (!compact && snapshot != null) {
                val columns = if (snapshot.required == 1) 2 else 1
                val maxRows = if (height >= 180) 4 else 2
                snapshot.tasks.take(columns * maxRows).chunked(columns).forEach { tasks ->
                    val row = RemoteViews(context.packageName, R.layout.checkin_widget_row)
                    tasks.forEach { task ->
                        val item = RemoteViews(context.packageName, R.layout.checkin_widget_task)
                        val prefix = if (task.done) "☑ " else "☐ "
                        item.setTextViewText(R.id.widget_task, prefix + task.label)
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
