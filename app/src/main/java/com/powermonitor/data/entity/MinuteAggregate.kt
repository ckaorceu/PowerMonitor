package com.powermonitor.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 分钟级聚合数据（每分钟写入1条，用于历史统计与图表缩略图）
 */
@Entity(tableName = "minute_aggregates")
data class MinuteAggregate(
    @PrimaryKey val minuteTimestamp: Long,   // 该分钟起始时间戳（ms，精确到分钟）
    val avgPower: Int,                       // 平均功率 mW
    val maxPower: Int,                       // 最大功率 mW
    val minPower: Int,                       // 最小功率 mW
    val avgVoltage: Int,                     // 平均电压 mV
    val avgCurrent: Int,                     // 平均电流 mA
    val totalConsumptionMah: Double,         // 累计耗电量 mAh（基于该分钟采样累加）
    val avgTemperature: Float,               // 平均温度 ℃
    val sampleCount: Int                     // 该分钟实际采样点数
)
