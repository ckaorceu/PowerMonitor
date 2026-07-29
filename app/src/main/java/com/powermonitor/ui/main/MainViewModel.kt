package com.powermonitor.ui.main

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.powermonitor.PowerMonitorApp
import com.powermonitor.data.entity.MinuteAggregate
import com.powermonitor.model.SampleData
import com.powermonitor.model.SamplingMode
import com.powermonitor.model.ServiceState
import com.powermonitor.service.FloatingWindowService
import com.powermonitor.service.PowerMonitorService
import com.powermonitor.util.AppPreferences
import com.powermonitor.util.DataBus
import com.powermonitor.R
import com.powermonitor.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MainViewModel：
 *  - 订阅 DataBus 的实时采样流、服务状态
 *  - 计算 1 分钟平均功率、预估剩余续航
 *  - 加载最近 24 小时分钟级聚合历史
 *  - 通过 SavedStateHandle 保存实时窗口数据，横竖屏切换不丢失
 */
class MainViewModel(
    app: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(app) {

    private val repository = PowerMonitorApp.get().repository

    private val _latestSample = MutableLiveData<SampleData>(SampleData.EMPTY)
    val latestSample: LiveData<SampleData> = _latestSample

    private val _realtimeWindow = MutableLiveData<List<SampleData>>(emptyList())
    val realtimeWindow: LiveData<List<SampleData>> = _realtimeWindow

    private val _serviceState = MutableLiveData<ServiceState>(ServiceState.Idle)
    val serviceState: LiveData<ServiceState> = _serviceState

    private val _historyAggregates = MutableLiveData<List<MinuteAggregate>>(emptyList())
    val historyAggregates: LiveData<List<MinuteAggregate>> = _historyAggregates

    // 1 分钟平均功率（实时窗口内）
    val avgPower1min: LiveData<Int> = MediatorLiveData<Int>().apply {
        addSource(_realtimeWindow) { list ->
            value = if (list.isEmpty()) 0
            else list.sumOf { it.power } / list.size
        }
    }

    // 预估剩余续航：电池当前容量 / 平均电流（mAh）
    data class EstimatedBattery(
        val remainingHours: Int,
        val remainingMinutes: Int,
        val remainingMah: Int,
        val avgCurrentMa: Int
    )

    private val _estimatedBattery = MutableLiveData(
        EstimatedBattery(0, 0, 0, 0)
    )
    val estimatedBattery: LiveData<EstimatedBattery> = _estimatedBattery

    // 累计耗电量（悬浮窗与主界面共享）
    private val _accumulatedMah = MutableLiveData(0.0)
    val accumulatedMah: LiveData<Double> = _accumulatedMah

    // 点击选中的数据点（用于底部 TextView 显示）
    private val _selectedPoint = MutableLiveData<SampleData?>(null)
    val selectedPoint: LiveData<SampleData?> = _selectedPoint

    // SelectedPoint 格式化显示
    val selectedPointText: LiveData<String> = MediatorLiveData<String>().apply {
        addSource(_selectedPoint) { s ->
            value = formatSelectedPoint(s)
        }
    }

    private var collectJob: Job? = null
    private var historyJob: Job? = null

    init {
        // 恢复 SavedState 中的窗口（横竖屏切换）
        savedStateHandle.get<List<SampleData>>(KEY_WINDOW)
            ?.takeIf { it.isNotEmpty() }
            ?.let { _realtimeWindow.value = it }
        startObserving()
        loadHistory24h()
    }

    // ==================== 对外操作 ====================

    fun startServiceIfNeeded() {
        val ctx = getApplication<Application>().applicationContext
        val intent = Intent(ctx, PowerMonitorService::class.java).apply {
            action = PowerMonitorService.ACTION_SET_FOREGROUND
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
            PowerMonitorService.isAppForeground = true
        } catch (_: Throwable) {
        }
    }

    fun notifyForeground() {
        val ctx = getApplication<Application>().applicationContext
        val intent = Intent(ctx, PowerMonitorService::class.java).apply {
            action = PowerMonitorService.ACTION_SET_FOREGROUND
        }
        ctx.startService(intent)
        PowerMonitorService.isAppForeground = true
    }

    fun notifyBackground() {
        val ctx = getApplication<Application>().applicationContext
        val intent = Intent(ctx, PowerMonitorService::class.java).apply {
            action = PowerMonitorService.ACTION_SET_BACKGROUND
        }
        ctx.startService(intent)
        PowerMonitorService.isAppForeground = false
    }

    fun stopService() {
        val ctx = getApplication<Application>().applicationContext
        val intent = Intent(ctx, PowerMonitorService::class.java).apply {
            action = PowerMonitorService.ACTION_STOP
        }
        ctx.startService(intent)
        stopFloatingWindow()
    }

    fun selectPoint(sample: SampleData?) {
        _selectedPoint.value = sample
    }

    fun toggleFloatingWindow(enable: Boolean) {
        val ctx = getApplication<Application>().applicationContext
        val intent = Intent(ctx, FloatingWindowService::class.java)
        if (enable && PermissionHelper.canDrawOverlays(ctx)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        } else {
            ctx.stopService(intent)
        }
    }

    fun stopFloatingWindow() {
        val ctx = getApplication<Application>().applicationContext
        ctx.stopService(Intent(ctx, FloatingWindowService::class.java))
    }

    fun loadHistory24h() {
        historyJob?.cancel()
        historyJob = viewModelScope.launch(Dispatchers.IO) {
            val start = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            repository.getMinuteAggregatesSince(start).collectLatest { list ->
                _historyAggregates.postValue(list)
            }
        }
    }

    fun updateSamplingInterval(mode: SamplingMode, valueMs: Long) {
        AppPreferences.setInterval(mode, valueMs)
    }

    // ==================== 内部 ====================

    private fun startObserving() {
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            launch {
                collectStateFlow(DataBus.latestSample) { s ->
                    _latestSample.postValue(s)
                    updateBatteryEstimate(s, _realtimeWindow.value ?: emptyList())
                }
            }
            launch {
                collectStateFlow(DataBus.realtimeWindow) { list ->
                    _realtimeWindow.postValue(list)
                    savedStateHandle[KEY_WINDOW] = ArrayList(list)
                    updateBatteryEstimate(_latestSample.value ?: SampleData.EMPTY, list)
                }
            }
            launch {
                collectStateFlow(DataBus.serviceState) { _serviceState.postValue(it) }
            }
            launch {
                collectStateFlow(DataBus.accumulatedMah) { _accumulatedMah.postValue(it) }
            }
        }
    }

    private suspend fun <T> collectStateFlow(flow: StateFlow<T>, action: (T) -> Unit) {
        flow.collectLatest { action(it) }
    }

    private fun updateBatteryEstimate(latest: SampleData, window: List<SampleData>) {
        val ctx = getApplication<Application>()
        val batteryRemainingMah = runCatching { getRemainingBatteryMah(ctx) }.getOrDefault(0)
        val avgCurrent: Double = if (window.isEmpty()) kotlin.math.abs(latest.current).toDouble()
        else window.sumOf { kotlin.math.abs(it.current).toDouble() } / window.size
        val hours = if (avgCurrent <= 0.0) 0 else (batteryRemainingMah.toDouble() / avgCurrent).toInt()
        val minutes = if (avgCurrent <= 0.0) 0
        else ((batteryRemainingMah.toDouble() % avgCurrent) * 60.0 / avgCurrent).toInt()
        _estimatedBattery.postValue(
            EstimatedBattery(hours, minutes, batteryRemainingMah, avgCurrent.toInt())
        )
    }

    private fun getRemainingBatteryMah(ctx: android.content.Context): Int {
        val bm = ctx.getSystemService(android.content.Context.BATTERY_SERVICE)
            as android.os.BatteryManager
        val capacity = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val chargeCounter = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        return if (chargeCounter > 0) chargeCounter / 1000
        else (capacity * estimateTotalCapacity(ctx) / 100)
    }

    private fun estimateTotalCapacity(ctx: android.content.Context): Int {
        return runCatching {
            val filter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val sticky = ctx.registerReceiver(null, filter)
            val scale = sticky?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = sticky?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 0) ?: 0
            val bm = ctx.getSystemService(android.content.Context.BATTERY_SERVICE)
                as android.os.BatteryManager
            val cc = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            if (cc > 0 && pct > 0) cc * scale / pct / 1000 else 3000
        }.getOrDefault(3000)
    }

    private fun formatSelectedPoint(s: SampleData?): String {
        if (s == null) {
            return getApplication<Application>().getString(R.string.no_data_selected)
        }
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
            .format(Date(s.timestamp))
        return getApplication<Application>().getString(
            R.string.selected_point_info,
            time, s.power, s.current, s.voltage, s.temperature
        )
    }

    companion object {
        private const val KEY_WINDOW = "realtime_window"
    }
}
