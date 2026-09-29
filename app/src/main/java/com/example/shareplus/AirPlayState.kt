package com.example.shareplus

import kotlinx.coroutines.flow.MutableStateFlow

// process-wide receiver state, written by AirPlayService (often from native threads),
// observed by MainActivity
object AirPlayState {
    enum class Phase {
        STOPPED,     // server not running
        IDLE,        // advertised, waiting for an iPhone
        CONNECTING,  // sender opened a session, no video/audio yet
        MIRRORING,   // screen mirroring video running
        AUDIO_ONLY,  // audio streaming without mirroring
        PAUSED,      // sender went away (e.g. iPhone locked); back to IDLE shortly
    }

    val phase = MutableStateFlow(Phase.STOPPED)
    val serverName = MutableStateFlow("SharePlus")
    val ipAddress = MutableStateFlow<String?>(null)
    // decoded picture size (after crop), drives the SurfaceView aspect ratio
    val videoSize = MutableStateFlow(0 to 0)
    val activityVisible = MutableStateFlow(false)
}
