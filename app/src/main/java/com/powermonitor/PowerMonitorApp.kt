package com.powermonitor

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.powermonitor.data.db.PowerDatabase
import com.powermonitor.data.repository.PowerRepository

class PowerMonitorApp : Application() {

    lateinit var repository: PowerRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        val db = PowerDatabase.getDatabase(this)
        repository = PowerRepository(db.powerDao())
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = getString(R.string.foreground_notification_channel)
            val channelName = channelId
            val channelDesc = getString(R.string.foreground_notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(channelId, channelName, importance).apply {
                description = channelDesc
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        @Volatile
        private var instance: PowerMonitorApp? = null

        fun get(): PowerMonitorApp = instance
            ?: throw IllegalStateException("PowerMonitorApp not initialized yet")
    }
}
