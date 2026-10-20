package com.reelcounter.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lightweight, in-process service state for the settings UI. Updated only on real state changes. */
enum class TrackingState { IDLE, APP_ACTIVE, FEED_ACTIVE, TRANSITIONING }

data class TrackingSnapshot(
    val state: TrackingState = TrackingState.IDLE,
    val platform: Platform? = null
)

object TrackingStatus {
    private val _snapshot = MutableStateFlow(TrackingSnapshot())
    val snapshot: StateFlow<TrackingSnapshot> = _snapshot.asStateFlow()

    fun update(state: TrackingState, platform: Platform?) {
        val next = TrackingSnapshot(state, platform)
        if (_snapshot.value != next) _snapshot.value = next
    }
}
