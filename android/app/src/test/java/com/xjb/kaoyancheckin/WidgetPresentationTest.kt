package com.xjb.kaoyancheckin

import org.junit.Assert.*
import org.junit.Test

class WidgetPresentationTest {
    private val snapshot = WidgetSnapshot("2026-11-10", emptyList(), 0, 2, false, 3, false)

    @Test fun layoutUsesAvailableHeightAndDegradesWhenSmall() {
        assertEquals(WidgetLayout(false, true, 1, 2), widgetLayout(300, 130, 2))
        assertEquals(WidgetLayout(false, true, 1, 1), widgetLayout(300, 100, 2))
        assertEquals(WidgetLayout(true, false, 1, 1), widgetLayout(180, 80, 2))
        assertEquals(WidgetLayout(false, true, 2, 3), widgetLayout(340, 180, 1))
        assertEquals(4, widgetLayout(340, 400, 1).maxRows)
    }

    @Test fun statusShowsAchievementAndBoundsProgress() {
        assertEquals(WidgetStatus("最低线 0/2 · 未达标", 0f), widgetStatus(snapshot))
        assertEquals(WidgetStatus("最低线 2/2 · 已达标", 1f), widgetStatus(snapshot.copy(completed = 2, satisfied = true)))
        assertEquals(1f, widgetStatus(snapshot.copy(completed = 0, satisfied = true)).progress, 0f)
        assertEquals(.5f, widgetStatus(snapshot.copy(completed = 1)).progress, 0f)
        assertEquals(1f, widgetStatus(snapshot.copy(completed = 5)).progress, 0f)
        assertEquals(0f, widgetStatus(snapshot.copy(required = 0)).progress, 0f)
        assertEquals("计划尚未开始", widgetStatus(snapshot.copy(beforePlan = true)).summary)
        assertEquals("存档异常，请打开应用", widgetStatus(null).summary)
    }
}
