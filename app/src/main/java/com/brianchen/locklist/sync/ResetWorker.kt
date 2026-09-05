package com.brianchen.locklist.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.brianchen.locklist.LockListApp
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class ResetWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LockListApp
        app.tasks.resetRecurringDone()
        scheduleNext(applicationContext)
        return Result.success()
    }

    companion object {
        private const val UNIQUE = "locklist-daily-reset"

        fun scheduleNext(context: Context) {
            val request = OneTimeWorkRequestBuilder<ResetWorker>()
                .setInitialDelay(millisUntilNextMidnight(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        private fun millisUntilNextMidnight(): Long {
            val zone = ZoneId.systemDefault()
            val next = LocalDate.now(zone).plusDays(1).atTime(LocalTime.MIDNIGHT).atZone(zone)
            val delay = Duration.between(java.time.ZonedDateTime.now(zone), next).toMillis()
            return delay.coerceAtLeast(1_000L)
        }
    }
}
