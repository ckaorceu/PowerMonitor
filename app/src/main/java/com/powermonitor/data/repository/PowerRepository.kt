package com.powermonitor.data.repository

import com.powermonitor.data.dao.PowerDao
import com.powermonitor.data.entity.MinuteAggregate
import com.powermonitor.data.entity.RawSample
import kotlinx.coroutines.flow.Flow

/**
 * 数据仓库层，封装所有数据库访问操作，供Service与ViewModel调用
 */
class PowerRepository(private val powerDao: PowerDao) {

    // ========== 写入 ==========

    suspend fun insertRawSample(sample: RawSample) = powerDao.insertRawSample(sample)

    suspend fun insertRawSamples(samples: List<RawSample>) = powerDao.insertRawSamples(samples)

    suspend fun insertMinuteAggregate(aggregate: MinuteAggregate) =
        powerDao.insertMinuteAggregate(aggregate)

    suspend fun insertMinuteAggregates(aggregates: List<MinuteAggregate>) =
        powerDao.insertMinuteAggregates(aggregates)

    // ========== 读取 ==========

    fun getMinuteAggregatesSince(startTime: Long): Flow<List<MinuteAggregate>> =
        powerDao.getMinuteAggregatesSince(startTime)

    suspend fun getMinuteAggregates(startTime: Long, endTime: Long): List<MinuteAggregate> =
        powerDao.getMinuteAggregates(startTime, endTime)

    suspend fun getLatestRawSample(): RawSample? = powerDao.getLatestRawSample()

    suspend fun getLatestMinuteAggregate(): MinuteAggregate? = powerDao.getLatestMinuteAggregate()

    suspend fun getRawSamples(startTime: Long, endTime: Long): List<RawSample> =
        powerDao.getRawSamples(startTime, endTime)

    suspend fun getAllMinuteAggregates(): List<MinuteAggregate> = powerDao.getAllMinuteAggregates()

    suspend fun getAllRawSamples(): List<RawSample> = powerDao.getAllRawSamples()

    // ========== 清理 ==========

    suspend fun cleanupOldRawSamples(cutoffTime: Long) = powerDao.cleanupOldRawSamples(cutoffTime)

    suspend fun clearAll() {
        powerDao.clearAllRawSamples()
        powerDao.clearAllMinuteAggregates()
    }
}
