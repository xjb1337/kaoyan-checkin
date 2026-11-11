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

/** Page and brand tokens. Status and subject colors remain the same across skins. */
data class WebColors(
    val bg: Color, val card: Color, val text: Color, val sub: Color, val line: Color,
    val accent: Color, val red: Color, val amber: Color, val green: Color,
    val blueTag: Color, val greenTag: Color, val orangeTag: Color, val redTag: Color, val purpleTag: Color,
    val dark: Boolean,
    // Opaque brand color for controls with white text, including in dark mode.
    val blue: Color = Color(0xFF2563EB),
) {
    val subjectBlue = Color(0xFF2563EB)
    val progressHighlight = if (blue == subjectBlue) Color(0xFF60A5FA) else accent
    val greenStrong = Color(0xFF059669)
    val purple = Color(0xFF7C3AED)
    val orange = Color(0xFFD97706)
    val chipBlue = blue.copy(alpha = .12f)
    val chipSubjectBlue = subjectBlue.copy(alpha = .12f)
    val chipGreen = greenStrong.copy(alpha = .12f)
    val chipOrange = orange.copy(alpha = .14f)
    val chipRed = Color(0xFFDC2626).copy(alpha = .12f)
    val chipPurple = purple.copy(alpha = .12f)
}

// These are the existing website palette, unchanged for installations without a preference.
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

/** Both Compose and the RemoteViews widget use this same palette selection. */
internal fun paletteFor(theme: AppTheme, dark: Boolean): WebColors {
    val base = if (dark) DarkWebColors else LightWebColors
    return when (theme) {
        AppTheme.WEB_BLUE -> base
        AppTheme.INK_TEAL -> if (dark) base.copy(
            bg = Color(0xFF0D1A1C), card = Color(0xFF17292B), text = Color(0xFFE3F0EF),
            sub = Color(0xFFB2CAC9), line = Color(0xFF2B4446), accent = Color(0xFF8CD4D1), blue = Color(0xFF126C76),
        ) else base.copy(
            bg = Color(0xFFF0F6F5), card = Color.White, text = Color(0xFF193739),
            sub = Color(0xFF536F70), line = Color(0xFFE0ECEA), accent = Color(0xFF126C76), blue = Color(0xFF126C76),
        )
        AppTheme.WARM_APRICOT -> if (dark) base.copy(
            bg = Color(0xFF211710), card = Color(0xFF33241C), text = Color(0xFFF8EAE0),
            sub = Color(0xFFD6BDAE), line = Color(0xFF513A2C), accent = Color(0xFFF4C19E), blue = Color(0xFF9E491A),
        ) else base.copy(
            bg = Color(0xFFFAF4EE), card = Color(0xFFFFFCF9), text = Color(0xFF38291F),
            sub = Color(0xFF765D50), line = Color(0xFFF0E4D9), accent = Color(0xFF9E491A), blue = Color(0xFF9E491A),
        )
        AppTheme.NIGHT_VIOLET -> if (dark) base.copy(
            bg = Color(0xFF171421), card = Color(0xFF282238), text = Color(0xFFF0EAF8),
            sub = Color(0xFFC6BDD5), line = Color(0xFF443B56), accent = Color(0xFFD0BAF7), blue = Color(0xFF65429C),
        ) else base.copy(
            bg = Color(0xFFF5F3F9), card = Color.White, text = Color(0xFF302A3C),
            sub = Color(0xFF6F657D), line = Color(0xFFEAE5F0), accent = Color(0xFF65429C), blue = Color(0xFF65429C),
        )
        AppTheme.BAMBOO_GREEN -> if (dark) base.copy(
            bg = Color(0xFF111C15), card = Color(0xFF1D2D22), text = Color(0xFFE7F1E8),
            sub = Color(0xFFB7CEBB), line = Color(0xFF354B3A), accent = Color(0xFFA8D9B0), blue = Color(0xFF326B45),
        ) else base.copy(
            bg = Color(0xFFF1F6F0), card = Color.White, text = Color(0xFF26392B),
            sub = Color(0xFF5C7060), line = Color(0xFFE2ECE2), accent = Color(0xFF326B45), blue = Color(0xFF326B45),
        )
    }
}

private val LocalWebColors = staticCompositionLocalOf { LightWebColors }
val webColors: WebColors @Composable get() = LocalWebColors.current

@Composable
fun WebTheme(theme: AppTheme = AppTheme.DEFAULT, darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = paletteFor(theme, darkTheme)
    // Explicit schemes prevent Material You from overriding the selected palette.
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
