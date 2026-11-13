package com.xjb.kaoyancheckin

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenshotTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var previousTimeZone: TimeZone
    private lateinit var previousClock: Clock
    private lateinit var testDatabase: TestDatabase
    private val screenshotInstant = Instant.parse("2026-11-09T13:30:00Z")

    @Before
    fun seedDatabase() {
        UpdateServices.backendOverride = NoNetworkUpdateBackend
        previousTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        previousClock = CheckinCore.clock
        CheckinCore.clock = Clock.fixed(screenshotInstant, CheckinCore.shanghai)

        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences(ThemeRepository.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        testDatabase = TestDatabase(app)
        val raw = checkNotNull(javaClass.getResourceAsStream("/screenshot-seed.json"))
            .bufferedReader().use { it.readText() }
        // Follow the same validation, normalization and recovery rules as a real replacement import.
        val imported = CheckinCore.importBackup(
            CheckinCore.emptyState(), raw, mode = "replace", now = screenshotInstant.toEpochMilli(),
        )
        check(CheckinCore.hasData(imported))
        val database = Room.databaseBuilder(app, CheckinDatabase::class.java, "checkin.db").build()
        try {
            runBlocking { database.document().write(CheckinDocument(json = imported.toString())) }
        } finally {
            database.close()
        }
    }

    @After
    fun restoreTimeZone() {
        try { testDatabase.close() } finally {
            UpdateServices.backendOverride = null
            CheckinCore.clock = previousClock
            TimeZone.setDefault(previousTimeZone)
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun lightScreens() = captureScreens(dark = false)

    @Test
    @Config(qualifiers = "w412dp-h915dp-night-xhdpi")
    fun darkScreens() = captureScreens(dark = true)

    private fun captureScreens(dark: Boolean) {
        val outputDir = File(checkNotNull(System.getProperty("screenshotDir")))
        check(outputDir.isDirectory || outputDir.mkdirs()) { "Cannot create $outputDir" }
        val tabs = listOf(
            "今日" to "today", "进度" to "prog", "周目标" to "week",
            "统计" to "stats", "节点" to "mile", "设置" to "set",
        )

        testDatabase.launchActivity().use { scenario ->
            lateinit var model: CheckinViewModel
            scenario.onActivity { model = ViewModelProvider(it)[CheckinViewModel::class.java] }
            // The ViewModel loads Room asynchronously; wait for content rather than a loading frame.
            compose.waitUntil(timeoutMillis = 15_000) { model.ready }
            compose.runOnIdle {
                check(model.loadError.isEmpty()) { model.loadError }
                check(model.today == "2026-11-09")
                val today = model.state.obj("days").obj("2026-11-09")
                check(today.optDouble("h") == 2.5 && today.obj("t").optBoolean("words")) {
                    "Screenshot seed was not loaded: ${model.state}"
                }
                val week = model.state.obj("weeks").obj("2026-11-09")
                check(week.arr("must").optString(0) == "生理第1–3章网课+讲义")
            }
            compose.onNodeWithText("从网页导出 JSON 导入").assertDoesNotExist()

            fun capture(name: String) {
                val suffix = if (dark) "-dark" else ""
                scenario.onActivity { it.window.decorView.captureRoboImage(File(outputDir, "app-$name$suffix.png")) }
            }

            tabs.forEachIndexed { index, (label, name) ->
                compose.onNodeWithText(label).performClick()
                compose.waitForIdle()
                scenario.onActivity {
                    check(model.page == index) { "Tab $label did not open" }
                }
                capture(name)
            }

            compose.onNodeWithText("今日").performClick()
            compose.onNodeWithContentDescription("查看学习进度预测").assertIsDisplayed()
            compose.onNodeWithContentDescription("新词 数量").performScrollTo().assertIsDisplayed()
            capture("today-words")
            compose.onNodeWithText("阶段计划与安装帮助").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { model.toggleTask(model.today, "words") }
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithText("撤销").fetchSemanticsNodes().isNotEmpty()
            }
            capture("today-undo-overlap")
            compose.onNodeWithContentDescription("新词 数量").performScrollTo().assertIsDisplayed()
            capture("today-undo")
            compose.onNodeWithText("撤销").performClick()

            compose.onNodeWithText("进度").performClick()
            compose.onNodeWithText("学习进度预测").performScrollTo().assertIsDisplayed()
            capture("prog-forecast")
            compose.onNodeWithText("未开始：", substring = true).performScrollTo().assertIsDisplayed()
            capture("prog-unstarted")
            compose.onNodeWithText("0/15").assertDoesNotExist()
            compose.onNodeWithText("章节进度").performScrollTo()
            capture("prog-chapters")
            compose.onNodeWithText("按当前速度，预计", substring = true).performScrollTo().assertIsDisplayed()
            capture("prog-estimate")
            compose.onNode(hasText("病理") and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithText("淋巴造血系统疾病").performScrollTo().assertIsDisplayed()
            capture("prog-path")
            compose.onNode(hasText("内科") and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithText("诊断学（常见症状与体格检查）").performScrollTo().assertIsDisplayed()
            capture("prog-med")
            compose.onNode(hasText("人文") and hasClickAction()).performScrollTo().performClick()
            compose.onNodeWithText("卫生法律法规").performScrollTo().assertIsDisplayed()
            capture("prog-humanities")

            compose.onNodeWithText("今日").performClick()
            compose.waitForIdle()
            lateinit var stateBeforeForecast: JSONObject
            compose.runOnIdle { stateBeforeForecast = model.state.copyJson() }
            compose.runOnIdle {
                model.state.obj("ch").put("phys", JSONArray((0 until 12).map { it < 5 }))
                model.state.obj("cf").put("phys", "2026-10-20")
                val start = LocalDate.parse("2026-10-27")
                repeat(14) { offset ->
                    model.state.obj("days").put(
                        start.plusDays(offset.toLong()).toString(),
                        JSONObject().put("w", JSONObject().put("n", 45)).put("h", 2.0),
                    )
                }
            }
            compose.onNodeWithText("进度").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("学习进度预测").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("生理：约 12月9日 学完", substring = true).assertIsDisplayed()
            compose.onNodeWithText("预计 1月28日 过完第一轮", substring = true).assertIsDisplayed()
            capture("prog-forecast-estimated")
            compose.runOnIdle {
                val state = model.state
                state.keys().asSequence().toList().forEach(state::remove)
                stateBeforeForecast.keys().forEach { state.put(it, stateBeforeForecast.get(it)) }
            }
            compose.onNodeWithText("今日").performClick()

            CheckinCore.clock = Clock.fixed(Instant.parse("2027-01-20T13:30:00Z"), CheckinCore.shanghai)
            compose.runOnIdle { model.resume() }
            compose.onNodeWithText("今日").performClick()
            compose.onNodeWithText("小时 / 日目标 3.5h").performScrollTo().assertIsDisplayed()
            capture("winter-today")
            compose.onNodeWithText("周目标").performClick()
            compose.onNodeWithText("打卡达标 0/3 天 · 时长 0.0h / 目标 10.5h").assertIsDisplayed()
            capture("winter-week")
            // Set up a separate presentation fixture without depending on Room's async writer.
            compose.runOnIdle {
                model.state.obj("ch").put("hum", JSONArray().put(true))
                model.page = 3
            }
            compose.onNodeWithText("周目标").performClick()
            compose.onNodeWithText("人文 1/4章", substring = true).performScrollTo().assertIsDisplayed()
            capture("winter-week-humanities")
            compose.onNodeWithText("统计").performClick()
            compose.onNodeWithText("近 8 周学习时长（橙=未达目标）").performScrollTo().assertIsDisplayed()
            capture("winter-stats")
            compose.runOnIdle { model.notify("示例提示：已保存学习记录") }
            compose.onNodeWithText("示例提示：已保存学习记录").assertIsDisplayed()
            compose.onNodeWithText("撤销").assertDoesNotExist()
            capture("notice-snackbar")
            compose.onNodeWithText("今日").performClick()
            capture("tabbar")
        }
    }
}
