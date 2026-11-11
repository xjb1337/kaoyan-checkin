package com.xjb.kaoyancheckin

import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WidgetSnapshotTest {
    private val plan = StudyPlan(JSONObject(File("src/main/assets/plan.json").readText()))
    private val date = "2026-11-10"

    @Test fun minimumTasksUsePhaseFlagsAndExistingAchievementAndStreak() {
        val state = CheckinCore.emptyState()
        state.obj("days").put("2026-11-09", StudyPlan.day().apply { plan.toggle(this, "words"); plan.toggle(this, "drill") })
        var snapshot = widgetSnapshot(plan, state, date)
        assertEquals(listOf("words", "drill"), snapshot.tasks.map { it.id })
        assertEquals(listOf(false, false), snapshot.tasks.map { it.done })
        assertEquals(1, snapshot.streak)
        assertEquals(0, snapshot.completed)
        assertFalse(snapshot.satisfied)
        toggleDayTask(state, plan, date, date, "words", 12)
        toggleDayTask(state, plan, date, date, "drill", 13)
        snapshot = widgetSnapshot(plan, state, date)
        assertEquals(listOf(true, true), snapshot.tasks.map { it.done })
        assertEquals(2, snapshot.completed)
        assertEquals(2, snapshot.streak)
        assertTrue(snapshot.satisfied)
        assertEquals(13, state.obj("days").obj(date).getLong("u"))
        toggleDayTask(state, plan, date, date, "words", 14)
        assertFalse(widgetSnapshot(plan, state, date).tasks.first().done)
        assertThrows(IllegalArgumentException::class.java) { toggleDayTask(state, plan, "2026-11-11", date, "drill", 15) }
    }

    @Test fun postExamCountsAnyTaskIncludingCustom() {
        val state = CheckinCore.emptyState().put("recur", JSONArray().put("阅读"))
        val after = "2027-12-25"
        toggleDayTask(state, plan, after, after, "r:阅读", 1)
        val snapshot = widgetSnapshot(plan, state, after)
        assertEquals(1, snapshot.completed)
        assertEquals(1, snapshot.required)
        assertTrue(snapshot.satisfied)
        assertTrue(snapshot.tasks.any { it.id == "r:阅读" && it.done })
        assertTrue(snapshot.tasks.any { it.id == "fs1" })
    }

    @Test fun documentStoreSerializesConcurrentAppAndWidgetEdits() = runBlocking {
        var disk: String? = null
        val store = DocumentStore(readRaw = { delay(5); disk }, writeRaw = { delay(5); disk = it }, now = { 42L })
        listOf("words", "drill").map { id -> async(Dispatchers.Default) {
            store.update { toggleDayTask(it, plan, date, date, id, 42L) }
        } }.awaitAll()
        val state = CheckinCore.parseBackupDocument(checkNotNull(store.read()))
        assertTrue(plan.isDone(state.obj("days").obj(date), "words"))
        assertTrue(plan.isDone(state.obj("days").obj(date), "drill"))
        assertTrue(plan.satisfied(date, state))
        assertEquals(42L, state.getLong("upd"))
        assertEquals(42L, state.obj("days").obj(date).getLong("u"))
    }

    @Test fun corruptDocumentIsNeverOverwrittenByNormalWidgetEdit() = runBlocking {
        var disk: String? = "{broken"
        val store = DocumentStore({ disk }, { disk = it })
        try { store.update { toggleDayTask(it, plan, date, date, "words", 1) }; fail("must reject corrupt document") }
        catch (_: Exception) { assertEquals("{broken", disk) }
    }

    @Test fun nextMidnightUsesShanghaiNotDeviceTimeZone() {
        assertEquals(Instant.parse("2026-11-10T16:00:00Z"), nextShanghaiMidnight(Instant.parse("2026-11-10T15:59:59Z")))
        assertEquals(Instant.parse("2026-11-11T16:00:00Z"), nextShanghaiMidnight(Instant.parse("2026-11-10T16:00:00Z")))
    }
}
