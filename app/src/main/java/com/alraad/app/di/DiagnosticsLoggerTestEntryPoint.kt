package com.alraad.app.di

import androidx.annotation.VisibleForTesting
import com.alraad.core.common.contracts.DiagnosticsLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** androidTest access to the app-scoped logger without exposing it through production UI APIs. */
@VisibleForTesting
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DiagnosticsLoggerTestEntryPoint {
    fun diagnosticsLogger(): DiagnosticsLogger
}
