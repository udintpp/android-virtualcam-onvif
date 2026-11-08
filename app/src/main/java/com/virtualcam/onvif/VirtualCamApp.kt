package com.virtualcam.onvif

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class VirtualCamApp : Application() {

    companion object {
        const val CHANNEL_ID = "virtual_cam_stream_channel"
        lateinit var instance: VirtualCamApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val descriptionText = getString(R.string.notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }
}
