package com.alraad.app.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Root Hilt module. Repository, ad-blocker, player and logger bindings
 * are added by their feature issues (#4, #5, #6, #8).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule
