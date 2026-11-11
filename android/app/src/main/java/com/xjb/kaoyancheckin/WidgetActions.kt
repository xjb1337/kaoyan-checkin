package com.xjb.kaoyancheckin

/** The receiver and tests use the same guarded, mutex-protected widget write. */
internal suspend fun toggleWidgetTask(
    store: DocumentStore, plan: StudyPlan, date: String, id: String,
    today: () -> String = CheckinCore::today, now: () -> Long = System::currentTimeMillis,
): Boolean = store.update { state ->
    val current = today()
    require(date == current) { "日期已变化，请重新操作" }
    require(widgetSnapshot(plan, state, current).tasks.any { it.id == id }) { "任务已变化，请重新操作" }
    toggleTaskWithUndo(state, plan, date, current, id, now())
}
