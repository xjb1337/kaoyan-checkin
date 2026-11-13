package com.xjb.kaoyancheckin

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.ContextThemeWrapper
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.xjb.kaoyancheckin.ui.theme.AppTheme
import com.xjb.kaoyancheckin.ui.theme.paletteFor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class StartupThemeTest {
    @Test @Config(qualifiers = "notnight")
    fun lightLaunchUsesLightSurfacesAndDarkSystemIcons() = verify(false)

    @Test @Config(qualifiers = "night")
    fun darkLaunchNeverUsesLightWindowOrSplashBackground() = verify(true)

    private fun verify(dark: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val context = ContextThemeWrapper(app, R.style.Theme_Checkin)
        val attributes = context.obtainStyledAttributes(intArrayOf(
            android.R.attr.windowBackground, android.R.attr.statusBarColor, android.R.attr.navigationBarColor,
            android.R.attr.windowLightStatusBar, android.R.attr.windowLightNavigationBar,
            android.R.attr.windowSplashScreenBackground,
        ))
        try {
            val expected = paletteFor(AppTheme.DEFAULT, dark).bg.toArgb()
            assertEquals(expected, (attributes.getDrawable(0) as ColorDrawable).color)
            assertEquals(expected, attributes.getColor(1, 0))
            assertEquals(expected, attributes.getColor(2, 0))
            assertEquals(!dark, attributes.getBoolean(3, dark))
            assertEquals(!dark, attributes.getBoolean(4, dark))
            if (Build.VERSION.SDK_INT >= 31) assertEquals(expected, attributes.getColor(5, 0))
        } finally { attributes.recycle() }
    }
}
