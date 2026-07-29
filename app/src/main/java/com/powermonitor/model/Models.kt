package com.powermonitor.model

/**
 * 内存中传递的实时采样数据（用于 UI 展示、悬浮窗、LiveData 更新）
 */
data class SampleData(
    val timestamp: Long,
    val voltage: Int,               // mV
    val current: Int,               // mA 正数放电
    val power: Int,                 // mW
    val temperature: Float,         // ℃
    val isEstimated: Boolean,
    val cpuLoad: Float = 0f,
    val screenBrightness: Int = 0
) {
    companion object {
        val EMPTY = SampleData(0, 0, 0, 0, 0f, false)
    }
}

/**
 * 采样模式：前台 / 后台 / 熄屏
 */
enum class SamplingMode {
    FOREGROUND,     // 500ms (默认)
    BACKGROUND,     // 2s
    IDLE            // 5s
}

/**
 * Service 采样状态事件（供 UI 订阅）
 */
sealed class ServiceState {
    object Idle : ServiceState()
    object Running : ServiceState()
    object Stopped : ServiceState()
    data class Error(val message: String) : ServiceState()
}
