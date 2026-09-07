package com.brianchen.locklist

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.brianchen.locklist.data.AppDatabase
import com.brianchen.locklist.data.AppSettings
import com.brianchen.locklist.data.TaskRepository
import com.brianchen.locklist.sync.ResetWorker
import com.brianchen.locklist.sync.SyncWorker
import com.brianchen.locklist.sync.TaskSync
import com.brianchen.locklist.sync.ThumbCache
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LockListApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var tasks: TaskRepository
        private set
    lateinit var sync: TaskSync
        private set
    lateinit var supabase: SupabaseClient
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var thumbs: ThumbCache
        private set

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        supabase = createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime) {
                // TaskSync supervises the socket itself; keep the library from tearing it
                // down on its own when a channel closes or a token refresh blips.
                reconnectDelay = 5.seconds
                disconnectOnSessionLoss = false
                disconnectOnNoSubscriptions = false
            }
            install(Storage)
        }
        tasks = TaskRepository(AppDatabase.create(this).taskDao()) {
            SyncWorker.enqueueOneShot(this)
        }
        thumbs = ThumbCache(this, supabase)
        sync = TaskSync(this, tasks, supabase, appScope)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LockList",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        SyncWorker.schedulePeriodic(this)
        SyncWorker.enqueueOneShot(this)
        ResetWorker.scheduleNext(this)
        appScope.launch {
            if (sync.isSignedIn()) sync.startRealtime()
        }
        watchNetwork()
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    sync.onNetworkAvailable()
                }
            })
        } catch (e: Exception) {
            Log.w("LockList", "network callback unavailable", e)
        }
    }

    companion object {
        const val CHANNEL_ID = "locklist_running"
    }
}
