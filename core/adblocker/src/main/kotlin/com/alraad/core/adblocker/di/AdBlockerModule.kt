package com.alraad.core.adblocker.di

import com.alraad.core.adblocker.impl.OfflineAdBlocker
import com.alraad.core.common.contracts.AdBlocker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AdBlockerModule {
    @Binds
    abstract fun bindAdBlocker(impl: OfflineAdBlocker): AdBlocker
}

@dagger.hilt.EntryPoint
@InstallIn(SingletonComponent::class)
interface AdblockEntryPoint {
    fun blocklistUpdater(): com.alraad.core.adblocker.impl.BlocklistUpdater

    fun offlineAdBlocker(): OfflineAdBlocker

    fun blocklistStore(): com.alraad.core.adblocker.impl.BlocklistStore
}
