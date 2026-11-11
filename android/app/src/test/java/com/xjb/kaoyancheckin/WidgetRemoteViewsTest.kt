package com.xjb.kaoyancheckin

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.xjb.kaoyancheckin.ui.theme.AppTheme
import com.xjb.kaoyancheckin.ui.theme.paletteFor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WidgetRemoteViewsTest {
    private val snapshot = WidgetSnapshot(
        "2026-11-10", listOf(
            WidgetTask("words", "英语单词：复习已学词和新词，大约二十分钟", true),
            WidgetTask("drill", "西综：当前科目学习或复习至少二十分钟，可以包含练习", false),
        ), 1, 2, false, 3, false,
    )

    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun lightWidgetHasTwoStretchingRowsAndTwoLineLabels() = verify(dark = false)

    @Test @Config(qualifiers = "w412dp-h915dp-night-xhdpi")
    fun darkWidgetHasSameContentAndCorrectPalette() = verify(dark = true)

    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun savedWidgetInflatesForTheHostsNewNightConfiguration() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        ThemeRepository(activity).save(AppTheme.NIGHT_VIOLET)
        val savedViews = CheckinWidget.views(activity, 1, snapshot, 300, 130)
        val nightConfig = Configuration(activity.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        val root = savedViews.apply(activity.createConfigurationContext(nightConfig), null)
        val light = root.findViewById<FrameLayout>(R.id.widget_light)
        val dark = root.findViewById<FrameLayout>(R.id.widget_dark)
        assertEquals(View.GONE, light.visibility)
        assertEquals(View.VISIBLE, dark.visibility)
        assertEquals(paletteFor(AppTheme.NIGHT_VIOLET, true).text.toArgb(),
            dark.getChildAt(0).findViewById<TextView>(R.id.widget_title).currentTextColor)
    }

    private fun verify(dark: Boolean) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        ThemeRepository(activity).save(AppTheme.INK_TEAL)
        val root = CheckinWidget.views(activity, 1, snapshot, 300, 130).apply(activity, null)
        val size = activity.resources.displayMetrics.density
        root.measure(View.MeasureSpec.makeMeasureSpec((300 * size).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((130 * size).toInt(), View.MeasureSpec.EXACTLY))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        val light = root.findViewById<FrameLayout>(R.id.widget_light)
        val night = root.findViewById<FrameLayout>(R.id.widget_dark)
        assertEquals(if (dark) View.GONE else View.VISIBLE, light.visibility)
        assertEquals(if (dark) View.VISIBLE else View.GONE, night.visibility)
        val shown = (if (dark) night else light).getChildAt(0)
        assertTrue(shown.hasOnClickListeners())
        assertEquals("最低线 1/2 · 未达标", shown.findViewById<TextView>(R.id.widget_summary).text.toString())
        assertEquals(paletteFor(AppTheme.INK_TEAL, dark).text.toArgb(), shown.findViewById<TextView>(R.id.widget_title).currentTextColor)
        val tasks = shown.findViewById<LinearLayout>(R.id.widget_tasks)
        assertEquals(2, tasks.childCount)
        for (index in 0 until 2) {
            val row = tasks.getChildAt(index) as LinearLayout
            val item = row.getChildAt(0)
            assertEquals((48 * size).toInt(), item.height)
            assertTrue(item.hasOnClickListeners())
            val text = item.findViewById<TextView>(R.id.widget_task_text)
            assertEquals(2, text.maxLines)
            assertEquals(snapshot.tasks[index].label, text.text.toString())
            assertTrue(item.contentDescription.toString().contains(if (index == 0) "已完成" else "未完成"))
        }
    }
}
