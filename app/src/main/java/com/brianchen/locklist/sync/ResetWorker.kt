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
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class ResetWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LockListApp
        try {
            app.runDailyResetIfDue()
        } finally {
            // Only here may the pending job be replaced: it is this job, and it is done.
            // In finally, so one failed reset does not end the nightly chain.
            scheduleNext(applicationContext, ExistingWorkPolicy.REPLACE)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE = "locklist-daily-reset"

        /**
         * App start must use KEEP: REPLACE there would delete an overdue reset that has not
         * run yet (phone off over midnight, or the process cold-started by that very job).
         */
        fun scheduleNext(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            val request = OneTimeWorkRequestBuilder<ResetWorker>()
                .setInitialDelay(millisUntilNextMidnight(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, policy, request)
        }

        private fun millisUntilNextMidnight(): Long {
            val zone = ZoneId.systemDefault()
            val next = LocalDate.now(zone).plusDays(1).atTime(LocalTime.MIDNIGHT).atZone(zone)
            val delay = Duration.between(ZonedDateTime.now(zone), next).toMillis()
            return delay.coerceAtLeast(1_000L)
        }
    }
}
