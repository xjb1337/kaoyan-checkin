package com.xjb.kaoyancheckin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import com.xjb.kaoyancheckin.ui.theme.WebColors
import kotlin.math.min
import kotlin.math.roundToInt

/** Small RemoteViews bitmaps let every palette share rounded, custom graphics on Android 8+. */
internal object WidgetArtwork {
    fun backdrop(width: Int, height: Int, density: Float, c: WebColors): Bitmap {
        // Bound memory per widget even on unusually large launcher grids.
        val scale = min(density, 1024f / maxOf(width, height, 1))
        val w = (width.coerceAtLeast(1) * scale).roundToInt().coerceAtLeast(1)
        val h = (height.coerceAtLeast(1) * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val bounds = RectF(0f, 0f, w.toFloat(), h.toFloat())
        paint.color = c.card.toArgb()
        canvas.drawRoundRect(bounds, 18 * scale, 18 * scale, paint)
        return bitmap
    }

    fun header(width: Int, density: Float, c: WebColors, status: WidgetStatus, satisfied: Boolean): Bitmap {
        val scale = min(density, 1024f / width.coerceAtLeast(1))
        val w = (width.coerceAtLeast(1) * scale).roundToInt().coerceAtLeast(1)
        val h = (34 * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = min(18 * scale, w / 2f)
        val path = Path().apply {
            moveTo(0f, h.toFloat())
            lineTo(0f, radius)
            quadTo(0f, 0f, radius, 0f)
            lineTo(w - radius, 0f)
            quadTo(w.toFloat(), 0f, w.toFloat(), radius)
            lineTo(w.toFloat(), h.toFloat())
            close()
        }
        canvas.clipPath(path)
        paint.color = c.accent.copy(alpha = if (c.dark) .14f else .09f).compositeOver(c.card).toArgb()
        val headerBottom = h.toFloat()
        canvas.drawRect(0f, 0f, w.toFloat(), headerBottom, paint)
        val barTop = (headerBottom - 2 * scale).coerceAtLeast(0f)
        paint.color = c.accent.copy(alpha = .16f).compositeOver(c.card).toArgb()
        canvas.drawRect(0f, barTop, w.toFloat(), headerBottom, paint)
        paint.color = (if (satisfied) c.greenTag else c.accent).toArgb()
        canvas.drawRect(0f, barTop, w * status.progress, headerBottom, paint)
        return bitmap
    }

    fun badge(density: Float, c: WebColors): Bitmap {
        val width = (64 * density).roundToInt().coerceAtLeast(1)
        val height = (24 * density).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val header = c.accent.copy(alpha = if (c.dark) .14f else .09f).compositeOver(c.card)
            color = c.accent.copy(alpha = if (c.dark) .16f else .12f).compositeOver(header).toArgb()
        }
        Canvas(bitmap).drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), height / 2f, height / 2f, paint)
        return bitmap
    }

    fun check(density: Float, c: WebColors, done: Boolean): Bitmap {
        val size = (21 * density).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (if (done) c.blue else c.accent).toArgb()
            style = if (done) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = 1.7f * density
        }
        val center = size / 2f
        canvas.drawCircle(center, center, size * .43f, paint)
        if (done) {
            paint.color = Color.White.toArgb()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            val tick = Path().apply {
                moveTo(size * .25f, size * .51f)
                lineTo(size * .43f, size * .69f)
                lineTo(size * .77f, size * .32f)
            }
            canvas.drawPath(tick, paint)
        }
        return bitmap
    }
}
