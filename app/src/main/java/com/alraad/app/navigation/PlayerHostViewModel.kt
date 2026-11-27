package com.alraad.app.navigation

import androidx.lifecycle.ViewModel
import com.alraad.feature.player.impl.Media3PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Exposes the singleton player controller and the active stream to PlayerScreen. */
@HiltViewModel
class PlayerHostViewModel
    @Inject
    constructor(
        val controller: Media3PlayerController,
        val holder: ActiveStreamHolder,
    ) : ViewModel()
