package com.brianchen.locklist

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.brianchen.locklist.data.AppDatabase
import com.brianchen.locklist.data.AppSettings
import com.brianchen.locklist.data.TaskRepository
import com.brianchen.locklist.sync.ResetWorker
import com.brianchen.locklist.sync.SyncWorker
import com.brianchen.locklist.sync.TaskImages
import com.brianchen.locklist.sync.TaskSync
import com.brianchen.locklist.sync.ThumbCache
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LockListApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val resetMutex = Mutex()

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
    lateinit var images: TaskImages
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
        tasks = TaskRepository(AppDatabase.create(this)) {
            // Push from the running app right away; the worker only covers a killed process.
            sync.requestPush()
            SyncWorker.enqueueOneShot(this)
        }
        thumbs = ThumbCache(this, supabase)
        images = TaskImages(this, supabase, thumbs)
        sync = TaskSync(this, tasks, supabase, appScope, images)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "LockList",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        SyncWorker.schedulePeriodic(this)
        SyncWorker.enqueueOneShot(this)
        ResetWorker.scheduleNext(this)
        catchUpDailyReset()
        // Waits for a session if there is none yet, so it also starts after a later sign-in.
        sync.startRealtime()
        watchNetwork()
    }

    /** Runs the recurring-task reset now if midnight passed without it (call on screen-on). */
    fun catchUpDailyReset() {
        appScope.launch {
            try {
                runDailyResetIfDue()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("LockList", "daily reset catch-up failed", e)
            }
        }
    }

    /** At most one reset per calendar day, whether the job or a catch-up gets there first. */
    suspend fun runDailyResetIfDue() {
        resetMutex.withLock {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val prefs = getSharedPreferences(RESET_PREFS, Context.MODE_PRIVATE)
            val last = prefs.getString(KEY_LAST_RESET, null)
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (last != null && !last.isBefore(today)) return
            // Only tasks ticked before today go back; a tick made today stays done.
            val startOfToday = today.atStartOfDay(zone).toInstant().toEpochMilli()
            tasks.resetRecurringDone(doneBefore = startOfToday)
            prefs.edit().putString(KEY_LAST_RESET, today.toString()).commit()
        }
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
        private const val RESET_PREFS = "locklist_reset"
        private const val KEY_LAST_RESET = "lastResetDate"
    }
}
