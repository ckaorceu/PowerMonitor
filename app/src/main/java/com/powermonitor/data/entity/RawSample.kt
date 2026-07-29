package com.powermonitor.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 原始采样数据点（每次采集写入，用于内存中的实时展示，可选持久化用于断点补录）
 */
@Entity(tableName = "raw_samples")
data class RawSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,            // 采样时间戳（ms）
    val voltage: Int,               // 电压 mV
    val current: Int,               // 电流 mA（正数放电，负数充电）
    val power: Int,                 // 功率 mW = voltage * current / 1000（已取绝对值）
    val temperature: Float,         // 温度 ℃
    val isEstimated: Boolean,       // 是否为估算值（无电流传感器时）
    val cpuLoad: Float = 0f,        // CPU负载 0-1（估算模式使用）
    val screenBrightness: Int = 0   // 屏幕亮度 0-255（估算模式使用）
)
