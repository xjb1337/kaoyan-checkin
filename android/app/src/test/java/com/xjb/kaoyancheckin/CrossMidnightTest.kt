package com.xjb.kaoyancheckin

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import java.time.Clock
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

class RollTodayTest {
    @Test fun oldTodayRollsButHistoricalSelectionStays() {
        val rolled = rollToday("2026-11-15", "2026-11-15", "2026-11-09", "2026-11-16")
        assertEquals("2026-11-16", rolled.today)
        assertEquals("2026-11-16", rolled.selected)
        assertEquals("2026-11-16", rolled.week)

        val historical = rollToday("2026-11-15", "2026-11-10", "2026-11-09", "2026-11-16")
        assertEquals("2026-11-16", historical.today)
        assertEquals("2026-11-10", historical.selected)
        assertEquals("2026-11-16", historical.week)
    }

    @Test fun staleTodayWritesMoveToTheNewDayButHistoricalWritesStay() {
        assertEquals(
            "2026-11-16",
            resolveWriteDate("2026-11-15", "2026-11-15", "2026-11-16", "2026-11-16", true),
        )
        assertEquals(
            "2026-11-10",
            resolveWriteDate("2026-11-10", "2026-11-15", "2026-11-16", "2026-11-10", false),
        )
        assertEquals(
            "2026-11-16",
            resolveWriteDate(null, "2026-11-15", "2026-11-16", "2026-11-16", true),
        )
        assertEquals(
            "2026-11-10",
            resolveWriteDate(null, "2026-11-15", "2026-11-16", "2026-11-10", false),
        )
    }

    @Test fun delayRunsUntilTheNextShanghaiMidnight() {
        val previous = CheckinCore.clock
        try {
            CheckinCore.clock = Clock.fixed(Instant.parse("2026-11-10T15:59:30Z"), CheckinCore.shanghai)
            assertEquals(30_000L, CheckinCore.millisUntilNextShanghaiMidnight())
            CheckinCore.clock = Clock.fixed(Instant.parse("2026-11-10T16:00:00Z"), CheckinCore.shanghai)
            assertEquals(86_400_000L, CheckinCore.millisUntilNextShanghaiMidnight())
        } finally {
            CheckinCore.clock = previous
        }
    }

    @Test fun refreshDelayIsBoundedEvenWithAFixedClockAtMidnight() {
        val previous = CheckinCore.clock
        try {
            CheckinCore.clock = Clock.fixed(Instant.parse("2026-11-10T15:59:30Z"), CheckinCore.shanghai)
            assertEquals(30_000L, CheckinCore.todayRefreshDelayMillis())
            CheckinCore.clock = Clock.fixed(Instant.parse("2026-11-10T15:59:59.999Z"), CheckinCore.shanghai)
            assertEquals(1_000L, CheckinCore.todayRefreshDelayMillis())
            CheckinCore.clock = Clock.fixed(Instant.parse("2026-11-10T16:00:00Z"), CheckinCore.shanghai)
            repeat(3) { assertEquals(60_000L, CheckinCore.todayRefreshDelayMillis()) }
        } finally {
            CheckinCore.clock = previous
        }
    }

