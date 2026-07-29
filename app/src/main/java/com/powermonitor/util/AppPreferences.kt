package com.powermonitor.util

import android.content.Context
import android.content.SharedPreferences
import com.powermonitor.model.SamplingMode

/**
 * 应用偏好设置：采样间隔、功率阈值、开关项等
 */
object AppPreferences {

    private const val NAME = "power_monitor_prefs"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    // 采样间隔（毫秒）
    private const val KEY_INTERVAL_FG = "interval_fg"
    private const val KEY_INTERVAL_BG = "interval_bg"
    private const val KEY_INTERVAL_IDLE = "interval_idle"
    private const val KEY_POWER_THRESHOLD = "power_threshold_mw"
    private const val KEY_FLOATING_ENABLED = "floating_enabled"
    private const val KEY_BOOT_AUTO_START = "boot_auto_start"
    private const val KEY_FIRST_LAUNCH = "first_launch"
    private const val KEY_LAST_SAMPLE_MINUTE = "last_sample_minute"  // 断点补录时间戳

    // 默认值
    const val DEFAULT_INTERVAL_FG = 500L
    const val DEFAULT_INTERVAL_BG = 2000L
    const val DEFAULT_INTERVAL_IDLE = 5000L
    const val DEFAULT_POWER_THRESHOLD = 5000

    fun getInterval(mode: SamplingMode): Long = when (mode) {
        SamplingMode.FOREGROUND -> prefs.getLong(KEY_INTERVAL_FG, DEFAULT_INTERVAL_FG)
        SamplingMode.BACKGROUND -> prefs.getLong(KEY_INTERVAL_BG, DEFAULT_INTERVAL_BG)
        SamplingMode.IDLE -> prefs.getLong(KEY_INTERVAL_IDLE, DEFAULT_INTERVAL_IDLE)
    }

    fun setInterval(mode: SamplingMode, valueMs: Long) {
        val key = when (mode) {
            SamplingMode.FOREGROUND -> KEY_INTERVAL_FG
            SamplingMode.BACKGROUND -> KEY_INTERVAL_BG
            SamplingMode.IDLE -> KEY_INTERVAL_IDLE
        }
        prefs.edit().putLong(key, valueMs).apply()
    }

    var powerThresholdMw: Int
        get() = prefs.getInt(KEY_POWER_THRESHOLD, DEFAULT_POWER_THRESHOLD)
        set(value) = prefs.edit().putInt(KEY_POWER_THRESHOLD, value).apply()

    var floatingEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_FLOATING_ENABLED, value).apply()

    var bootAutoStart: Boolean
        get() = prefs.getBoolean(KEY_BOOT_AUTO_START, true)
        set(value) = prefs.edit().putBoolean(KEY_BOOT_AUTO_START, value).apply()

    var isFirstLaunch: Boolean
        get() = prefs.getBoolean(KEY_FIRST_LAUNCH, true)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_LAUNCH, value).apply()

    var lastSampleMinute: Long
        get() = prefs.getLong(KEY_LAST_SAMPLE_MINUTE, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SAMPLE_MINUTE, value).apply()
}
