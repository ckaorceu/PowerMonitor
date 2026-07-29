package com.powermonitor.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.powermonitor.PowerMonitorApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CSV 导出工具：
 *  - 将 Room 中 RawSample + MinuteAggregate 导出到 Downloads/PowerLog_yyyyMMdd_HHmmss.csv
 *  - 兼容 Android 10+（MediaStore）与旧版（Environment.getExternalStoragePublicDirectory）
 */
object CsvExporter {

    private const val FILE_PREFIX = "PowerLog_"
    private const val HEADER = "时间戳,时间,功率(mW),电压(mV),电流(mA),温度(℃),累计耗电量(mAh),估算\n"

    suspend fun export(context: Context): Result<String> = withContext(Dispatchers.IO) {
        return@withContext runCatching {
            val repo = PowerMonitorApp.get().repository
            val rawSamples = repo.getAllRawSamples()
            val minuteAggs = repo.getAllMinuteAggregates()

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            val minuteFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:00", Locale.getDefault())

            // 生成合并视图：
            //  - 原始采样（如果有） + 分钟聚合的累计耗电量列
            //  优先使用原始采样（细粒度），若缺失则用分钟聚合近似

            val content = buildString {
                append(HEADER)
                // 1. 先写原始采样（毫秒级）
                var accMah = 0.0
                // 计算每分钟累计耗电量映射
                val minuteAccum = minuteAggs.associate { it.minuteTimestamp to it.totalConsumptionMah }

                if (rawSamples.isNotEmpty()) {
                    var lastMinute = -1L
                    rawSamples.forEach { s ->
                        val minuteStart = s.timestamp / 60000 * 60000
                        if (minuteStart != lastMinute) {
                            // 在新分钟起始时，累加之前分钟的合计
                            val aggMah = minuteAccum[minuteStart]
                            if (aggMah != null) {
                                // 用分钟聚合的精确累计值
                            }
                            lastMinute = minuteStart
                        }
                        // 单个采样点贡献的 mAh 近似
                        val deltaSec = if (s.isEstimated) 2.0 else 0.5
                        val deltaMah = (kotlin.math.abs(s.current) * deltaSec) / 3600.0
                        accMah += deltaMah
                        append("${s.timestamp},")
                        append("${dateFormat.format(Date(s.timestamp))},")
                        append("${s.power},")
                        append("${s.voltage},")
                        append("${s.current},")
                        append("${String.format(Locale.US, "%.1f", s.temperature)},")
                        append("${String.format(Locale.US, "%.4f", accMah)},")
                        append("${if (s.isEstimated) "1" else "0"}\n")
                    }
                } else if (minuteAggs.isNotEmpty()) {
                    // 无原始采样，则按分钟聚合导出
                    minuteAggs.forEach { a ->
                        append("${a.minuteTimestamp},")
                        append("${minuteFormat.format(Date(a.minuteTimestamp))},")
                        append("${a.avgPower},")
                        append("${a.avgVoltage},")
                        append("${a.avgCurrent},")
                        append("${String.format(Locale.US, "%.1f", a.avgTemperature)},")
                        append("${String.format(Locale.US, "%.4f", a.totalConsumptionMah)},")
                        append("agg\n")
                    }
                }
            }

            // 保存到 Downloads
            val fileName = run {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                "$FILE_PREFIX$ts.csv"
            }
            val savedPath = saveToDownloads(context, fileName, content)
            savedPath
        }
    }

    private fun saveToDownloads(ctx: Context, fileName: String, content: String): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore.insert returned null")
            resolver.openOutputStream(uri!!).use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { w -> w.write(content) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Downloads/$fileName (MediaStore)"
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val file = java.io.File(dir, fileName)
            file.outputStream().use { os ->
                os.bufferedWriter().use { w -> w.write(content) }
            }
            file.absolutePath
        }
    }
}
