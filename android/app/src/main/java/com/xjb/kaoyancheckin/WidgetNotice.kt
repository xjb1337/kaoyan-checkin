package com.xjb.kaoyancheckin

import android.content.Context

internal object WidgetNotice {
    private const val PREFERENCES = "widget_notice"
    internal const val MESSAGE = "message"

    fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    fun peek(context: Context): String? = preferences(context).getString(MESSAGE, null)
    fun post(context: Context, message: String) { preferences(context).edit().putString(MESSAGE, message).apply() }
    fun take(context: Context): String? = peek(context)?.also { preferences(context).edit().remove(MESSAGE).apply() }
}
