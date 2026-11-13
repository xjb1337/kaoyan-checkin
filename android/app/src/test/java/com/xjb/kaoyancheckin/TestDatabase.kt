package com.xjb.kaoyancheckin

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers

internal class TestDatabase(private val app: Application) : AutoCloseable {
    private val scenarios = mutableListOf<ActivityScenario<MainActivity>>()
    private val models = mutableListOf<CheckinViewModel>()

    init {
        CheckinRepository.reset()
        app.deleteDatabase("checkin.db")
        check(!app.getDatabasePath("checkin.db").exists()) { "无法重置测试数据库" }
        resetViewModelFactory()
    }

    fun launchActivity(): ActivityScenario<MainActivity> = ActivityScenario.launch(MainActivity::class.java).also { scenario ->
        scenarios.add(scenario)
        scenario.onActivity { activity ->
            val model = ViewModelProvider(activity)[CheckinViewModel::class.java]
            models.add(model)
            check(model.getApplication<Application>() === app) { "ViewModel 使用了其他测试的 Application" }
        }
    }

    fun awaitWrites() = awaitTestCondition(
        description = "ViewModel 在途写入结束",
        current = { models.map { "pending=${it.hasPendingWrites}, error=${it.loadError}" } },
    ) { models.none { it.hasPendingWrites } }

    override fun close() {
        try {
            scenarios.forEach { it.close() }
            awaitWrites()
        } finally {
            CheckinRepository.reset()
            app.deleteDatabase("checkin.db")
            resetViewModelFactory()
        }
    }

    private fun resetViewModelFactory() {
        ReflectionHelpers.setStaticField(ViewModelProvider.AndroidViewModelFactory::class.java, "_instance", null)
    }
}

internal fun awaitTestCondition(
    description: String,
    timeoutMillis: Long = 15_000,
    current: () -> Any? = { "" },
    condition: () -> Boolean,
) {
    val looper = shadowOf(Looper.getMainLooper())
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    while (true) {
        looper.idle()
        if (condition()) return
        check(System.nanoTime() < deadline) { "等待 $description 超时（${timeoutMillis}ms）；当前状态：${current()}" }
        Thread.sleep(20)
    }
}
