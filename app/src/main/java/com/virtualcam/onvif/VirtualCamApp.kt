package com.virtualcam.onvif

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter

class VirtualCamApp : Application() {

    companion object {
        const val CHANNEL_ID = "virtual_cam_stream_channel"
        lateinit var instance: VirtualCamApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        setupCrashHandler()
        createNotificationChannel()
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("VirtualCamCrash", "FATAL CRASH in thread ${thread.name}", throwable)
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val logText = "Crash in thread ${thread.name}:\n$sw\n\n"

                val logFile = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "virtualcam_crash.log"
                )
                FileOutputStream(logFile, true).use {
                    it.write(logText.toByteArray())
                }
            } catch (ignored: Exception) {}

            defaultHandler?.uncaughtException(thread, throwable)
        }
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
