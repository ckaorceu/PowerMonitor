package com.powermonitor.ui.main

import android.content.Context
import android.graphics.Color
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.AxisBase
import com.github.mikephil.charting.components.Description
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import com.powermonitor.R
import com.powermonitor.data.entity.MinuteAggregate
import com.powermonitor.model.SampleData
import com.powermonitor.util.DataBus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 图表管理器：
 *  - 配置实时双轴 LineChart（功率 + 温度）
 *  - 配置 24 小时缩略图
 *  - 处理数据点点击回调
 */
class ChartManager(
    private val context: Context,
    private val realtimeChart: LineChart,
    private val historyChart: LineChart,
    private val onPointSelected: (SampleData?) -> Unit
) {

    private var powerThresholdMw: Int = 5000

    // 实时窗口数据索引到 SampleData 的映射（保留引用以便点击时查询）
    private var currentWindow: List<SampleData> = emptyList()

    private val timeFormatHms = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val historyTimeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    init {
        setupRealtimeChart()
        setupHistoryChart()
    }

    fun setPowerThreshold(mw: Int) {
        powerThresholdMw = mw
    }

    // ==================== 实时图配置 ====================

    private fun setupRealtimeChart() {
        val c = context

        realtimeChart.apply {
            setDrawGridBackground(false)
            setBackgroundColor(Color.parseColor("#FAFBFC"))
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            isDoubleTapToZoomEnabled = false
            setBorderColor(c.getColor(R.color.divider))
            setBorderWidth(0.5f)
            setDrawBorders(true)
            description = Description().apply { text = "" }
            setNoDataText("等待采样数据…")
            setNoDataTextColor(c.getColor(R.color.text_hint))
        }

        // Legend
        realtimeChart.legend.apply {
            isEnabled = true
            form = Legend.LegendForm.LINE
            textColor = c.getColor(R.color.text_secondary)
            textSize = 11f
            horizontalAlignment = Legend.LegendHorizontalAlignment.RIGHT
            verticalAlignment = Legend.LegendVerticalAlignment.TOP
            orientation = Legend.LegendOrientation.VERTICAL
            setDrawInside(true)
            yEntrySpace = 4f
        }

        // X 轴：时间（HH:mm:ss）
        realtimeChart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            textColor = c.getColor(R.color.text_secondary)
            textSize = 10f
            setDrawGridLines(true)
            gridColor = Color.parseColor("#1A000000")
            gridLineWidth = 0.5f
            axisMinimum = 0f
            axisMaximum = (DataBus.WINDOW_SIZE - 1).toFloat()
            setLabelCount(6, true)
            valueFormatter = object : ValueFormatter() {
                override fun getAxisLabel(value: Float, axis: AxisBase?): String {
                    val idx = value.toInt().coerceIn(0, currentWindow.size - 1)
                    if (idx < 0 || idx >= currentWindow.size) return ""
                    return timeFormatHms.format(Date(currentWindow[idx].timestamp))
                }
            }
        }

        // 左轴 Y：功率 mW
        realtimeChart.axisLeft.apply {
            setPosition(YAxis.YAxisLabelPosition.OUTSIDE_CHART)
            textColor = c.getColor(R.color.chart_power_line)
            textSize = 11f
            setDrawGridLines(true)
            gridColor = Color.parseColor("#1A000000")
            gridLineWidth = 0.5f
            axisMinimum = 0f
            setLabelCount(6, true)
            valueFormatter = object : ValueFormatter() {
                override fun getAxisLabel(value: Float, axis: AxisBase?): String =
                    "${value.toInt()}"
            }
        }

        // 右轴 Y：温度 ℃
        realtimeChart.axisRight.apply {
            isEnabled = true
            textColor = c.getColor(R.color.chart_temp_line)
            textSize = 11f
            setDrawGridLines(false)
            setDrawAxisLine(true)
            axisMaximum = 60f
            axisMinimum = 0f
            setLabelCount(6, true)
            valueFormatter = object : ValueFormatter() {
                override fun getAxisLabel(value: Float, axis: AxisBase?): String =
                    String.format(Locale.getDefault(), "%.0f°", value)
            }
        }

        // 点击监听
        realtimeChart.setOnChartValueSelectedListener(object : OnChartValueSelectedListener {
            override fun onValueSelected(e: Entry?, h: Highlight?) {
                e ?: return
                val idx = e.x.toInt().coerceIn(0, currentWindow.size - 1)
                if (idx in currentWindow.indices) {
                    onPointSelected(currentWindow[idx])
                }
            }
            override fun onNothingSelected() {
                onPointSelected(null)
            }
        })
    }

    // ==================== 实时图更新 ====================

    fun updateRealtimeData(window: List<SampleData>) {
        currentWindow = window
        if (window.isEmpty()) {
            realtimeChart.data = null
            realtimeChart.invalidate()
            return
        }

        val powerEntries = mutableListOf<Entry>()
        val tempEntries = mutableListOf<Entry>()
        val underIndices = mutableListOf<Int>()
        val overIndices = mutableListOf<Int>()

        window.forEachIndexed { idx, s ->
            val x = idx.toFloat()
            powerEntries.add(Entry(x, s.power.toFloat()))
            tempEntries.add(Entry(x, s.temperature))
            if (s.power > powerThresholdMw) overIndices.add(idx) else underIndices.add(idx)
        }

        // 功率线：阈值以上红色、以下蓝色。这里用渐变/分段颜色实现，
        // 同时用可填充阴影。为简化，先使用一条线 + 填充，
        // 对超过阈值的 Entry 设置图标颜色高亮由 dataSet 的动态着色；
        // 更严格做法是拆成两条 DataSet（使用不同颜色），此处实现后者：
        val underEntries = underIndices.map { Entry(it.toFloat(), window[it].power.toFloat()) }
        val overEntries = overIndices.map { Entry(it.toFloat(), window[it].power.toFloat()) }

        val normalSet = buildPowerDataSet(
            underEntries,
            context.getString(R.string.chart_power_label),
            context.getColor(R.color.chart_power_line),
            context.getColor(R.color.chart_power_fill),
            overThreshold = false
        )
        val overSet = buildPowerDataSet(
            overEntries,
            "功率(>阈值)",
            context.getColor(R.color.chart_power_over_threshold),
            context.getColor(R.color.chart_power_over_fill),
            overThreshold = true
        )

        val tempSet = LineDataSet(tempEntries, context.getString(R.string.chart_temp_label)).apply {
            axisDependency = YAxis.AxisDependency.RIGHT
            color = context.getColor(R.color.chart_temp_line)
            setCircleColor(context.getColor(R.color.chart_temp_line))
            setDrawCircles(false)
            setDrawValues(false)
            lineWidth = 1.8f
            setDrawFilled(false)
            mode = LineDataSet.Mode.HORIZONTAL_BEZIER
        }

        val data = LineData(normalSet, overSet, tempSet)
        data.setDrawValues(false)
        realtimeChart.data = data
        realtimeChart.xAxis.axisMaximum = (DataBus.WINDOW_SIZE - 1).toFloat()
        realtimeChart.xAxis.axisMinimum = 0f
        realtimeChart.notifyDataSetChanged()
        realtimeChart.invalidate()
    }

    private fun buildPowerDataSet(
        entries: List<Entry>,
        label: String,
        lineColor: Int,
        fillColor: Int,
        overThreshold: Boolean
    ): LineDataSet {
        return LineDataSet(entries, label).apply {
            axisDependency = YAxis.AxisDependency.LEFT
            color = lineColor
            lineWidth = 2.0f
            setDrawCircles(overThreshold)
            circleRadius = 2.8f
            setCircleColor(lineColor)
            setDrawValues(false)
            setDrawFilled(true)
            setFillColor(fillColor)
            mode = LineDataSet.Mode.HORIZONTAL_BEZIER
            setHighLightColor(context.getColor(R.color.color_accent))
            isHighlightEnabled = true
        }
    }

    // ==================== 历史图配置 ====================

    private fun setupHistoryChart() {
        historyChart.apply {
            setDrawGridBackground(false)
            setBackgroundColor(Color.parseColor("#FAFBFC"))
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            isDoubleTapToZoomEnabled = false
            description = Description().apply { text = "" }
            setNoDataText("暂无 24 小时历史")
            setNoDataTextColor(context.getColor(R.color.text_hint))
            setTouchEnabled(true)
        }

        historyChart.legend.apply {
            isEnabled = false
        }

        historyChart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            textColor = context.getColor(R.color.text_secondary)
            textSize = 9f
            setDrawGridLines(true)
            gridColor = Color.parseColor("#1A000000")
            gridLineWidth = 0.5f
            setLabelCount(6, true)
            granularity = 1f
        }

        historyChart.axisLeft.apply {
            textColor = context.getColor(R.color.chart_power_line)
            textSize = 10f
            setDrawGridLines(true)
            gridColor = Color.parseColor("#1A000000")
            axisMinimum = 0f
            setLabelCount(4, true)
        }

        historyChart.axisRight.isEnabled = false
    }

    fun updateHistory(aggregates: List<MinuteAggregate>) {
        if (aggregates.isEmpty()) {
            historyChart.data = null
            historyChart.invalidate()
            return
        }
        val avgEntries = aggregates.mapIndexed { i, a ->
            Entry(i.toFloat(), a.avgPower.toFloat())
        }
        val maxEntries = aggregates.mapIndexed { i, a ->
            Entry(i.toFloat(), a.maxPower.toFloat())
        }

        val avgSet = LineDataSet(avgEntries, "平均功率").apply {
            color = context.getColor(R.color.chart_power_line)
            lineWidth = 1.6f
            setDrawCircles(false)
            setDrawValues(false)
            setDrawFilled(true)
            fillColor = context.getColor(R.color.chart_power_fill)
            mode = LineDataSet.Mode.CUBIC_BEZIER
        }
        val maxSet = LineDataSet(maxEntries, "峰值").apply {
            color = context.getColor(R.color.chart_power_over_threshold)
            lineWidth = 1.0f
            setDrawCircles(false)
            setDrawValues(false)
            setDrawFilled(false)
            mode = LineDataSet.Mode.LINEAR
        }

        // X轴格式化为时间
        historyChart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getAxisLabel(value: Float, axis: AxisBase?): String {
                val i = value.toInt().coerceIn(0, aggregates.size - 1)
                if (i < 0 || i >= aggregates.size) return ""
                return historyTimeFormat.format(Date(aggregates[i].minuteTimestamp))
            }
        }
        historyChart.data = LineData(avgSet, maxSet)
        historyChart.notifyDataSetChanged()
        historyChart.invalidate()
    }

    // 辅助：清除选中高亮
    fun clearHighlights() {
        realtimeChart.highlightValue(null)
    }
}
