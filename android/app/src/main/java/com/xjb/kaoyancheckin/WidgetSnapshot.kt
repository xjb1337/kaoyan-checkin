package com.xjb.kaoyancheckin

import org.json.JSONObject
import java.time.Instant

data class WidgetTask(val id: String, val label: String, val done: Boolean)
data class WidgetSnapshot(
    val date: String, val tasks: List<WidgetTask>, val completed: Int, val required: Int,
    val satisfied: Boolean, val streak: Int, val beforePlan: Boolean,
)

/** Presentation only: achievement, counts and streak stay in StudyPlan. */
internal fun widgetSnapshot(plan: StudyPlan, state: JSONObject, today: String): WidgetSnapshot {
    val phase = plan.phase(today, state)
    val all = plan.tasks(today, state, today)
    val flexible = phase.optInt("fs") == 1
    val minimumIds = phase.arr("tasks").values().filterIsInstance<JSONObject>()
        .filter { it.optInt("min") == 1 }.map { it.getString("id") }.toSet()
    val eligible = if (flexible) all else all.filter { it.id in minimumIds }
    val record = state.obj("days").optJSONObject(today)
    return WidgetSnapshot(
        today, eligible.map { WidgetTask(it.id, it.label, plan.isDone(record, it.id)) },
        plan.minCount(today, state), if (flexible) 1 else 2,
        plan.satisfied(today, state), plan.streak(state, today), today < plan.start,
    )
}

internal fun nextShanghaiMidnight(now: Instant): Instant =
    now.atZone(CheckinCore.shanghai).toLocalDate().plusDays(1).atStartOfDay(CheckinCore.shanghai).toInstant()
