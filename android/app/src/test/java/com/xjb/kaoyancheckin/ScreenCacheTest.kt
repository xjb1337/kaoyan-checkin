package com.xjb.kaoyancheckin

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenCacheTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unrelatedRecompositionsReuseCalculationsButRecordsAndMidnightInvalidateThem() {
        val plan = StudyPlan(JSONObject(File("src/main/assets/plan.json").readText()))
        var state by mutableStateOf(CheckinCore.emptyState())
        var today by mutableStateOf("2026-11-09")
        var unrelated by mutableIntStateOf(0)
        lateinit var progress: ProgressCalculations
        lateinit var statistics: StatsCalculations
        var summary = ""
        var compositions = 0
        compose.setContent {
            val currentProgress = rememberProgressCalculations(plan, state, today)
            val currentStatistics = rememberStatsCalculations(plan, state, today)
            val currentSummary = rememberTodayForecast(plan, state, today)
            Text("无关界面状态 $unrelated")
            SideEffect {
                progress = currentProgress
                statistics = currentStatistics
                summary = currentSummary
                compositions++
            }
        }
        compose.waitForIdle()
        val originalProgress = progress
        val originalStatistics = statistics
        val originalSummary = summary
        val initialCompositions = compositions
        compose.runOnIdle { unrelated++ }
        compose.runOnIdle {
            assertTrue(compositions > initialCompositions)
            assertSame(originalProgress, progress)
            assertSame(originalStatistics, statistics)
            assertSame(originalSummary, summary)
            val changed = state.copyJson()
            changed.obj("days").put(today, StudyPlan.day().put("h", 2.0).put("w", JSONObject().put("n", 40)))
            state = changed
        }
        compose.runOnIdle {
            assertNotSame(originalProgress, progress)
            assertNotSame(originalStatistics, statistics)
            assertEquals(40, progress.words.todayNew)
            assertEquals(2.0, statistics.totalHours, 0.0)
        }
        val beforeMidnightProgress = progress
        val beforeMidnightStatistics = statistics
        compose.runOnIdle { today = "2026-11-10" }
        compose.runOnIdle {
            assertNotSame(beforeMidnightProgress, progress)
            assertNotSame(beforeMidnightStatistics, statistics)
            assertEquals(0, progress.words.todayNew)
            assertEquals(2.0, statistics.totalHours, 0.0)
        }
    }
}
