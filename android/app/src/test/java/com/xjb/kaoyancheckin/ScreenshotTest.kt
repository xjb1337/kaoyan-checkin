package com.xjb.kaoyancheckin

import android.app.Application
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
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
    private val screenshotInstant = Instant.parse("2026-11-09T13:30:00Z")

    @Before
    fun seedDatabase() {
        previousTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        previousClock = CheckinCore.clock
        CheckinCore.clock = Clock.fixed(screenshotInstant, CheckinCore.shanghai)

        val app = ApplicationProvider.getApplicationContext<Application>()
        app.deleteDatabase("checkin.db")
        check(!app.getDatabasePath("checkin.db").exists()) { "Could not reset screenshot database" }
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
        CheckinCore.clock = previousClock
        TimeZone.setDefault(previousTimeZone)
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

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: CheckinViewModel
            scenario.onActivity { model = ViewModelProvider(it)[CheckinViewModel::class.java] }
            // The ViewModel loads Room asynchronously; wait for content rather than a loading frame.
            compose.waitUntil(timeoutMillis = 15_000) { model.ready }
            compose.runOnIdle {
                check(model.loadError.isEmpty()) { model.loadError }
                check(model.today == "2026-11-09")
                val today = model.state.obj("days").obj("2026-11-09")
                check(today.optDouble("h") == 2.5 && today.obj("t").optBoolean("words"))
                val week = model.state.obj("weeks").obj("2026-11-09")
                check(week.arr("must").optString(0) == "生理第1–3章网课+讲义")
            }
            compose.onNodeWithText("从网页导出 JSON 导入").assertDoesNotExist()

            tabs.forEachIndexed { index, (label, name) ->
                compose.onNodeWithText(label).performClick()
                compose.waitForIdle()
                val suffix = if (dark) "-dark" else ""
                val output = File(outputDir, "app-$name$suffix.png")
                scenario.onActivity {
                    check(model.page == index) { "Tab $label did not open" }
                    it.window.decorView.captureRoboImage(output)
                }
            }
        }
    }
}
