package com.powermonitor.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.powermonitor.service.PowerMonitorService

/**
 * 开机自启接收器：启动前台功率监测服务
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            if (AppPreferences.bootAutoStart) {
                startMonitorService(context)
            }
        }
    }

    private fun startMonitorService(context: Context) {
        try {
            val serviceIntent = Intent(context, PowerMonitorService::class.java).apply {
                action = PowerMonitorService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Throwable) {
        }
    }
}
