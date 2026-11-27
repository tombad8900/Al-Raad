package com.alraad.feature.player

import com.alraad.core.common.contracts.PlayerController
import com.alraad.feature.player.impl.Media3PlayerController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Media3 player controller (full stream.headers) and RTL player screen.
 * Implemented in Issue #8 (Wave 2); module created in Issue #1.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlayerModule {
    @Binds abstract fun bindPlayerController(impl: Media3PlayerController): PlayerController
}
