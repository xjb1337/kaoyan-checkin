package com.xjb.kaoyancheckin

import android.app.Application
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.xjb.kaoyancheckin.ui.theme.AppTheme
import com.xjb.kaoyancheckin.ui.theme.paletteFor
import java.time.Clock
import java.time.Instant
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
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
class SystemBarsAndTabsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var database: TestDatabase

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        UpdateServices.backendOverride = NoNetworkUpdateBackend
        app.getSharedPreferences(ThemeRepository.PREFERENCES, 0).edit().clear().commit()
        database = TestDatabase(app)
    }

    @After fun tearDown() {
        try { database.close() } finally { UpdateServices.backendOverride = null }
    }

    @Test @Config(qualifiers = "notnight")
    fun lightSystemBarsAndVectorTabsFollowEveryPalette() = verify(false)

    @Test @Config(qualifiers = "night")
    fun darkSystemBarsAndVectorTabsFollowEveryPalette() = verify(true)

    private fun verify(dark: Boolean) {
        database.launchActivity().use { scenario ->
            lateinit var model: CheckinViewModel
            lateinit var appearance: ThemeViewModel
            scenario.onActivity {
                model = ViewModelProvider(it)[CheckinViewModel::class.java]
                appearance = ViewModelProvider(it)[ThemeViewModel::class.java]
            }
            compose.waitUntil(timeoutMillis = 15_000) {
                shadowOf(Looper.getMainLooper()).idle()
                model.ready
            }
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertCountEquals(4)
            compose.onNodeWithText("今日").assertIsSelected()
            compose.onNodeWithText("计划").performClick().assertIsSelected()
            AppTheme.entries.forEach { theme ->
                compose.runOnIdle { appearance.select(theme) }
                compose.waitForIdle()
                scenario.onActivity {
                    val colors = paletteFor(theme, dark)
                    assertEquals(colors.bg.toArgb(), (it.window.decorView.background as ColorDrawable).color)
                    val controller = WindowCompat.getInsetsController(it.window, it.window.decorView)
                    assertEquals(!dark, controller.isAppearanceLightStatusBars)
                    assertEquals(!dark, controller.isAppearanceLightNavigationBars)
                    assertEquals(android.graphics.Color.TRANSPARENT, it.window.statusBarColor)
                    assertEquals(android.graphics.Color.TRANSPARENT, it.window.navigationBarColor)
                    assertEquals(1, model.page)
                }
                compose.onNodeWithText("计划").assertIsSelected()
            }
        }
    }

    @Test
    fun subTabSelectionSurvivesMainTabSwitchAndWordsQuickAddCapsAt2000() {
        val previousClock = CheckinCore.clock
        CheckinCore.clock = Clock.fixed(Instant.parse("2026-11-10T04:00:00Z"), CheckinCore.shanghai)
        try {
            database.launchActivity().use { scenario ->
                lateinit var model: CheckinViewModel
                scenario.onActivity { model = ViewModelProvider(it)[CheckinViewModel::class.java] }
                compose.waitUntil(timeoutMillis = 15_000) {
                    shadowOf(Looper.getMainLooper()).idle()
                    model.ready
                }
                compose.onNodeWithText("计划").performClick()
                compose.onNodeWithText("阶段节点").performClick().assertIsSelected()
                compose.onNodeWithText("今日").performClick()
                compose.onNodeWithText("计划").performClick()
                compose.onNodeWithText("阶段节点").assertIsSelected()

                val date = "2026-11-10"
                compose.runOnIdle {
                    model.adjustWords(date, "n", 20)
                    model.adjustWords(date, "n", 2500)
                }
                compose.waitUntil(timeoutMillis = 15_000) {
                    shadowOf(Looper.getMainLooper()).idle()
                    model.state.obj("days").optJSONObject(date)?.obj("w")?.optInt("n") == 2000
                }
                assertEquals(2000, model.state.obj("days").obj(date).obj("w").optInt("n"))
            }
        } finally {
            CheckinCore.clock = previousClock
        }
    }
}
