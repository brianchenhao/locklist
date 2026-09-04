package com.brianchen.locklist

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.brianchen.locklist.data.AppDatabase
import com.brianchen.locklist.data.TaskRepository

class LockListApp : Application() {
    lateinit var tasks: TaskRepository
        private set

    override fun onCreate() {
        super.onCreate()
        tasks = TaskRepository(AppDatabase.create(this).taskDao())
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
