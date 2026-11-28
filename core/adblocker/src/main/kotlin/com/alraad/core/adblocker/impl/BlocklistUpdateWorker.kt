package com.alraad.core.adblocker.impl

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.alraad.core.adblocker.di.AdblockEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.TimeUnit

/** Periodic background refresh of the downloadable blocklists (roughly daily). */
class BlocklistUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, AdblockEntryPoint::class.java)
        entryPoint.blocklistUpdater().updateAll()
        entryPoint.offlineAdBlocker().reload()
        // The upgrade refresh retries a bounded number of times while lists are still pending (offline or a failed
        // download); the daily periodic run covers anything left, so there is no download loop.
        val stillPending = entryPoint.blocklistStore().listsPendingRefresh().isNotEmpty()
        val retry = inputData.getBoolean(KEY_REFRESH_PENDING, false) && stillPending && runAttemptCount < MAX_REFRESH_ATTEMPTS
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "adblock-list-updates"
        private const val PENDING_WORK_NAME = "adblock-list-refresh-pending"
        private const val KEY_REFRESH_PENDING = "refresh_pending"
        private const val MAX_REFRESH_ATTEMPTS = 3
        private const val BACKOFF_MINUTES = 15L

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(24, TimeUnit.HOURS).build(),
            )
            scheduleRefreshIfPending(context)
        }

        /**
         * After an upgrade, Adblock Plus lists have only a lossy cache that the engine ignores. Fetch them as soon
         * as the network is available instead of waiting for the next daily run. One unique job, never replaced
         * while queued or running.
         */
        fun scheduleRefreshIfPending(context: Context) {
            if (BlocklistStore(context).listsPendingRefresh().isEmpty()) return
            val request =
                OneTimeWorkRequestBuilder<BlocklistUpdateWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_MINUTES, TimeUnit.MINUTES)
                    .setInputData(Data.Builder().putBoolean(KEY_REFRESH_PENDING, true).build())
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(PENDING_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
