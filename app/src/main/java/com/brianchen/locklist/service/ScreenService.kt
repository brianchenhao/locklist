package com.brianchen.locklist.service

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.brianchen.locklist.LockListApp
import com.brianchen.locklist.R
import com.brianchen.locklist.ui.LockActivity

class ScreenService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    // Second look just before launching: an alarm or ringtone can start a moment after the
    // screen wakes, and the lock list must never cover the call or alarm screen. The phone may
    // also have been unlocked (fingerprint, face) or switched off again in the meantime; the
    // activity turns the screen on, so launching then would light it back up.
    private val launchLock = Runnable {
        if (!lockedAndScreenOn()) return@Runnable
        if (callOrAlarmActive()) return@Runnable
        val lock = Intent(this, LockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            startActivity(lock)
        } catch (e: Exception) {
            Log.e("LockList", "could not open lock checklist", e)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                handler.removeCallbacks(launchLock)
                return
            }
            if (intent.action != Intent.ACTION_SCREEN_ON) return
            // Only over the lock screen: never on top of an unlocked phone.
            if (!lockedAndScreenOn()) return
            if (callOrAlarmActive()) return
            handler.removeCallbacks(launchLock)
            handler.postDelayed(launchLock, RECHECK_DELAY_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(launchLock)
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun lockedAndScreenOn(): Boolean {
        val keyguard = getSystemService(KeyguardManager::class.java)
        val power = getSystemService(PowerManager::class.java)
        return keyguard?.isKeyguardLocked == true && power?.isInteractive == true
    }

    /** Ringing, in a call (phone or VoIP), or an alarm/timer sounding. Needs no permission. */
    private fun callOrAlarmActive(): Boolean {
        val audio = getSystemService(AudioManager::class.java) ?: return false
        if (audio.mode != AudioManager.MODE_NORMAL) return true
        return try {
            audio.activePlaybackConfigurations.any {
                it.audioAttributes.usage == AudioAttributes.USAGE_ALARM
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun startInForeground() {
        val notification = NotificationCompat.Builder(this, LockListApp.CHANNEL_ID)
            .setContentTitle("LockList is running")
            .setSmallIcon(R.drawable.ic_stat_locklist)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val RECHECK_DELAY_MS = 300L
    }
}
