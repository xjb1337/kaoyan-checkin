package com.xjb.kaoyancheckin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xjb.kaoyancheckin.ui.theme.WebColors
import com.xjb.kaoyancheckin.ui.theme.webColors

internal data class UndoSnackbarColors(
    val container: Color,
    val content: Color,
    val action: Color,
)

/**
 * Dark themes use a lifted opaque card surface; light themes use the page's inverse surface.
 * The action color is deliberately checked against the container with WCAG contrast tests.
 */
internal fun undoSnackbarColors(palette: WebColors): UndoSnackbarColors =
    if (palette.dark) {
        UndoSnackbarColors(
            container = lerp(palette.card, palette.text, 0.10f),
            content = palette.text,
            action = palette.accent,
        )
    } else {
        UndoSnackbarColors(
            container = palette.text,
            content = palette.card,
            action = lerp(palette.accent, Color.White, 0.45f),
        )
    }

@Composable
internal fun UndoSnackbar(
    message: String,
    actionLabel: String = "撤销",
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = undoSnackbarColors(webColors)
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .shadow(8.dp, shape, clip = false)
            .clip(shape)
            .background(colors.container)
            .heightIn(min = 52.dp)
            .padding(start = 16.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            message,
            Modifier.weight(1f).padding(vertical = 7.dp),
            color = colors.content,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 44.dp)) {
            Text(actionLabel, color = colors.action, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun UndoSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier) { data ->
        val background = webColors.bg
        Box(
            Modifier.fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0f to background.copy(alpha = 0f),
                        0.22f to background.copy(alpha = 0.88f),
                        0.50f to background,
                        1f to background,
                    ),
                )
                .padding(top = 24.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            UndoSnackbar(
                message = data.visuals.message,
                actionLabel = data.visuals.actionLabel ?: "撤销",
                onAction = { data.performAction() },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
