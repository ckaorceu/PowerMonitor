package com.powermonitor.util

import com.powermonitor.model.SampleData
import com.powermonitor.model.ServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局数据总线：Service 发布采样数据、UI / 悬浮窗订阅
 *
 * 使用 MutableStateFlow 保证新订阅者（冷启动的 Activity / 悬浮窗）
 * 能立即获取到最近一次采样与服务状态。
 */
object DataBus {

    // 最近的采样数据（每次新采样都更新）
    private val _latestSample = MutableStateFlow(SampleData.EMPTY)
    val latestSample: StateFlow<SampleData> = _latestSample.asStateFlow()

    // 实时采样流（保留最近 60 个点，FIFO 滑动窗口）
    private val _realtimeWindow = MutableStateFlow<List<SampleData>>(emptyList())
    val realtimeWindow: StateFlow<List<SampleData>> = _realtimeWindow.asStateFlow()

    // 服务状态
    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    // 悬浮窗：累计耗电量 mAh（启动服务后累计）
    private val _accumulatedMah = MutableStateFlow(0.0)
    val accumulatedMah: StateFlow<Double> = _accumulatedMah.asStateFlow()

    const val WINDOW_SIZE = 60

    /** 发布一个新的采样点 */
    @Synchronized
    fun emitSample(sample: SampleData) {
        _latestSample.value = sample
        val current = _realtimeWindow.value.toMutableList()
        current.add(sample)
        while (current.size > WINDOW_SIZE) current.removeAt(0)
        _realtimeWindow.value = current
    }

    fun setState(state: ServiceState) {
        _serviceState.value = state
    }

    fun addAccumulatedMah(delta: Double) {
        _accumulatedMah.value += delta
    }

    fun resetAccumulatedMah() {
        _accumulatedMah.value = 0.0
    }

    fun setAccumulatedMah(value: Double) {
        _accumulatedMah.value = value
    }
}
