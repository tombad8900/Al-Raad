package com.alraad.core.adblocker.impl

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
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
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "adblock-list-updates"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BlocklistUpdateWorker>(24, TimeUnit.HOURS).build(),
            )
        }
    }
}
