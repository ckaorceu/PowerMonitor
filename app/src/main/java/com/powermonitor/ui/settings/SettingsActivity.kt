package com.powermonitor.ui.settings

import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import com.powermonitor.PowerMonitorApp
import com.powermonitor.R
import com.powermonitor.databinding.ActivitySettingsBinding
import com.powermonitor.model.SamplingMode
import com.powermonitor.ui.main.MainViewModel
import com.powermonitor.util.AppPreferences
import com.powermonitor.util.CsvExporter
import com.powermonitor.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private var pendingFloatingToggle: Boolean = false
    private var pendingOverlayPermission: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPreferences.init(this)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        loadPreferenceValues()
        setupListeners()
        refreshPermissionStatus()
        showVersion()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
        // 如果用户刚从悬浮窗权限页面返回，应用上次的待切换值
        if (pendingOverlayPermission) {
            pendingOverlayPermission = false
            if (PermissionHelper.canDrawOverlays(this) && pendingFloatingToggle) {
                binding.switchFloating.isChecked = true
                AppPreferences.floatingEnabled = true
                startFloatingService()
            }
            pendingFloatingToggle = false
        }
    }

    private fun loadPreferenceValues() {
        val fg = AppPreferences.getInterval(SamplingMode.FOREGROUND)
        val bg = AppPreferences.getInterval(SamplingMode.BACKGROUND)
        val idle = AppPreferences.getInterval(SamplingMode.IDLE)
        val th = AppPreferences.powerThresholdMw

        binding.sliderIntervalFg.value = fg.toFloat().coerceIn(200f, 2000f)
        binding.sliderIntervalBg.value = bg.toFloat().coerceIn(500f, 5000f)
        binding.sliderIntervalIdle.value = idle.toFloat().coerceIn(1000f, 15000f)
        binding.sliderThreshold.value = th.toFloat().coerceIn(1000f, 20000f)

        binding.tvIntervalFg.text = "${fg.toInt()} ms"
        binding.tvIntervalBg.text = "${bg.toInt()} ms"
        binding.tvIntervalIdle.text = "${idle.toInt()} ms"
        binding.tvThreshold.text = "${th} mW"

        binding.switchFloating.isChecked = AppPreferences.floatingEnabled
        binding.switchBoot.isChecked = AppPreferences.bootAutoStart
    }

    private fun setupListeners() {
        fun sliderListener(mode: SamplingMode, tv: android.widget.TextView) =
            Slider.OnChangeListener { _, value, fromUser ->
                val ms = value.toLong()
                tv.text = "${ms} ms"
                if (fromUser) {
                    AppPreferences.setInterval(mode, ms)
                }
            }
        binding.sliderIntervalFg.addOnChangeListener(sliderListener(SamplingMode.FOREGROUND, binding.tvIntervalFg))
        binding.sliderIntervalBg.addOnChangeListener(sliderListener(SamplingMode.BACKGROUND, binding.tvIntervalBg))
        binding.sliderIntervalIdle.addOnChangeListener(sliderListener(SamplingMode.IDLE, binding.tvIntervalIdle))

        binding.sliderThreshold.addOnChangeListener { _, value, fromUser ->
            val mw = value.toInt()
            binding.tvThreshold.text = "${mw} mW"
            if (fromUser) AppPreferences.powerThresholdMw = mw
        }

        binding.switchFloating.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (!PermissionHelper.canDrawOverlays(this)) {
                    pendingFloatingToggle = true
                    pendingOverlayPermission = true
                    binding.switchFloating.isChecked = false
                    PermissionHelper.requestOverlayPermission(this, REQ_OVERLAY)
                    return@setOnCheckedChangeListener
                }
                AppPreferences.floatingEnabled = true
                startFloatingService()
            } else {
                AppPreferences.floatingEnabled = false
                stopService(Intent(this, com.powermonitor.service.FloatingWindowService::class.java))
            }
        }

        binding.switchBoot.setOnCheckedChangeListener { _, checked ->
            AppPreferences.bootAutoStart = checked
        }

        // 权限按钮
        binding.btnBatteryOpt.setOnClickListener {
            if (PermissionHelper.isIgnoringBatteryOptimizations(this)) {
                Toast.makeText(this, "已在白名单中", Toast.LENGTH_SHORT).show()
            } else {
                PermissionHelper.requestBatteryOptimizations(this)
            }
        }
        binding.btnNotification.setOnClickListener {
            if (PermissionHelper.needsNotificationPermission() &&
                !PermissionHelper.hasNotificationPermission(this)) {
                PermissionHelper.requestNotificationPermission(this, REQ_NOTIF)
            } else {
                Toast.makeText(this, "已授权", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnOverlay.setOnClickListener {
            pendingOverlayPermission = true
            PermissionHelper.requestOverlayPermission(this, REQ_OVERLAY)
        }

        // 导出 & 清空
        binding.btnExportCsv.setOnClickListener { exportCsv() }
        binding.btnClearData.setOnClickListener { confirmClearData() }
    }

    private fun startFloatingService() {
        val intent = Intent(this, com.powermonitor.service.FloatingWindowService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun refreshPermissionStatus() {
        binding.tvBatteryOptStatus.text =
            if (PermissionHelper.isIgnoringBatteryOptimizations(this))
                "✅ 已加入电池优化白名单" else "⚠️ 尚未加入白名单，后台可能被系统查杀"

        binding.tvNotifStatus.text =
            if (PermissionHelper.hasNotificationPermission(this))
                "✅ 已授权" else "❌ 未授权（前台服务通知将不可见）"

        val canOverlay = PermissionHelper.canDrawOverlays(this)
        binding.tvOverlayStatus.text =
            if (canOverlay) "✅ 已授权" else "❌ 未授权，悬浮窗无法显示"
        binding.tvFloatingStatus.text =
            if (canOverlay) "双击悬浮球可切换显示模式" else "需要先授权悬浮窗权限"
    }

    private fun showVersion() {
        val v = packageManager.getPackageInfo(packageName, 0).versionName
        binding.tvVersion.text = getString(R.string.settings_version, v)
    }

    private fun exportCsv() {
        binding.btnExportCsv.isEnabled = false
        binding.tvExportStatus.text = "正在导出…"
        lifecycleScope.launch {
            val result = CsvExporter.export(this@SettingsActivity)
            withContext(Dispatchers.Main) {
                binding.btnExportCsv.isEnabled = true
                result.onSuccess { path ->
                    val msg = getString(R.string.settings_export_success, path)
                    binding.tvExportStatus.text = msg
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                }.onFailure { t ->
                    val msg = getString(R.string.settings_export_failed, t.message ?: "未知错误")
                    binding.tvExportStatus.text = msg
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_clear_data)
            .setMessage(R.string.confirm_clear_data)
            .setNegativeButton(R.string.no, null)
            .setPositiveButton(R.string.yes) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        PowerMonitorApp.get().repository.clearAll()
                    }.onSuccess {
                        launch(Dispatchers.Main) {
                            Toast.makeText(this@SettingsActivity,
                                "已清空所有历史数据", Toast.LENGTH_SHORT).show()
                        }
                    }.onFailure {
                        launch(Dispatchers.Main) {
                            Toast.makeText(this@SettingsActivity,
                                "清空失败: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OVERLAY) {
            // onResume 会检查并刷新
        }
    }

    companion object {
        private const val REQ_OVERLAY = 2001
        private const val REQ_NOTIF = 2002
    }
}
