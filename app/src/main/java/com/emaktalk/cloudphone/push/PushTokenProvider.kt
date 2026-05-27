package com.emaktalk.cloudphone.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * RFC 8599 push parameters surfaced to the SIP REGISTER contact so FreeSWITCH
 * (e.g. via mod_push) can wake the app on incoming INVITEs. We are deliberately
 * agnostic about FCM vs APNs vs anything else; the app supplies the token and
 * we render the right contact params.
 *
 * To wire FCM: add the Firebase SDK, derive the token in your FirebaseMessagingService,
 * then call [PushTokenStore.update] with provider="fcm.googleapis.com" and the
 * token as `prid`. To unregister push, call [PushTokenStore.update] with null.
 */
data class PushToken(
    /** RFC 8599 `pn-provider` — e.g. "fcm.googleapis.com" or "apns". */
    val provider: String,
    /** RFC 8599 `pn-prid` — the device push token. */
    val prid: String,
    /** RFC 8599 `pn-param` — usually the FCM sender ID or APNs topic. */
    val param: String
) {
    /** Renders as a SIP contact parameter string (semicolon-separated). */
    fun toContactParams(): String =
        "pn-provider=$provider;pn-prid=$prid;pn-param=$param;pn-silent=1;pn-timeout=0"
}

object PushTokenStore {
    private val _token = MutableStateFlow<PushToken?>(null)
    val token: StateFlow<PushToken?> = _token.asStateFlow()

    fun update(token: PushToken?) {
        _token.value = token
    }
}
