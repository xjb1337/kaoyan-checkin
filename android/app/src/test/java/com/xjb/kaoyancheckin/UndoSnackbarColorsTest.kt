package com.xjb.kaoyancheckin

import androidx.compose.ui.graphics.Color
import com.xjb.kaoyancheckin.ui.theme.AppTheme
import com.xjb.kaoyancheckin.ui.theme.paletteFor
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class UndoSnackbarColorsTest {
    @Test
    fun everyThemeAndModeHasADistinctReadableSnackbar() {
        AppTheme.entries.forEach { theme ->
            listOf(false, true).forEach { dark ->
                val palette = paletteFor(theme, dark)
                val colors = undoSnackbarColors(palette)
                val label = "${theme.name}-${if (dark) "dark" else "light"}"

                assertNotEquals("$label container must differ from card", palette.card, colors.container)
                assertTrue(
                    "$label container must be visually lifted from card",
                    contrastRatio(colors.container, palette.card) >= 1.2,
                )
                assertTrue(
                    "$label message contrast is too low",
                    contrastRatio(colors.content, colors.container) >= 4.5,
                )
                assertTrue(
                    "$label action contrast is too low",
                    contrastRatio(colors.action, colors.container) >= 4.5,
                )
            }
        }
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val first = relativeLuminance(foreground)
        val second = relativeLuminance(background)
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * linearChannel(color.red) +
            0.7152 * linearChannel(color.green) +
            0.0722 * linearChannel(color.blue)

    private fun linearChannel(channel: Float): Double {
        val value = channel.toDouble()
        return if (value <= 0.04045) value / 12.92
        else ((value + 0.055) / 1.055).pow(2.4)
    }
}
