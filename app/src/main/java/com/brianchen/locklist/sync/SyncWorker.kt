package com.brianchen.locklist.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.brianchen.locklist.LockListApp
import io.github.jan.supabase.exceptions.RestException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = (applicationContext as LockListApp).sync
        return try {
            if (!sync.isSignedIn()) return Result.success()
            sync.syncOnce()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sync.recordFailure(e)
            // A request the server rejects outright will be rejected again; let the next
            // periodic run or an in-app sync try instead of retrying in a tight loop.
            val permanent = e is RestException && e.statusCode in 400..499 &&
                e.statusCode !in RETRYABLE_4XX
            if (permanent || runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_ONCE = "locklist-sync-once"
        private const val UNIQUE_PERIODIC = "locklist-sync-periodic"
        private const val FALLBACK_DELAY_SECONDS = 30L
        private const val MAX_ATTEMPTS = 5
        private val RETRYABLE_4XX = setOf(401, 408, 429)

        /**
         * Fallback for when the in-app push does not get to run (process killed). KEEP never
         * cancels a pass that is already running, and the delay leaves the in-app push first.
         */
        fun enqueueOneShot(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraints())
                .setInitialDelay(FALLBACK_DELAY_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONCE,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        private fun networkConstraints(): Constraints {
            return Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        }
    }
}
