package com.alraad.feature.browser.tabs

import androidx.annotation.VisibleForTesting
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * androidTest access to the process-wide TabManager singleton for test isolation.
 * Must live in a Hilt-processed main source set: entry points defined in
 * androidTest are not implemented by the app's generated component.
 */
@VisibleForTesting
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TabManagerTestEntryPoint {
    fun tabManager(): TabManager
}
