package com.xjb.kaoyancheckin

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import androidx.core.content.ContextCompat

/**
 * DATE_CHANGED is not exempt from Android's manifest implicit-broadcast restrictions.
 * Refresh widgets when the live process observes a system light/dark change.
 */
class CheckinApplication : Application() {
    private var nightMode = Configuration.UI_MODE_NIGHT_UNDEFINED

    override fun onCreate() {
        super.onCreate()
        nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        runCatching {
            val filter = IntentFilter(Intent.ACTION_DATE_CHANGED)
            ContextCompat.registerReceiver(this, WidgetClockReceiver(), filter, ContextCompat.RECEIVER_EXPORTED)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val next = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (next != nightMode) {
            nightMode = next
            CheckinWidget.refreshAll(this)
        }
    }
}
