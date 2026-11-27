package com.alraad.feature.player.ui

import androidx.lifecycle.ViewModel
import com.alraad.feature.player.impl.Media3PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Exposes the shared player controller to the PLAYER navigation route. */
@HiltViewModel
class PlayerViewModel
    @Inject
    constructor(
        val controller: Media3PlayerController,
    ) : ViewModel()
