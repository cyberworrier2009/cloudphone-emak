package com.emaktalk.cloudphone.sip

import com.emaktalk.cloudphone.audio.AudioRoute
import com.emaktalk.cloudphone.network.NetworkType
import org.linphone.core.Call

/** Higher-level grouping over Linphone's call states, used to drive UI banners. */
enum class ConnectionPhase {
    /** Setup, ring, or connected with healthy media flow. */
    Healthy,
    /** Network just changed; SIP is re-negotiating media. UI shows a banner. */
    Reconnecting,
    /** No network at all. UI shows "Connection lost". */
    Lost
}

/**
 * Snapshot of the currently active call rendered by the UI.
 * A `null` [CallUiState] means there is no call in progress.
 */
data class CallUiState(
    val state: Call.State,
    val remoteUri: String,
    val displayName: String,
    val isOutgoing: Boolean,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val audioRoute: AudioRoute = AudioRoute.Earpiece,
    val availableRoutes: List<AudioRoute> = listOf(AudioRoute.Earpiece, AudioRoute.Speaker),
    val connectionPhase: ConnectionPhase = ConnectionPhase.Healthy,
    val networkType: NetworkType = NetworkType.NONE,
    val quality: CallQualityStats = CallQualityStats.EMPTY
) {
    /** Best human-readable label for the remote party. */
    val title: String get() = displayName.ifBlank { remoteUri }

    val isRinging: Boolean
        get() = state == Call.State.IncomingReceived ||
            state == Call.State.OutgoingRinging ||
            state == Call.State.OutgoingProgress ||
            state == Call.State.OutgoingInit

    val isConnected: Boolean
        get() = state == Call.State.Connected || state == Call.State.StreamsRunning

    val isTerminated: Boolean
        get() = state == Call.State.End || state == Call.State.Released || state == Call.State.Error
}
