package com.powermonitor.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.powermonitor.PowerMonitorApp
import com.powermonitor.R
import com.powermonitor.data.entity.MinuteAggregate
import com.powermonitor.data.entity.RawSample
import com.powermonitor.model.SampleData
import com.powermonitor.model.SamplingMode
import com.powermonitor.model.ServiceState
import com.powermonitor.util.AppPreferences
import com.powermonitor.util.CpuUsageReader
import com.powermonitor.util.DataBus
import com.powermonitor.util.PowerEstimator
import com.powermonitor.util.ScreenBrightnessReader
import com.powermonitor.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * 实时功率监测前台服务
 *
 * 核心职责：
 *  1. 前台服务保活（Android 14 FOREGROUND_SERVICE_TYPE_DATA_SYNC）
 *  2. 唤醒锁管理（采样前获取，采样后立即释放）
 *  3. 动态采样策略：前台 500ms / 后台 2s / 熄屏 5s
 *  4. START_STICKY 自动重启，重启后根据 Room 断点补录缺失分钟聚合
 *  5. BatteryManager 采样 + 估算模型回退
 *  6. 每分钟聚合并写入 Room
 */
class PowerMonitorService : Service() {

    private lateinit var batteryManager: BatteryManager
    private lateinit var powerManager: PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var samplingJob: Job? = null
    private var currentMode = SamplingMode.FOREGROUND
    private var currentIntervalMs = AppPreferences.DEFAULT_INTERVAL_FG
    private var supportsCurrentSensor = true

    // 分钟聚合暂存
    private var currentMinuteStart = 0L
    private var minuteSumPower = 0L
    private var minuteSumVoltage = 0L
    private var minuteSumCurrent = 0L
    private var minuteSumTemperature = 0.0
    private var minuteMinPower = Int.MAX_VALUE
    private var minuteMaxPower = Int.MIN_VALUE
    private var minuteSampleCount = 0
    private var minuteConsumptionMah = 0.0

    // 重启恢复相关
    private val restartRunnable = Runnable { startSamplingLoop() }

