package com.xjb.kaoyancheckin

import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UndoTaskTest {
    private val plan = StudyPlan(JSONObject(File("src/main/assets/plan.json").readText()))
    private val date = "2026-11-10"
    private val time = 1_700_000_000_000L

    @Test fun onlyTheLatestStepIsAvailableAndItExpires() {
        val state = CheckinCore.emptyState()
        toggleTaskWithUndo(state, plan, date, date, "words", time, "one")
        assertEquals("已勾选「英语单词」", liveUndo(state, time)?.message)
        assertEquals("one", liveUndo(state, time)?.token)
        assertEquals("one", liveUndo(state, time + UNDO_WINDOW_MS - 1)?.token)
        assertNull(liveUndo(state, time + UNDO_WINDOW_MS))
        assertNull(liveUndo(state, time - 1))
        toggleTaskWithUndo(state, plan, date, date, "drill", time + 1, "two")
        assertEquals("two", liveUndo(state, time + 1)?.token)
        assertNull(undoTask(state, plan, "one", date, time + 2))
        assertNull(undoTask(state, plan, "two", date, time + 1 + UNDO_WINDOW_MS))
    }

    @Test fun widgetAndAppShareLockedToggleAndUndoWritesWithoutCreatingAnotherStep() = runBlocking {
        var disk: String? = null
        var writes = 0
        val store = DocumentStore({ disk }, { disk = it; writes++ }, now = { time })
        val foregroundChange = async(start = CoroutineStart.UNDISPATCHED) { store.changes.first() }
        store.update { toggleTaskWithUndo(it, plan, date, date, "words", time, "widget") }
        foregroundChange.await()
        val afterWidget = CheckinCore.parseBackupDocument(checkNotNull(store.read()))
        val step = checkNotNull(liveUndo(afterWidget, time + 1))
        assertEquals("widget", step.token)
        assertTrue(plan.isDone(afterWidget.obj("days").obj(date), "words"))
        assertFalse(JSONObject(CheckinCore.backupJSON(afterWidget)).has("_undo"))

        // Reloading a fresh store models the app opening after a background widget write/process restart.
        val restarted = DocumentStore({ disk }, { disk = it; writes++ }, now = { time + 2 })
        assertEquals(step, liveUndo(CheckinCore.parseBackupDocument(checkNotNull(restarted.read())), time + 2))
        assertNull(liveUndo(CheckinCore.parseBackupDocument(checkNotNull(restarted.read())), time + UNDO_WINDOW_MS))
        assertTrue(restarted.update { undoTask(it, plan, step.token, date, time + 2) })
        val restored = CheckinCore.parseBackupDocument(checkNotNull(restarted.read()))
        assertFalse(plan.isDone(restored.obj("days").obj(date), "words"))
        assertNull(liveUndo(restored, time + 2))
        assertFalse(restored.has("_undo"))
        assertEquals(time + 2, restored.obj("days").obj(date).getLong("u"))
        assertFalse(restarted.update { undoTask(it, plan, step.token, date, time + 3) })
        assertEquals(2, writes)
    }

    @Test fun widgetUsesTheSameGuardedWriteAndOnlyOneStep() = runBlocking {
        var disk: String? = null
        val store = DocumentStore({ disk }, { disk = it }, now = { time })
        assertTrue(toggleWidgetTask(store, plan, date, "words", today = { date }, now = { time }))
        val first = CheckinCore.parseBackupDocument(checkNotNull(store.read()))
        val firstToken = checkNotNull(liveUndo(first, time)).token
        assertTrue(toggleWidgetTask(store, plan, date, "drill", today = { date }, now = { time + 1 }))
        val second = CheckinCore.parseBackupDocument(checkNotNull(store.read()))
        assertNotEquals(firstToken, liveUndo(second, time + 1)?.token)
        assertEquals("drill", liveUndo(second, time + 1)?.taskId)
        val before = disk
        try {
            toggleWidgetTask(store, plan, "2026-11-09", "words", today = { date }, now = { time + 2 })
            fail("过期的小组件日期应被拒绝")
        } catch (_: IllegalArgumentException) { assertEquals(before, disk) }
        try {
            toggleWidgetTask(store, plan, date, "not-a-task", today = { date }, now = { time + 2 })
            fail("不存在的小组件任务应被拒绝")
        } catch (_: IllegalArgumentException) { assertEquals(before, disk) }
    }

    @Test fun undoRestoresUncheckingAndDoesNotResurrectDeletedCustomTasks() {
        val state = CheckinCore.emptyState()
        toggleTaskWithUndo(state, plan, date, date, "c:练习", time, "first")
        assertEquals("已勾选「练习」", liveUndo(state, time)?.message)
        toggleTaskWithUndo(state, plan, date, date, "c:练习", time + 1, "second")
        assertEquals("已取消勾选「练习」", liveUndo(state, time + 1)?.message)
        assertNotNull(undoTask(state, plan, "second", date, time + 2))
        assertTrue(plan.isDone(state.obj("days").obj(date), "c:练习"))
        toggleTaskWithUndo(state, plan, date, date, "c:练习", time + 3, "third")
        plan.deleteCustomTask(state.obj("days").obj(date), "c:练习")
        assertNull(undoTask(state, plan, "third", date, time + 4))
    }
}
