package com.emaktalk.cloudphone.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import com.emaktalk.cloudphone.sip.SipCoreManager

/**
 * Receives MEDIA_BUTTON intents from wired/Bluetooth headsets (a single click
 * answers or hangs up). Registered dynamically while a call is active by
 * [MediaButtonHandle], which owns a [MediaSession] tied to this receiver.
 */
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val event: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
        if (event == null || event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                Log.i(TAG, "Headset hook pressed (keycode=${event.keyCode})")
                SipCoreManager.onHeadsetHook()
            }
        }
    }
    companion object { private const val TAG = "MediaButtonReceiver" }
}

/**
 * Lifecycle owner for the per-call media session. Keep one instance attached
 * to the active call; release it when the call ends so we don't sit on the
 * media button focus forever.
 */
class MediaButtonHandle(private val context: Context) {

    private var session: MediaSession? = null

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun acquire() {
        if (session != null) return
        val s = MediaSession(context, "CloudPhoneCall")
        s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
        s.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(intent: Intent): Boolean {
                val event: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }
                if (event?.action == KeyEvent.ACTION_DOWN && (
                        event.keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
                            event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        )
                ) {
                    SipCoreManager.onHeadsetHook()
                    return true
                }
                return super.onMediaButtonEvent(intent)
            }
        })
        // Mark the session as active "playing" so it wins the media button route.
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build()
        )
        s.isActive = true
        session = s
    }

    fun release() {
        session?.run {
            isActive = false
            release()
        }
        session = null
    }
}
