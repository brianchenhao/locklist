package com.brianchen.locklist.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/** Brings the lock-screen service back after a reboot or after the app updates itself. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                try {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, ScreenService::class.java)
                    )
                } catch (e: Exception) {
                    Log.w("LockList", "could not restart service after ${intent.action}", e)
                }
            }
        }
    }
}
