package com.alraad.feature.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.alraad.core.adblocker.impl.BlocklistStore
import com.alraad.core.adblocker.impl.BlocklistUpdater
import com.alraad.core.adblocker.impl.OfflineAdBlocker
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.contracts.SettingsRepository

internal fun testBlocklistDeps(
    settings: SettingsRepository,
    logger: DiagnosticsLogger,
): Triple<BlocklistStore, BlocklistUpdater, OfflineAdBlocker> {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = BlocklistStore(context)
    val updater = BlocklistUpdater(store, logger)
    val adBlocker = OfflineAdBlocker(context, logger, settings, store)
    return Triple(store, updater, adBlocker)
}
