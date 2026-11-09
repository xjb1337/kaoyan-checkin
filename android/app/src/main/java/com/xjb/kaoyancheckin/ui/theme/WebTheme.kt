package com.xjb.kaoyancheckin.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Tokens taken from index.html :root and both prefers-color-scheme overrides. */
data class WebColors(
    val bg: Color, val card: Color, val text: Color, val sub: Color, val line: Color,
    val accent: Color, val red: Color, val amber: Color, val green: Color,
    val blueTag: Color, val greenTag: Color, val orangeTag: Color, val redTag: Color, val purpleTag: Color,
    val dark: Boolean,
) {
    // The web keeps these translucent backgrounds and the gradient fixed in both themes.
    val blue = Color(0xFF2563EB)
    val greenStrong = Color(0xFF059669)
    val purple = Color(0xFF7C3AED)
    val orange = Color(0xFFD97706)
    val chipBlue = blue.copy(alpha = .12f)
    val chipGreen = greenStrong.copy(alpha = .12f)
    val chipOrange = orange.copy(alpha = .14f)
    val chipRed = Color(0xFFDC2626).copy(alpha = .12f)
    val chipPurple = purple.copy(alpha = .12f)
}

private val LightWebColors = WebColors(
    Color(0xFFF2F4F7), Color.White, Color(0xFF1F2937), Color(0xFF6B7280), Color(0xFFEEF0F3),
    Color(0xFF2563EB), Color(0xFFEF4444), Color(0xFFD97706), Color(0xFF16A34A),
    Color(0xFF2563EB), Color(0xFF059669), Color(0xFFD97706), Color(0xFFDC2626), Color(0xFF7C3AED), false,
)
private val DarkWebColors = WebColors(
    Color(0xFF0F1420), Color(0xFF1A2130), Color(0xFFE5E7EB), Color(0xFFB1BBCC), Color(0xFF263043),
    Color(0xFF93C5FD), Color(0xFFFCA5A5), Color(0xFFFCD34D), Color(0xFF86EFAC),
    Color(0xFF93C5FD), Color(0xFF6EE7B7), Color(0xFFFCD34D), Color(0xFFFCA5A5), Color(0xFFC4B5FD), true,
)
private val LocalWebColors = staticCompositionLocalOf { LightWebColors }
val webColors: WebColors @Composable get() = LocalWebColors.current

@Composable
fun WebTheme(content: @Composable () -> Unit) {
    val c = if (isSystemInDarkTheme()) DarkWebColors else LightWebColors
    // Explicit schemes prevent Material You from overriding the web palette.
    val scheme = (if (c.dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.accent, onPrimary = if (c.dark) c.bg else Color.White,
        primaryContainer = c.chipBlue, onPrimaryContainer = c.accent, inversePrimary = c.accent,
        secondary = c.green, onSecondary = if (c.dark) c.bg else Color.White,
        secondaryContainer = c.chipGreen, onSecondaryContainer = c.greenTag,
        tertiary = c.amber, onTertiary = if (c.dark) c.bg else Color.White,
        tertiaryContainer = c.chipOrange, onTertiaryContainer = c.amber,
        background = c.bg, onBackground = c.text, surface = c.card, onSurface = c.text,
        surfaceVariant = c.bg, onSurfaceVariant = c.sub, surfaceTint = c.accent,
        inverseSurface = c.text, inverseOnSurface = c.card,
        error = c.red, onError = if (c.dark) c.bg else Color.White,
        errorContainer = c.chipRed, onErrorContainer = c.red,
        outline = c.line, outlineVariant = c.line, scrim = Color.Black,
        surfaceBright = c.card, surfaceDim = c.bg, surfaceContainer = c.card,
        surfaceContainerHigh = c.card, surfaceContainerHighest = c.card,
        surfaceContainerLow = c.card, surfaceContainerLowest = c.card,
    )
    val typography = Typography(
        bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
        titleLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
        titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
        titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .25.sp),
        labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    )
    CompositionLocalProvider(LocalWebColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
