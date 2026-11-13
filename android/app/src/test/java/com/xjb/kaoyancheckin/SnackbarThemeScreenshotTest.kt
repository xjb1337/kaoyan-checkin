package com.xjb.kaoyancheckin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.xjb.kaoyancheckin.ui.theme.AppTheme
import com.xjb.kaoyancheckin.ui.theme.WebTheme
import com.xjb.kaoyancheckin.ui.theme.webColors
import java.io.File
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class SnackbarThemeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private var theme by mutableStateOf(AppTheme.WEB_BLUE)
    private var dark by mutableStateOf(false)

    @Test
    fun captureEveryTheme() {
        val outputDir = File(checkNotNull(System.getProperty("screenshotDir"))).apply {
            check(isDirectory || mkdirs()) { "Cannot create $this" }
        }
        compose.setContent {
            WebTheme(theme = theme, darkTheme = dark) {
                SnackbarPreview()
            }
        }

        AppTheme.entries.forEach { candidate ->
            listOf(false, true).forEach { candidateDark ->
                compose.runOnUiThread {
                    theme = candidate
                    dark = candidateDark
                }
                compose.waitForIdle()
                val mode = if (candidateDark) "dark" else "light"
                val name = "snackbar-${candidate.name.lowercase(Locale.ROOT)}-$mode.png"
                compose.onRoot().captureRoboImage(File(outputDir, name))
            }
        }
    }

    @Composable
    private fun SnackbarPreview() {
        val colors = webColors
        Box(Modifier.fillMaxSize().background(colors.bg)) {
            Column(
                Modifier.align(Alignment.TopCenter)
                    .widthIn(max = 540.dp)
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(5) { index ->
                    Section("示例卡片 ${index + 1}") {
                        Text(
                            "用于检查撤销提示下方的卡片边框不会透出，也不会形成第二层框。",
                            color = colors.sub,
                        )
                    }
                }
            }
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .widthIn(max = 540.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                UndoSnackbar("已勾选「英语单词」", onAction = {})
            }
        }
    }
}