    // 屏幕与前后台监听
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> applySamplingMode(SamplingMode.IDLE)
                Intent.ACTION_SCREEN_ON -> applySamplingMode(
                    if (isAppForeground) SamplingMode.FOREGROUND else SamplingMode.BACKGROUND
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppPreferences.init(this)
        batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        supportsCurrentSensor = detectCurrentSensorSupport()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, filter)
        }

        startForeground(NOTIF_ID, buildNotification(SampleData.EMPTY))
        DataBus.setState(ServiceState.Running)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSampling()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SET_FOREGROUND -> applySamplingMode(SamplingMode.FOREGROUND)
            ACTION_SET_BACKGROUND -> applySamplingMode(
                if (ScreenBrightnessReader.isScreenOn(this)) SamplingMode.BACKGROUND else SamplingMode.IDLE
            )
        }
        // 启动采样，若被系统杀死后 5 秒内重启并补录
        startSamplingLoop()
        serviceScope.launch { recoverFromGapIfNeeded() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopSampling()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Throwable) {
        }
        releaseWakeLock()
        serviceJob.cancel()
        DataBus.setState(ServiceState.Stopped)
    }

    // ==================== 采样循环 ====================

    private fun startSamplingLoop() {
        samplingJob?.cancel()
        currentIntervalMs = AppPreferences.getInterval(currentMode)
        CpuUsageReader.reset()
        // 先读取一次 CPU 基准
        CpuUsageReader.readCpuLoad()

        samplingJob = serviceScope.launch {
            while (true) {
                val interval = currentIntervalMs
                // 1) 采样前获取唤醒锁
                acquireWakeLock()
                try {
                    val sample = takeSample()
                    onSampleCollected(sample)
                } catch (t: Throwable) {
                    DataBus.setState(ServiceState.Error(t.message ?: "Unknown error"))
                } finally {
                    // 2) 采样完成立即释放
                    releaseWakeLock()
                }
                delay(interval)
            }
        }
    }

    private fun stopSampling() {
        samplingJob?.cancel()
        samplingJob = null
        handler.removeCallbacks(restartRunnable)
    }

    private fun applySamplingMode(mode: SamplingMode) {
        if (currentMode == mode) return
        currentMode = mode
        currentIntervalMs = AppPreferences.getInterval(mode)
        // 重启采样循环以使用新间隔
        startSamplingLoop()
    }

    // ==================== 单次采样 ====================

    private fun takeSample(): SampleData {
        val ts = System.currentTimeMillis()
        return if (supportsCurrentSensor) {
            val voltage = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_VOLTAGE)
            val current = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val temp = readBatteryTemperature()
            val power = (voltage * kotlin.math.abs(current)) / 1000  // mW
            SampleData(ts, voltage, current, power, temp, isEstimated = false)
        } else {
            val cpu = CpuUsageReader.readCpuLoad()
            val brightness = ScreenBrightnessReader.readBrightness(this)
            val screenOn = ScreenBrightnessReader.isScreenOn(this)
            val est = PowerEstimator.estimate(this, cpu, brightness, screenOn)
            val temp = readBatteryTemperature()
            SampleData(
                timestamp = ts,
                voltage = est.voltage,
                current = est.current,
                power = est.power,
                temperature = temp,
                isEstimated = true,
                cpuLoad = cpu,
                screenBrightness = brightness
            )
        }
    }

    private fun readBatteryTemperature(): Float {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val sticky = registerReceiver(null, filter)
            val raw = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?: Int.MIN_VALUE
            if (raw == Int.MIN_VALUE) 25f else raw / 10f
        } catch (_: Throwable) {
            25f
        }
    }

    private fun detectCurrentSensorSupport(): Boolean {
        return try {
            val current = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            current != Int.MIN_VALUE
        } catch (_: Throwable) {
            false
        }
    }

    // ==================== 采样后处理 ====================

    private fun onSampleCollected(sample: SampleData) {
        // 发布到数据总线（UI / 悬浮窗订阅）
        DataBus.emitSample(sample)

        // 累计耗电量：mAh = (mA * seconds) / 3600
        val seconds = currentIntervalMs / 1000.0
        val deltaMah = (kotlin.math.abs(sample.current) * seconds) / 3600.0
        DataBus.addAccumulatedMah(deltaMah)

        // 分钟聚合
        val minuteStart = sample.timestamp / 60000 * 60000
        if (currentMinuteStart == 0L) currentMinuteStart = minuteStart
        if (minuteStart != currentMinuteStart) {
            flushMinuteAggregate()
            currentMinuteStart = minuteStart
            minuteSumPower = 0L
            minuteSumVoltage = 0L
            minuteSumCurrent = 0L
            minuteSumTemperature = 0.0
            minuteMinPower = Int.MAX_VALUE
            minuteMaxPower = Int.MIN_VALUE
            minuteSampleCount = 0
            minuteConsumptionMah = 0.0
        }
        minuteSumPower += sample.power
        minuteSumVoltage += sample.voltage
        minuteSumCurrent += kotlin.math.abs(sample.current)
        minuteSumTemperature += sample.temperature
        minuteMinPower = min(minuteMinPower, sample.power)
        minuteMaxPower = max(minuteMaxPower, sample.power)
        minuteSampleCount++
        minuteConsumptionMah += deltaMah

        // 更新前台通知
        updateNotificationIfNeeded(sample)

        // 持久化原始采样（用于断点补录，保留 7 天）
        val raw = RawSample(
            timestamp = sample.timestamp,
            voltage = sample.voltage,
            current = sample.current,
            power = sample.power,
            temperature = sample.temperature,
            isEstimated = sample.isEstimated,
            cpuLoad = sample.cpuLoad,
            screenBrightness = sample.screenBrightness
        )
        serviceScope.launch {
            val repo = PowerMonitorApp.get().repository
            repo.insertRawSample(raw)
            // 清理超过 7 天的原始数据
            val cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
            repo.cleanupOldRawSamples(cutoff)
        }
    }

    private fun flushMinuteAggregate() {
        if (minuteSampleCount == 0) return
        val count = minuteSampleCount
        val agg = MinuteAggregate(
            minuteTimestamp = currentMinuteStart,
            avgPower = (minuteSumPower / count).toInt(),
            maxPower = minuteMaxPower,
            minPower = minuteMinPower,
            avgVoltage = (minuteSumVoltage / count).toInt(),
            avgCurrent = (minuteSumCurrent / count).toInt(),
            totalConsumptionMah = minuteConsumptionMah,
            avgTemperature = (minuteSumTemperature / count).toFloat(),
            sampleCount = count
        )
        AppPreferences.lastSampleMinute = currentMinuteStart
        serviceScope.launch {
            runCatching {
                PowerMonitorApp.get().repository.insertMinuteAggregate(agg)
            }
        }
    }

    // ==================== 重启断点补录 ====================

    private suspend fun recoverFromGapIfNeeded() {
        val lastMinute = AppPreferences.lastSampleMinute
        if (lastMinute == 0L) return
        val now = System.currentTimeMillis()
        val nowMinute = now / 60000 * 60000
        if (nowMinute <= lastMinute) return
        // 若差距超过 1 分钟，尝试用原始采样点补齐聚合
        runCatching {
            val repo = PowerMonitorApp.get().repository
            val samples = repo.getRawSamples(lastMinute, nowMinute)
            if (samples.isNotEmpty()) {
                // 按分钟分组聚合
                val grouped = samples.groupBy { it.timestamp / 60000 * 60000 }
                val aggs = grouped.map { (ms, list) ->
                    val c = list.size
                    MinuteAggregate(
                        minuteTimestamp = ms,
                        avgPower = (list.sumOf { it.power.toLong() } / c).toInt(),
                        maxPower = list.maxOf { it.power },
                        minPower = list.minOf { it.power },
                        avgVoltage = (list.sumOf { it.voltage.toLong() } / c).toInt(),
                        avgCurrent = (list.sumOf { kotlin.math.abs(it.current).toLong() } / c).toInt(),
                        totalConsumptionMah = list.sumOf { s ->
                            val sec = AppPreferences.getInterval(SamplingMode.BACKGROUND) / 1000.0
                            (kotlin.math.abs(s.current) * sec) / 3600.0
                        },
                        avgTemperature = (list.sumOf { it.temperature.toDouble() } / c).toFloat(),
                        sampleCount = c
                    )
                }
                if (aggs.isNotEmpty()) repo.insertMinuteAggregates(aggs)
            }
            // 恢复累计耗电量（粗略：读最近 24h 聚合）
            val last24h = System.currentTimeMillis() - 24 * 3600 * 1000
            val lastAggs = repo.getMinuteAggregates(last24h, now)
            val totalMah = lastAggs.sumOf { it.totalConsumptionMah }
            DataBus.setAccumulatedMah(totalMah)
        }
    }

    // ==================== 唤醒锁 ====================

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "PowerMonitor::SamplingWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.takeIf { !it.isHeld }?.acquire(WAKELOCK_TIMEOUT_MS)
        } catch (_: Throwable) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
    }

    // ==================== 通知 ====================

    private var lastNotifTs = 0L
    private fun updateNotificationIfNeeded(s: SampleData) {
        val now = System.currentTimeMillis()
        if (now - lastNotifTs < NOTIF_UPDATE_THROTTLE_MS) return
        lastNotifTs = now
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIF_ID, buildNotification(s))
    }

    private fun buildNotification(s: SampleData): Notification {
        val ctx = this
        val contentText = getString(
            R.string.foreground_notification_text,
            s.power, s.temperature
        )
        val clickIntent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(ctx, 0, clickIntent, piFlags)

        return NotificationCompat.Builder(ctx, getString(R.string.foreground_notification_channel))
            .setContentTitle(getString(R.string.foreground_notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val NOTIF_UPDATE_THROTTLE_MS = 3000L
        private const val WAKELOCK_TIMEOUT_MS = 3000L

        const val ACTION_START = "com.powermonitor.ACTION_START"
        const val ACTION_STOP = "com.powermonitor.ACTION_STOP"
        const val ACTION_SET_FOREGROUND = "com.powermonitor.ACTION_SET_FOREGROUND"
        const val ACTION_SET_BACKGROUND = "com.powermonitor.ACTION_SET_BACKGROUND"

        /** 当 onTaskRemoved 被系统调用时，5s 内触发重启（通过 Alarm 或再次自启） */
        var isAppForeground = false
    }
}
