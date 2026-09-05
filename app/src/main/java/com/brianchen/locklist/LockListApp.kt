package com.brianchen.locklist

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.brianchen.locklist.data.AppDatabase
import com.brianchen.locklist.data.TaskRepository
import com.brianchen.locklist.sync.SyncWorker
import com.brianchen.locklist.sync.TaskSync
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest

class LockListApp : Application() {
    lateinit var tasks: TaskRepository
        private set
    lateinit var sync: TaskSync
        private set
    lateinit var supabase: SupabaseClient
        private set

    override fun onCreate() {
        super.onCreate()
        supabase = createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Auth)
            install(Postgrest)
        }
        tasks = TaskRepository(AppDatabase.create(this).taskDao()) {
            SyncWorker.enqueueOneShot(this)
        }
        sync = TaskSync(this, tasks, supabase)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LockList",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        SyncWorker.schedulePeriodic(this)
        SyncWorker.enqueueOneShot(this)
    }

    companion object {
        const val CHANNEL_ID = "locklist_running"
    }
}
