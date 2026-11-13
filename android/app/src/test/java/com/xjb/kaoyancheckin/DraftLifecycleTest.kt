package com.xjb.kaoyancheckin

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.lifecycle.Lifecycle
import com.xjb.kaoyancheckin.ui.theme.AppTheme
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DraftLifecycleTest {
    private lateinit var previousClock: Clock
    private lateinit var app: Application
    private lateinit var database: TestDatabase

    @Before fun setUp() {
        UpdateServices.backendOverride = NoNetworkUpdateBackend
        previousClock = CheckinCore.clock
        CheckinCore.clock = Clock.fixed(Instant.parse("2026-11-10T04:00:00Z"), CheckinCore.shanghai)
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences(ThemeRepository.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        database = TestDatabase(app)
    }

    @After fun tearDown() {
        try { database.close() } finally {
            CheckinCore.clock = previousClock
            UpdateServices.backendOverride = null
        }
    }

    @Test fun switchingTabsAndPausingPersistDraftsAlongsideOtherEdits() {
        val date = "2026-11-10"
        val week = "2026-11-09"
        database.launchActivity().use { scenario ->
            val model = awaitReady(scenario)

            scenario.onActivity {
                model.updateDraft(DraftField.Hours(date, "西综"), "2")
                model.adjustHours(date, "西综", 0.5)
                model.updateDraft(DraftField.DayReview(date, "g"), "今天的收获")
                model.updateDraft(DraftField.Words(date, "n"), "35")
                model.updateDraft(DraftField.Words(date, "r"), "80")
                model.editDay(date) { it.obj("t").put("words", true) }
                model.makeup("2026-11-08")
                model.page = 2
                model.updateDraft(DraftField.WeekGoal(week, 0), "本周目标")
                model.updateDraft(DraftField.WeekReview(week), "周日复盘")
                model.editWeek(week) { it.arr("md").put(0, true) }
            }
            scenario.onActivity { model.flushDrafts() }
            scenario.moveToState(Lifecycle.State.STARTED)

            awaitCondition(
                description = "切页与暂停后的草稿、目标和其他编辑全部保存",
                current = { "page=${model.page}, state=${model.state}" },
            ) {
                val state = model.state
                val day = state.obj("days").optJSONObject(date)
                val target = state.obj("weeks").optJSONObject(week)
                day != null && target != null && day.obj("rw").optString("g") == "今天的收获" &&
                    day.obj("w").optInt("n") == 35 && day.obj("w").optInt("r") == 80 &&
                    day.obj("t").optBoolean("words") && day.obj("sh").optDouble("西综") == 2.5 &&
                    state.arr("mk").values().contains("2026-11-08") &&
                    target.arr("must").optString(0) == "本周目标" &&
                    target.optString("review") == "周日复盘" && target.arr("md").optBoolean(0)
            }
            // 落盘由 activityDestructionDoesNotCancelAFlushedWriteBeforeReload 覆盖。
        }
    }

    @Test fun changingThemeKeepsViewModelPageAndPendingDraft() {
        database.launchActivity().use { scenario ->
            val model = awaitReady(scenario)
            val field = DraftField.DayReview("2026-11-10", "g")
            scenario.onActivity { activity ->
                model.page = 5
                model.updateDraft(field, "切换主题前的草稿")
                val appearance = ViewModelProvider(activity)[ThemeViewModel::class.java]
                assertEquals(AppTheme.WEB_BLUE, appearance.theme)
                assertEquals(AppTheme.WEB_BLUE, ThemeRepository(activity).load())
                appearance.select(AppTheme.BAMBOO_GREEN)
                assertEquals(AppTheme.BAMBOO_GREEN, appearance.theme)
                assertSame(activity.application, appearance.getApplication<Application>())
                assertEquals(AppTheme.BAMBOO_GREEN, ThemeRepository(activity.application).load())
                assertEquals(AppTheme.BAMBOO_GREEN, ThemeRepository(activity).load())
            }
            shadowOf(Looper.getMainLooper()).idle()
            scenario.onActivity { activity ->
                assertSame(model, ViewModelProvider(activity)[CheckinViewModel::class.java])
                assertEquals(5, model.page)
                val saved = model.state.obj("days").obj("2026-11-10").obj("rw").optString("g")
                assertEquals("切换主题前的草稿", model.draftValue(field, saved))
                assertEquals(AppTheme.BAMBOO_GREEN, ThemeRepository(activity).load())
            }
        }
    }

    @Test fun copyingGoalsAfterTypingKeepsLaterCheckboxChanges() {
        database.launchActivity().use { scenario ->
            val model = awaitReady(scenario)
            val week = "2026-11-09"
            scenario.onActivity {
                model.editWeek("2026-11-02") { it.arr("must").put(0, "上周目标") }
            }
            awaitCondition(
                description = "上周目标写入完成",
                current = { model.state.toString() },
            ) {
                model.state.obj("weeks").optJSONObject("2026-11-02")?.arr("must")?.optString(0) == "上周目标"
            }

            scenario.onActivity {
                model.page = 2
                model.updateDraft(DraftField.WeekGoal(week, 0), "刚输入的目标")
                model.copyPreviousWeekGoals(week)
                model.editWeek(week) { it.arr("md").put(0, true) }
            }
            awaitCondition(
                description = "复制上周目标后仍保留稍后的勾选",
                current = { "week=${model.state.obj("weeks").optJSONObject(week)}, state=${model.state}" },
            ) {
                val target = model.state.obj("weeks").optJSONObject(week)
                target != null && target.arr("must").optString(0) == "上周目标" && target.arr("md").optBoolean(0)
            }
        }
    }

    @Test fun activityDestructionDoesNotCancelAFlushedWriteBeforeReload() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val readStarted = CountDownLatch(1)
        val continueWrite = CountDownLatch(1)
        try {
            database.launchActivity().use { scenario ->
                val model = awaitReady(scenario)
                val blocker = scope.async {
                    CheckinRepository.get(app).inspect {
                        readStarted.countDown()
                        check(continueWrite.await(15, TimeUnit.SECONDS))
                    }
                }
                awaitCondition("阻塞存储写入") { readStarted.count == 0L }
                scenario.onActivity {
                    model.updateDraft(DraftField.DayReview("2026-11-10", "n"), "下次继续")
                    model.flushDrafts()
                }
                assertTrue(model.hasPendingWrites)
                scenario.close()
                assertTrue(model.hasPendingWrites)
                continueWrite.countDown()
                awaitCondition("释放存储写入") { blocker.isCompleted }
                runBlocking { blocker.await() }
            }
            database.awaitWrites()

            val persisted = readPersisted()
            assertEquals("下次继续", persisted.obj("days").obj("2026-11-10").obj("rw").optString("n"))

            database.launchActivity().use { scenario ->
                val reloaded = awaitReady(scenario)
                assertEquals("下次继续", reloaded.state.obj("days").obj("2026-11-10").obj("rw").optString("n"))
            }
        } finally {
            continueWrite.countDown()
            scope.cancel()
        }
    }

    @Test fun activityDestructionFlushesADraftWithoutAnExplicitSave() {
        database.launchActivity().use { scenario ->
            val model = awaitReady(scenario)
            scenario.onActivity {
                model.updateDraft(DraftField.DayReview("2026-11-10", "g"), "退出前尚未保存的草稿")
            }
        }
        database.awaitWrites()
        assertEquals("退出前尚未保存的草稿", readPersisted().obj("days").obj("2026-11-10").obj("rw").getString("g"))
    }

    private fun awaitReady(scenario: ActivityScenario<MainActivity>): CheckinViewModel {
        lateinit var model: CheckinViewModel
        scenario.onActivity { model = ViewModelProvider(it)[CheckinViewModel::class.java] }
        awaitCondition(
            description = "ViewModel 加载完成",
            current = { "ready=${model.ready}, loadError=${model.loadError}" },
        ) { model.ready }
        check(model.loadError.isEmpty()) { model.loadError }
        return model
    }

    private fun awaitCondition(
        description: String,
        timeoutMillis: Long = 15_000,
        current: () -> Any? = { "" },
        condition: () -> Boolean,
    ) {
        awaitTestCondition(description, timeoutMillis, current, condition)
    }

    private fun readPersisted(): JSONObject {
        val database = Room.databaseBuilder(app, CheckinDatabase::class.java, "checkin.db").build()
        return try {
            JSONObject(checkNotNull(runBlocking { database.document().read() }))
        } finally {
            database.close()
        }
    }
}
