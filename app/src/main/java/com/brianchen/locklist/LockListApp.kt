package com.brianchen.locklist

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class LockListApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LockList",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "locklist_running"
    }
}
