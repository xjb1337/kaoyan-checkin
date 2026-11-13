package com.xjb.kaoyancheckin

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UndoLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var app: Application
    private lateinit var database: TestDatabase
    private lateinit var previousClock: Clock
    private val date = "2026-11-10"

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        previousClock = CheckinCore.clock
        CheckinCore.clock = Clock.fixed(Instant.parse("2026-11-10T04:00:00Z"), CheckinCore.shanghai)
        UpdateServices.backendOverride = NoNetworkUpdateBackend
        database = TestDatabase(app)
        WidgetNotice.take(app)
    }

    @After fun tearDown() {
        try { database.close() } finally {
            WidgetNotice.take(app)
            CheckinCore.clock = previousClock
            UpdateServices.backendOverride = null
        }
    }

    private fun widgetToggle() {
        val plan = StudyPlan(JSONObject(app.assets.open("plan.json").bufferedReader().use { it.readText() }))
        awaitWrite { toggleWidgetTask(CheckinRepository.get(app), plan, date, "words") }
    }

    private fun awaitWrite(write: suspend () -> Boolean) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val job = scope.async { write() }
            compose.waitUntil(timeoutMillis = 15_000) {
                shadowOf(Looper.getMainLooper()).idle()
                job.isCompleted
            }
            check(runBlocking { job.await() })
        } finally {
            scope.cancel()
        }
    }

    private fun waitForSnackbar(model: CheckinViewModel) {
        try {
            compose.waitUntil(timeoutMillis = 15_000) {
                shadowOf(Looper.getMainLooper()).idle()
                compose.onAllNodesWithText("撤销").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("未出现撤销提示：ready=${model.ready} pending=${model.pendingUndo} state=${model.state}", e)
        }
        compose.onNodeWithText("撤销").assertIsDisplayed()
    }

    @Test fun widgetActionsAreUndoableOnOpenAndInForegroundAndExpireInBackground() {
        // Simulate a completed widget write before the Activity or its ViewModel exists.
        widgetToggle()
        database.launchActivity().use { scenario ->
            lateinit var model: CheckinViewModel
            scenario.onActivity { model = ViewModelProvider(it)[CheckinViewModel::class.java] }
            waitForSnackbar(model)
            fun undoAndWait() {
                compose.onNodeWithText("撤销").performClick()
                try {
                    compose.waitUntil(timeoutMillis = 15_000) {
                        shadowOf(Looper.getMainLooper()).idle()
                        val day = model.state.obj("days").optJSONObject(date)
                        day != null && !model.plan.isDone(day, "words") && !model.state.has("_undo")
                    }
                } catch (e: ComposeTimeoutException) {
                    throw AssertionError("撤销未落盘：pending=${model.pendingUndo} state=${model.state}", e)
                }
            }
            undoAndWait()
            widgetToggle()
            waitForSnackbar(model)
            undoAndWait()

            scenario.moveToState(Lifecycle.State.CREATED)
            awaitWrite {
                CheckinRepository.get(app).update {
                    toggleTaskWithUndo(it, model.plan, date, date, "words", System.currentTimeMillis() - UNDO_WINDOW_MS - 1)
                }
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(timeoutMillis = 15_000) {
                shadowOf(Looper.getMainLooper()).idle()
                model.state.has("_undo") && model.pendingUndo == null
            }
            compose.waitForIdle()
            compose.onNodeWithText("撤销").assertDoesNotExist()
        }
    }

    @Test fun ordinaryNoticeDoesNotDismissOrStealTheUndoAction() {
        database.launchActivity().use { scenario ->
            lateinit var model: CheckinViewModel
            scenario.onActivity { model = ViewModelProvider(it)[CheckinViewModel::class.java] }
            compose.waitUntil(timeoutMillis = 15_000) { model.ready }
            compose.runOnIdle { model.toggleTask(date, "words") }
            waitForSnackbar(model)
            val token = checkNotNull(model.pendingUndo).token
            compose.runOnIdle { model.notify("示例普通提示") }
            compose.onNodeWithText("示例普通提示").assertIsDisplayed()
            compose.onNodeWithText("撤销").assertIsDisplayed()
            assertEquals(1, compose.onAllNodesWithText("撤销").fetchSemanticsNodes().size)
            assertEquals(token, model.pendingUndo?.token)
            compose.onNodeWithText("撤销").performClick()
            compose.waitUntil(timeoutMillis = 15_000) {
                shadowOf(Looper.getMainLooper()).idle()
                !model.plan.isDone(model.state.obj("days").optJSONObject(date), "words") && !model.state.has("_undo")
            }
            compose.onNodeWithText("示例普通提示").assertIsDisplayed()
        }
    }

    @Test fun widgetFailureSurvivesUntilAppOpenAndUsesAnOrdinarySnackbar() {
        WidgetNotice.post(app, "示例小组件保存失败")
        database.launchActivity().use { scenario ->
            lateinit var model: CheckinViewModel
            scenario.onActivity { model = ViewModelProvider(it)[CheckinViewModel::class.java] }
            compose.waitUntil(timeoutMillis = 15_000) { model.ready }
            compose.onNodeWithText("示例小组件保存失败").assertIsDisplayed()
            compose.onNodeWithText("撤销").assertDoesNotExist()
            assertNull(WidgetNotice.peek(app))
            compose.runOnIdle { WidgetNotice.post(app, "示例前台小组件失败") }
            assertNull(WidgetNotice.peek(app))
        }
    }
}
