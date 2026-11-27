package com.alraad.app.di

import android.content.Context
import com.alraad.core.common.contracts.DiagnosticsLogger
import com.alraad.core.common.diagnostics.FileDiagnosticsLogger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Root Hilt module. More bindings land in #5 (repositories) and #6/#8. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun diagnosticsLogger(
        @ApplicationContext context: Context,
        scope: CoroutineScope,
    ): DiagnosticsLogger =
        FileDiagnosticsLogger(
            logFile = File(context.filesDir, "diagnostics/alraad.log"),
            scope = scope,
        )
}
