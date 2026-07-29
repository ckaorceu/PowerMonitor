package com.powermonitor.util

import android.content.Context
import android.os.PowerManager
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * 功率估算模型：当设备不支持电流传感器时，基于 CPU/GPU 负载 + 屏幕亮度估算功率
 * 估算公式（经验模型）：
 *   P = P_idle + kCpu * cpuLoad + kScreen * brightnessNorm
 *   P_idle = 额定待机 mW（按电池容量近似）
 */
object PowerEstimator {

    // 设备级估算参数（可按设备实际校准）
    private const val P_IDLE_BASE_MW = 80         // 基础待机
    private const val K_CPU = 600                 // CPU满载时附加功率 mW
    private const val K_SCREEN = 400              // 屏幕满亮度附加功率 mW
    private const val NOMINAL_VOLTAGE_MV = 3800   // 额定电池电压

    fun estimate(
        context: Context,
        cpuLoad: Float,
        screenBrightness: Int,
        screenOn: Boolean
    ): EstimatedResult {
        val brightnessNorm = if (screenOn) (screenBrightness.coerceIn(0, 255) / 255f) else 0f
        val idleMw = P_IDLE_BASE_MW + approximateCapacityFactor(context)
        val cpuMw = (K_CPU * cpuLoad.coerceIn(0f, 1f)).roundToInt()
        val screenMw = (K_SCREEN * brightnessNorm).roundToInt()
        val powerMw = idleMw + cpuMw + screenMw
        // 反向推导电流
        val currentMa = (powerMw * 1000f / NOMINAL_VOLTAGE_MV).roundToInt()
        return EstimatedResult(
            voltage = NOMINAL_VOLTAGE_MV,
            current = currentMa,
            power = powerMw,
            idleMw = idleMw,
            cpuMw = cpuMw,
            screenMw = screenMw
        )
    }

    data class EstimatedResult(
        val voltage: Int,
        val current: Int,
        val power: Int,
        val idleMw: Int,
        val cpuMw: Int,
        val screenMw: Int
    )

    private fun approximateCapacityFactor(context: Context): Int {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val battery = pm.isInteractive  // 占位，不影响核心估算
            if (battery) 0 else 0
        } catch (_: Throwable) {
            0
        }
    }
}

/**
 * CPU 负载估算工具（读取 /proc/stat 两次差量计算）
 */
object CpuUsageReader {
    private var lastIdle = 0L
    private var lastTotal = 0L

    fun readCpuLoad(): Float {
        return try {
            val lines = java.io.File("/proc/stat").readLines().firstOrNull() ?: return 0f
            val parts = lines.split("\\s+".toRegex()).drop(1).map { it.toLongOrNull() ?: 0L }
            if (parts.size < 8) return 0f
            val idle = parts[3] + parts[4]
            val total = parts.sum()
            val load = if (lastTotal == 0L) 0f else {
                val idleDelta = (idle - lastIdle).toFloat()
                val totalDelta = (total - lastTotal).toFloat()
                if (totalDelta <= 0f) 0f else (1f - idleDelta / totalDelta).coerceIn(0f, 1f)
            }
            lastIdle = idle
            lastTotal = total
            load
        } catch (_: Throwable) {
            0f
        }
    }

    fun reset() {
        lastIdle = 0L
        lastTotal = 0L
    }
}

/**
 * 屏幕亮度读取工具
 */
object ScreenBrightnessReader {
    fun readBrightness(context: Context): Int {
        return try {
            val cr = context.contentResolver
            android.provider.Settings.System.getInt(
                cr,
                android.provider.Settings.System.SCREEN_BRIGHTNESS
            )
        } catch (_: Throwable) {
            128
        }
    }

    fun isScreenOn(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isInteractive
        } catch (_: Throwable) {
            true
        }
    }
}
