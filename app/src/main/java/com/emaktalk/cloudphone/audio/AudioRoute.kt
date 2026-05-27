package com.emaktalk.cloudphone.audio

/**
 * Logical audio output that the user can pick in-call. Maps to one or more
 * Linphone [org.linphone.core.AudioDevice]s at the SIP layer.
 */
sealed class AudioRoute(val label: String) {
    object Earpiece : AudioRoute("Phone")
    object Speaker : AudioRoute("Speaker")
    object WiredHeadset : AudioRoute("Headset")
    data class Bluetooth(val deviceName: String) : AudioRoute(deviceName)

    val id: String get() = when (this) {
        is Earpiece -> "earpiece"
        is Speaker -> "speaker"
        is WiredHeadset -> "wired"
        is Bluetooth -> "bt:$deviceName"
    }
}
