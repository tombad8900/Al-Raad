package com.alraad.app

import android.app.Application
import com.alraad.core.adblocker.impl.BlocklistUpdateWorker
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.diagnostics.CrashReporter
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** Application entry point. Hilt root; installs the raw crash reporter on startup. */
@HiltAndroidApp
class AlRaadApp : Application() {
    @Inject
    lateinit var diagnosticsLogger: DiagnosticsLogger

    override fun onCreate() {
        super.onCreate()
        CrashReporter(diagnosticsLogger).install()
        BlocklistUpdateWorker.schedule(this)
    }
}
