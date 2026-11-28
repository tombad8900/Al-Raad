package com.alraad.app

import android.app.Application
import com.alraad.core.adblocker.impl.BlocklistUpdateWorker
import com.alraad.core.adblocker.impl.OfflineAdBlocker
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.diagnostics.CrashReporter
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Application entry point. Hilt root; installs the raw crash reporter on startup. */
@HiltAndroidApp
class AlRaadApp : Application() {
    @Inject
    lateinit var diagnosticsLogger: DiagnosticsLogger

    @Inject
    lateinit var adBlocker: OfflineAdBlocker

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        CrashReporter(diagnosticsLogger).install()
        BlocklistUpdateWorker.schedule(this)
        appScope.launch { runCatching { adBlocker.warmUp() } }
    }
}
