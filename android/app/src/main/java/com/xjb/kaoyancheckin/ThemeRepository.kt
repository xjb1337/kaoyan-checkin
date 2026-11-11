package com.xjb.kaoyancheckin

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.xjb.kaoyancheckin.ui.theme.AppTheme

/** Local interface preference, deliberately separate from the study document and its backups. */
internal class ThemeRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): AppTheme = AppTheme.from(runCatching { preferences.getString(KEY_THEME, null) }.getOrNull())
    fun save(theme: AppTheme) { preferences.edit().putString(KEY_THEME, theme.name).apply() }

    companion object {
        internal const val PREFERENCES = "appearance"
        internal const val KEY_THEME = "theme"
    }
}

internal class ThemeViewModel(
    app: Application,
    private val refreshWidgets: (Context) -> Unit = { context -> CheckinWidget.refreshAll(context) },
) : AndroidViewModel(app) {
    private val repository = ThemeRepository(app)
    var theme by mutableStateOf(repository.load())
        private set

    fun select(theme: AppTheme) {
        if (theme == this.theme) return
        repository.save(theme) // apply updates the in-process preference before the widget reads it.
        this.theme = theme
        refreshWidgets(getApplication())
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ThemeViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return ThemeViewModel(app) as T
            }
        }
    }
}
