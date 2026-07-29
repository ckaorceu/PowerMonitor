package com.powermonitor.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.powermonitor.data.entity.MinuteAggregate
import com.powermonitor.data.entity.RawSample
import kotlinx.coroutines.flow.Flow

@Dao
interface PowerDao {

    // ========== RawSample 操作 ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRawSample(sample: RawSample)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRawSamples(samples: List<RawSample>)

    /** 获取指定时间范围内的原始采样（用于断点补录） */
    @Query("SELECT * FROM raw_samples WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp ASC")
    suspend fun getRawSamples(startTime: Long, endTime: Long): List<RawSample>

    /** 获取最后一条原始采样（Service重启恢复时读取） */
    @Query("SELECT * FROM raw_samples ORDER BY id DESC LIMIT 1")
    suspend fun getLatestRawSample(): RawSample?

    /** 清理超过7天的原始采样 */
    @Query("DELETE FROM raw_samples WHERE timestamp < :cutoffTime")
    suspend fun cleanupOldRawSamples(cutoffTime: Long)

    // ========== MinuteAggregate 操作 ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMinuteAggregate(aggregate: MinuteAggregate)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMinuteAggregates(aggregates: List<MinuteAggregate>)

    /** 获取最近24小时的分钟聚合数据（缩略图） */
    @Query("SELECT * FROM minute_aggregates WHERE minuteTimestamp >= :startTime ORDER BY minuteTimestamp ASC")
    fun getMinuteAggregatesSince(startTime: Long): Flow<List<MinuteAggregate>>

    /** 获取指定时间范围内的聚合数据 */
    @Query("SELECT * FROM minute_aggregates WHERE minuteTimestamp BETWEEN :startTime AND :endTime ORDER BY minuteTimestamp ASC")
    suspend fun getMinuteAggregates(startTime: Long, endTime: Long): List<MinuteAggregate>

    /** 最近一条聚合记录 */
    @Query("SELECT * FROM minute_aggregates ORDER BY minuteTimestamp DESC LIMIT 1")
    suspend fun getLatestMinuteAggregate(): MinuteAggregate?

    /** 全部聚合（CSV导出） */
    @Query("SELECT * FROM minute_aggregates ORDER BY minuteTimestamp ASC")
    suspend fun getAllMinuteAggregates(): List<MinuteAggregate>

    /** 全部原始采样（CSV导出） */
    @Query("SELECT * FROM raw_samples ORDER BY timestamp ASC")
    suspend fun getAllRawSamples(): List<RawSample>

    /** 清空所有数据 */
    @Query("DELETE FROM minute_aggregates")
    suspend fun clearAllMinuteAggregates()

    @Query("DELETE FROM raw_samples")
    suspend fun clearAllRawSamples()
}
