package com.xjb.kaoyancheckin

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.xjb.kaoyancheckin.ui.theme.AppTheme
import com.xjb.kaoyancheckin.ui.theme.paletteFor
import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeRepositoryTest {
    private lateinit var app: Application

    @Before fun reset() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences(ThemeRepository.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun unknownOrMissingNamesFallBackToWebsiteBlue() {
        assertEquals(AppTheme.WEB_BLUE, AppTheme.from(null))
        assertEquals(AppTheme.WEB_BLUE, AppTheme.from("removed_theme"))
        val prefs = app.getSharedPreferences(ThemeRepository.PREFERENCES, Context.MODE_PRIVATE)
        assertEquals(AppTheme.WEB_BLUE, ThemeRepository(app).load())
        prefs.edit().putString(ThemeRepository.KEY_THEME, "unknown").commit()
        assertEquals(AppTheme.WEB_BLUE, ThemeRepository(app).load())
        assertEquals(Color(0xFF2563EB), paletteFor(AppTheme.DEFAULT, false).accent)
        assertEquals(Color(0xFF93C5FD), paletteFor(AppTheme.DEFAULT, true).accent)
    }

    @Test fun everyThemeRoundTripsByNameInSeparatePreferences() {
        val prefs = app.getSharedPreferences(ThemeRepository.PREFERENCES, Context.MODE_PRIVATE)
        AppTheme.entries.forEach { theme ->
            ThemeRepository(app).save(theme)
            assertEquals(theme.name, prefs.getString(ThemeRepository.KEY_THEME, null))
            assertEquals(theme, ThemeRepository(app).load())
        }
        assertFalse(CheckinCore.normalizeState(CheckinCore.emptyState()).obj("settings").has("theme"))
    }

    @Test fun semanticStatusColorsDoNotChangeWithSkin() {
        listOf(false, true).forEach { dark ->
            val baseline = paletteFor(AppTheme.DEFAULT, dark)
            AppTheme.entries.forEach { theme ->
                val colors = paletteFor(theme, dark)
                assertEquals(baseline.red, colors.red)
                assertEquals(baseline.amber, colors.amber)
                assertEquals(baseline.green, colors.green)
                assertEquals(baseline.blueTag, colors.blueTag)
            }
        }
    }

    @Test fun selectingThemePersistsBeforeRefreshingWidgets() {
        val observed = mutableListOf<AppTheme>()
        val model = ThemeViewModel(app) { context -> observed += ThemeRepository(context).load() }
        model.select(AppTheme.INK_TEAL)
        model.select(AppTheme.INK_TEAL)
        model.select(AppTheme.NIGHT_VIOLET)
        assertEquals(listOf(AppTheme.INK_TEAL, AppTheme.NIGHT_VIOLET), observed)
        assertEquals(AppTheme.NIGHT_VIOLET, model.theme)
    }
}
