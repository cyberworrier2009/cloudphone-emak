package com.emaktalk.cloudphone.sip

/**
 * Snapshot of the active call's media quality, refreshed by Linphone's
 * onCallStatsUpdated callback (~1Hz). Surfaced to the UI so the user can see
 * when the line is degrading.
 *
 * Quality is Linphone's MOS estimate on a 0–5 scale; <2.0 means audio is
 * likely garbled, 3.5+ is "sounds fine".
 */
data class CallQualityStats(
    val mos: Float,
    val downloadKbps: Float,
    val uploadKbps: Float,
    val jitterMs: Float,
    val roundTripMs: Float,
    val lossRate: Float
) {
    /** Five buckets so we can drive a signal-bars indicator without flicker. */
    val bars: Int get() = when {
        mos <= 0f -> 0           // No data yet.
        mos < 1.5f -> 1
        mos < 2.5f -> 2
        mos < 3.5f -> 3
        mos < 4.2f -> 4
        else -> 5
    }

    companion object {
        val EMPTY = CallQualityStats(0f, 0f, 0f, 0f, 0f, 0f)
    }
}
