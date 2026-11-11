package com.xjb.kaoyancheckin

import android.app.Activity
import android.view.View
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Opt-in like ScreenshotTest; captures the actual RemoteViews, without network or a launcher. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WidgetScreenshotTest {
    @Test @Config(qualifiers = "w412dp-h915dp-xhdpi") fun light() = capture("widget-light.png")
    @Test @Config(qualifiers = "w412dp-h915dp-night-xhdpi") fun dark() = capture("widget-dark.png")

    private fun capture(name: String) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val plan = StudyPlan(org.json.JSONObject(activity.assets.open("plan.json").bufferedReader().use { it.readText() }))
        val state = CheckinCore.emptyState()
        toggleDayTask(state, plan, "2026-11-10", "2026-11-10", "words", 1)
        val views = CheckinWidget.views(activity, 1, widgetSnapshot(plan, state, "2026-11-10"), 300, 130)
        val root = views.apply(activity, null)
        activity.setContentView(root)
        val density = activity.resources.displayMetrics.density
        val width = (300 * density).toInt()
        val height = (130 * density).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        val directory = File(checkNotNull(System.getProperty("screenshotDir")))
        check(directory.isDirectory || directory.mkdirs())
        root.captureRoboImage(File(directory, name))
    }
}
