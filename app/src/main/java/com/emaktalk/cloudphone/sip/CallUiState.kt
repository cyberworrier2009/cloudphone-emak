package com.emaktalk.cloudphone.sip

import org.linphone.core.Call

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
    val isSpeakerOn: Boolean = false
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
