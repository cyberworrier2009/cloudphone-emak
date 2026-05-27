package com.emaktalk.cloudphone.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives FCM messages from FreeSWITCH `mod_push` (or any push gateway) and
 * publishes token rotations to [PushTokenStore]. The SIP layer watches that
 * store and re-REGISTERs with the new RFC 8599 contact params automatically.
 *
 * Wire-up:
 *  1. Drop your real `google-services.json` into `app/`.
 *  2. On the FreeSWITCH side, configure `mod_push` (or your own bridge) to
 *     read the `pn-prid` / `pn-provider` params from the Contact URI and send
 *     a data-only FCM message to that token whenever an INVITE arrives for
 *     this AOR. The message wakes Android out of doze; the SIP REGISTER
 *     keepalive then keeps the socket alive long enough to receive the INVITE.
 *  3. Keep the FCM payload as data-only (no `notification:` field) so this
 *     class handles the call presentation, not the system notification shade.
 */
class CloudPhoneMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM token rotated; updating SIP contact params")
        PushTokenStore.update(
            PushToken(
                provider = "fcm.googleapis.com",
                prid = token,
                // FCM sender ID — required by some push gateways for routing.
                // Sourced from google-services.json at build time; fall back
                // to a generic literal if not present.
                param = SENDER_ID
            )
        )
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // The wake-up itself is what we needed; Linphone's keepalive will pull
        // the INVITE off the SIP socket. If the gateway sends extra metadata
        // (caller name, etc.) we could surface it here, but for now we just
        // log so we can confirm the path end-to-end in dev.
        Log.i(TAG, "FCM wakeup received: data=${message.data} from=${message.from}")
    }

    companion object {
        private const val TAG = "CloudPhoneFcm"

        // FreeSWITCH's mod_push expects the sender ID (or APNs topic) verbatim
        // in the contact param. Replace with your Firebase project number, or
        // pull from google-services.json via a generated resource.
        private const val SENDER_ID = "REPLACE_WITH_FIREBASE_SENDER_ID"
    }
}
