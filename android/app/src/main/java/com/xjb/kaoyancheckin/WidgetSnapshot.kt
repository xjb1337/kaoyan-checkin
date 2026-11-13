package com.xjb.kaoyancheckin

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class WidgetTask(val id: String, val label: String, val done: Boolean)
data class WidgetSnapshot(
    val date: String, val tasks: List<WidgetTask>, val completed: Int, val required: Int,
    val satisfied: Boolean, val streak: Int, val beforePlan: Boolean, val daysToStart: Long? = null,
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
    val beforePlan = today < plan.start
    return WidgetSnapshot(
        today, eligible.map { WidgetTask(it.id, it.label, plan.isDone(record, it.id)) },
        plan.minCount(today, state), if (flexible) 1 else 2,
        plan.satisfied(today, state), plan.streak(state, today), beforePlan,
        if (beforePlan) ChronoUnit.DAYS.between(LocalDate.parse(today), LocalDate.parse(plan.start)) else null,
    )
}

internal fun nextShanghaiMidnight(now: Instant): Instant =
    now.atZone(CheckinCore.shanghai).toLocalDate().plusDays(1).atStartOfDay(CheckinCore.shanghai).toInstant()
