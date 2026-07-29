package com.powermonitor.ui.main

import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import com.powermonitor.R
import com.powermonitor.databinding.ActivityMainBinding
import com.powermonitor.model.ServiceState
import com.powermonitor.ui.settings.SettingsActivity
import com.powermonitor.util.AppPreferences
import com.powermonitor.util.DataBus
import com.powermonitor.util.PermissionHelper
import com.powermonitor.util.ScreenBrightnessReader

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var chartManager: ChartManager
    private var firstLaunchDialogShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPreferences.init(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        chartManager = ChartManager(
            context = this,
            realtimeChart = binding.realtimeChart,
            historyChart = binding.historyChart,
            onPointSelected = { sample -> viewModel.selectPoint(sample) }
        )
        chartManager.setPowerThreshold(AppPreferences.powerThresholdMw)

        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.action_stop -> {
                    confirmStopService()
                    true
                }
                else -> false
            }
        }

        observeViewModel()
        requestEssentialPermissions()
        viewModel.startServiceIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        viewModel.notifyForeground()
        if (AppPreferences.floatingEnabled && PermissionHelper.canDrawOverlays(this)) {
            viewModel.toggleFloatingWindow(true)
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.notifyBackground()
    }

    override fun onResume() {
        super.onResume()
        // 返回前台时刷新历史与阈值
        chartManager.setPowerThreshold(AppPreferences.powerThresholdMw)
        viewModel.loadHistory24h()
    }

    // ==================== 观察数据 ====================

    private fun observeViewModel() {
        with(viewModel) {
            latestSample.observe(this@MainActivity, Observer { s ->
                binding.tvCurrentPower.text = s.power.toString()
                val extra = buildString {
                    append("${s.voltage}mV · ${s.current}mA · ${String.format("%.1f", s.temperature)}℃")
                    if (s.isEstimated) append(" · ")
                        .append(getString(R.string.estimation_mode))
                }
                binding.tvDetailExtra.text = extra
            })

            avgPower1min.observe(this@MainActivity, Observer { v ->
                binding.tvAvgPower.text = v.toString()
            })

            estimatedBattery.observe(this@MainActivity, Observer { e ->
                binding.tvRemainingHours.text = e.remainingHours.toString()
                binding.tvRemainingMinutes.text = String.format("%02d", e.remainingMinutes)
            })

            accumulatedMah.observe(this@MainActivity, Observer { mah ->
                binding.tvAccumulatedMah.text = String.format("%.2f mAh", mah)
            })

            serviceState.observe(this@MainActivity, Observer { st ->
                binding.tvServiceState.text = when (st) {
                    is ServiceState.Idle -> "就绪"
                    is ServiceState.Running -> "监测中 · 屏幕" +
                        if (ScreenBrightnessReader.isScreenOn(this@MainActivity)) "亮" else "熄"
                    is ServiceState.Stopped -> "已停止"
                    is ServiceState.Error -> "错误: ${st.message}"
                }
            })

            realtimeWindow.observe(this@MainActivity, Observer { window ->
                chartManager.updateRealtimeData(window)
            })

            historyAggregates.observe(this@MainActivity, Observer { list ->
                chartManager.updateHistory(list)
                val from24h = System.currentTimeMillis() - 24L * 3600 * 1000
                val count24h = list.count { it.minuteTimestamp >= from24h }
                val totalMah = list.filter { it.minuteTimestamp >= from24h }
                    .sumOf { it.totalConsumptionMah }
                binding.tvHistoryInfo.text = if (list.isEmpty())
                    "暂无历史数据，首次启动后将在每分钟结束后写入。"
                else
                    "近24小时: ${count24h} 条聚合 · 累计耗电 ${String.format("%.2f", totalMah)} mAh · 总记录 ${list.size}"
            })

            selectedPointText.observe(this@MainActivity, Observer { text ->
                binding.tvSelectedInfo.text = text
            })
        }
    }

    // ==================== 权限 ====================

    private fun requestEssentialPermissions() {
        // 1) 通知权限
        if (PermissionHelper.needsNotificationPermission() &&
            !PermissionHelper.hasNotificationPermission(this)
        ) {
            PermissionHelper.requestNotificationPermission(this, REQ_NOTIF)
        }
        // 2) 电池优化白名单 + 厂商锁定提示（仅首次启动）
        if (AppPreferences.isFirstLaunch) {
            showFirstLaunchGuides()
        }
    }

    private fun showFirstLaunchGuides() {
        if (firstLaunchDialogShown) return
        firstLaunchDialogShown = true
        val message = buildString {
            append(getString(R.string.permission_battery_optimization))
            append("\n\n")
            append(getString(R.string.permission_lock_app_hint))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.go_to_settings) { _, _ ->
                if (!PermissionHelper.isIgnoringBatteryOptimizations(this)) {
                    PermissionHelper.requestBatteryOptimizations(this)
                } else {
                    PermissionHelper.openAppSettings(this)
                }
            }
            .setOnDismissListener { AppPreferences.isFirstLaunch = false }
            .show()
    }

    private fun confirmStopService() {
        AlertDialog.Builder(this)
            .setMessage("确认停止功率监测服务？后台连续监测将中断。")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton("停止") { _, _ -> viewModel.stopService() }
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 通知被拒绝的话不影响功能（只是前台服务通知不显示在通知栏）
    }

    companion object {
        private const val REQ_NOTIF = 1001
    }
}
