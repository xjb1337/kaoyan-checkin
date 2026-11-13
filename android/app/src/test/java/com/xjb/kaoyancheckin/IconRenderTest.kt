package com.xjb.kaoyancheckin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xhdpi")
class IconRenderTest {
    private val iconSize = 432

    @Test
    fun renderAdaptiveIconLayers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val outputDir = File(checkNotNull(System.getProperty("screenshotDir"))).apply {
            check(isDirectory || mkdirs()) { "Cannot create $this" }
        }
        val adaptive = checkNotNull(context.getDrawable(R.mipmap.ic_launcher) as? AdaptiveIconDrawable)
        val background = checkNotNull(adaptive.background)
        val foreground = checkNotNull(adaptive.foreground)

        renderFull(background, foreground, File(outputDir, "icon-full.png"))
        renderMasked(
            background,
            foreground,
            Path().apply { addCircle(iconSize / 2f, iconSize / 2f, iconSize / 2f, Path.Direction.CW) },
            File(outputDir, "icon-circle.png"),
        )
        renderMasked(
            background,
            foreground,
            Path().apply {
                addRoundRect(
                    RectF(0f, 0f, iconSize.toFloat(), iconSize.toFloat()),
                    iconSize * 0.22f,
                    iconSize * 0.22f,
                    Path.Direction.CW,
                )
            },
            File(outputDir, "icon-squircle.png"),
        )

        val monochrome = checkNotNull(context.getDrawable(R.drawable.ic_launcher_monochrome))
        renderDrawable(monochrome, File(outputDir, "icon-monochrome.png"), Color.rgb(15, 23, 42))

        val legacy = checkNotNull(context.getDrawable(R.drawable.ic_launcher))
        renderDrawable(legacy, File(outputDir, "icon-legacy.png"))
    }

    private fun renderFull(background: Drawable, foreground: Drawable, output: File) {
        val bitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawLayer(canvas, background)
        drawLayer(canvas, foreground)

        val inset = iconSize * 21f / 108f
        val safeBounds = RectF(inset, inset, iconSize - inset, iconSize - inset)
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            color = Color.argb(110, 0, 0, 0)
        }
        val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.argb(235, 255, 255, 255)
            pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        }
        canvas.drawRect(safeBounds, outline)
        canvas.drawRect(safeBounds, dashed)
        bitmap.captureRoboImage(output)
    }

    private fun renderMasked(background: Drawable, foreground: Drawable, mask: Path, output: File) {
        val bitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.clipPath(mask)
        drawLayer(canvas, background)
        drawLayer(canvas, foreground)
        canvas.restore()
        bitmap.captureRoboImage(output)
    }

    private fun renderDrawable(drawable: Drawable, output: File, backgroundColor: Int? = null) {
        val bitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (backgroundColor != null) canvas.drawColor(backgroundColor)
        drawLayer(canvas, drawable)
        bitmap.captureRoboImage(output)
    }

    private fun drawLayer(canvas: Canvas, drawable: Drawable) {
        drawable.setBounds(0, 0, iconSize, iconSize)
        drawable.draw(canvas)
    }
}
