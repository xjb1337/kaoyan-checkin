package com.xjb.kaoyancheckin

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * DATE_CHANGED is not exempt from Android's manifest implicit-broadcast restrictions.
 * Theme changes are handled by values-night resources and AppWidget updates, not a broadcast loop.
 */
class CheckinApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            val filter = IntentFilter(Intent.ACTION_DATE_CHANGED)
            ContextCompat.registerReceiver(this, WidgetClockReceiver(), filter, ContextCompat.RECEIVER_EXPORTED)
        }
    }
}