    @Test fun chapterToggleRecordsTheRolledDay() {
        val state = CheckinCore.emptyState()
        val subject = JSONObject()
            .put("k", "phys")
            .put("ch", JSONArray().put("一"))
        val rolled = rollToday("2026-11-15", "2026-11-15", "2026-11-09", "2026-11-16")

        toggleChapter(state, subject, 0, rolled.today)

        assertEquals("2026-11-16", state.obj("cd").obj("phys").getString("0"))
        assertEquals("2026-11-16", state.obj("cf").getString("phys"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CrossMidnightTest {
    private lateinit var previousClock: Clock
    private lateinit var app: Application
    private lateinit var database: TestDatabase
    private val oldToday = "2026-11-15"
    private val newToday = "2026-11-16"

    @Before fun setUp() {
        previousClock = CheckinCore.clock
        setTime("2026-11-15T15:59:59Z")
        UpdateServices.backendOverride = NoNetworkUpdateBackend
        app = ApplicationProvider.getApplicationContext()
        database = TestDatabase(app)
    }

    @After fun tearDown() {
        try { database.close() } finally {
            CheckinCore.clock = previousClock
            UpdateServices.backendOverride = null
        }
    }

    @Test fun crossMidnightToggleTaskWritesToTheNewVisibleDay() {
        database.launchActivity().use { scenario ->
            val model = awaitReady(scenario)
            scenario.onActivity {
                setTime("2026-11-15T16:00:00Z")
                model.toggleTask(oldToday, "words")
            }
            awaitCondition { model.state.obj("days").obj(newToday).obj("t").optBoolean("words") }
            assertEquals(newToday, model.today)
            assertEquals(newToday, model.selected)
            assertEquals(newToday, model.week)
            assertFalse(model.state.obj("days").has(oldToday))
        }
    }

    @Test fun historicalSelectionStaysWhileTodayRolls() {
        database.launchActivity().use { scenario ->
            val model = awaitReady(scenario)
            scenario.onActivity {
                model.selected = "2026-11-10"
                model.week = "2026-11-02"
                setTime("2026-11-15T16:00:00Z")
                model.editDay(oldToday) { it.obj("rw").put("n", "明确补记昨天") }
                model.toggleTask(oldToday, "words")
                model.editDay { it.obj("rw").put("g", "补记历史") }
                model.editWeek("2026-11-02") { it.arr("must").put(0, "历史周目标") }
            }
            awaitCondition {
                model.state.obj("weeks").obj("2026-11-02").arr("must").optString(0) == "历史周目标"
            }
            assertEquals("2026-11-10", model.selected)
            assertEquals("2026-11-02", model.week)
            assertEquals(newToday, model.today)
            assertTrue(model.state.obj("days").obj(oldToday).obj("t").optBoolean("words"))
            assertEquals("明确补记昨天", model.state.obj("days").obj(oldToday).obj("rw").optString("n"))
            assertEquals("补记历史", model.state.obj("days").obj("2026-11-10").obj("rw").optString("g"))
            assertFalse(model.state.obj("days").has(newToday))
        }
    }

    @Test fun crossMidnightEditDayMovesStaleTodayButNotExplicitHistory() {
        database.launchActivity().use { scenario ->
            val model = awaitReady(scenario)
            scenario.onActivity {
                setTime("2026-11-15T16:00:00Z")
                model.editDay(oldToday) { it.obj("rw").put("g", "新今天") }
                model.editDay("2026-11-10") { it.obj("rw").put("g", "明确历史") }
                model.editDay { it.obj("rw").put("n", "默认今天") }
            }
            awaitCondition { model.state.obj("days").obj(newToday).obj("rw").optString("n") == "默认今天" }
            assertEquals("新今天", model.state.obj("days").obj(newToday).obj("rw").optString("g"))
            assertEquals("明确历史", model.state.obj("days").obj("2026-11-10").obj("rw").optString("g"))
            assertFalse(model.state.obj("days").has(oldToday))
        }
    }

    @Test fun crossMidnightEditWeekMovesTheCurrentWeekButKeepsExplicitHistory() {
        database.launchActivity().use { scenario ->
            val model = awaitReady(scenario)
            scenario.onActivity {
                setTime("2026-11-15T16:00:00Z")
                model.editWeek("2026-11-09") { it.arr("must").put(0, "新一周") }
                model.editWeek("2026-11-02") { it.arr("must").put(0, "历史周") }
            }
            awaitCondition { model.state.obj("weeks").obj("2026-11-02").arr("must").optString(0) == "历史周" }
            assertEquals("新一周", model.state.obj("weeks").obj(newToday).arr("must").optString(0))
            assertFalse(model.state.obj("weeks").has("2026-11-09"))
        }
    }

    @Test fun crossMidnightCopyGoalsUsesTheRolledWeek() {
        database.launchActivity().use { scenario ->
            val model = awaitReady(scenario)
            scenario.onActivity { model.editWeek("2026-11-09") { it.arr("must").put(0, "本周目标") } }
            awaitCondition { model.state.obj("weeks").obj("2026-11-09").arr("must").optString(0) == "本周目标" }
            scenario.onActivity {
                setTime("2026-11-15T16:00:00Z")
                model.copyPreviousWeekGoals("2026-11-09")
            }
            awaitCondition { model.state.obj("weeks").obj(newToday).arr("must").optString(0) == "本周目标" }
            assertEquals(newToday, model.week)
        }
    }

    @Test fun crossMidnightCopyGoalsKeepsAnExplicitHistoricalWeek() {
        database.launchActivity().use { scenario ->
            val model = awaitReady(scenario)
            scenario.onActivity { model.editWeek("2026-10-26") { it.arr("must").put(0, "历史上周") } }
            awaitCondition { model.state.obj("weeks").obj("2026-10-26").arr("must").optString(0) == "历史上周" }
            scenario.onActivity {
                model.selected = "2026-11-10"
                model.week = "2026-11-02"
                setTime("2026-11-15T16:00:00Z")
                model.copyPreviousWeekGoals("2026-11-02")
            }
            awaitCondition { model.state.obj("weeks").obj("2026-11-02").arr("must").optString(0) == "历史上周" }
            assertEquals("2026-11-02", model.week)
            assertFalse(model.state.obj("weeks").has(newToday))
        }
    }

    @Test fun chapterTogglePersistsTheRolledDay() {
        database.launchActivity().use { scenario ->
            val model = awaitReady(scenario)
            val subject = JSONObject().put("k", "phys").put("ch", JSONArray().put("一"))
            scenario.onActivity {
                setTime("2026-11-15T16:00:00Z")
                model.toggleChapter(subject, 0)
            }
            awaitCondition { model.state.obj("cd").obj("phys").optString("0") == newToday }
            assertEquals(newToday, model.selected)
            assertEquals(newToday, model.state.obj("ce").getString("phys"))
        }
    }

    @Test fun resumingRefreshesTodayWithoutMovingHistoricalSelections() {
        database.launchActivity().use { scenario ->
            val model = awaitReady(scenario)
            scenario.onActivity {
                model.selected = "2026-11-10"
                model.week = "2026-11-02"
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            setTime("2026-11-15T16:00:00Z")
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitCondition { model.today == newToday }
            assertEquals("2026-11-10", model.selected)
            assertEquals("2026-11-02", model.week)
        }
    }

    private fun setTime(instant: String) {
        CheckinCore.clock = Clock.fixed(Instant.parse(instant), CheckinCore.shanghai)
    }

    private fun awaitReady(scenario: ActivityScenario<MainActivity>): CheckinViewModel {
        lateinit var model: CheckinViewModel
        scenario.onActivity { model = ViewModelProvider(it)[CheckinViewModel::class.java] }
        awaitCondition { model.ready }
        check(model.loadError.isEmpty()) { model.loadError }
        assertEquals("新测试不应读到其他测试的记录", 0, model.state.obj("days").length())
        return model
    }

    private fun awaitCondition(condition: () -> Boolean) {
        awaitTestCondition("跨午夜写入", condition = condition)
    }
}
