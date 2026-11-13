package com.xjb.kaoyancheckin

import android.app.Application
import android.graphics.drawable.ColorDrawable
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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
            compose.waitUntil(timeoutMillis = 15_000) { model.ready }
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertCountEquals(6)
            compose.onNodeWithText("今日").assertIsSelected()
            compose.onNodeWithText("进度").performClick().assertIsSelected()
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
                compose.onNodeWithText("进度").assertIsSelected()
            }
        }
    }
}
