package com.xjb.kaoyancheckin

/** Size and status decisions are independent of RemoteViews and can be tested on the JVM. */
internal data class WidgetLayout(val compact: Boolean, val showTitle: Boolean, val columns: Int, val maxRows: Int)

internal fun widgetLayout(width: Int, height: Int, required: Int): WidgetLayout {
    val compact = width < 200 || height < 90
    val columns = if (required == 1 && width >= 320) 2 else 1
    val rows = if (height < 110) 1 else ((height - 34) / 48).coerceIn(2, 4)
    return WidgetLayout(compact, width >= 200, columns, rows)
}

internal data class WidgetStatus(val summary: String, val progress: Float)

internal fun widgetStatus(snapshot: WidgetSnapshot?): WidgetStatus {
    val summary = when {
        snapshot == null -> "存档异常，请打开应用"
        snapshot.beforePlan -> "计划尚未开始"
        snapshot.satisfied -> "最低线 ${snapshot.completed}/${snapshot.required} · 已达标"
        else -> "最低线 ${snapshot.completed}/${snapshot.required} · 未达标"
    }
    val progress = when {
        snapshot == null || snapshot.beforePlan || snapshot.required <= 0 -> 0f
        snapshot.satisfied -> 1f // A make-up card can satisfy the day without checked minimum tasks.
        else -> (snapshot.completed.toFloat() / snapshot.required).coerceIn(0f, 1f)
    }
    return WidgetStatus(summary, progress)
}

internal fun widgetEmptyLabel(snapshot: WidgetSnapshot?): String = when {
    snapshot == null -> "⚠ 存档异常"
    snapshot.beforePlan -> "距开营 ${snapshot.daysToStart ?: 0} 天"
    else -> "查看今日 ›"
}
