package com.kostysetinin.gametranslate.overlay

import kotlinx.coroutines.flow.MutableStateFlow

object SessionState {
    val running = MutableStateFlow(false)
    val paused = MutableStateFlow(false)
    val status = MutableStateFlow("Ожидание")
}
