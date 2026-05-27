package com.emaktalk.cloudphone.audio

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-device audio route memory. When the user picks Speaker on a specific
 * Bluetooth headset, we remember that and re-apply it the next time the
 * same headset connects — so they don't have to manually pick the route
 * every call. Cars commonly get "Bluetooth" stuck, AirPods commonly get
 * "Bluetooth" stuck, but for low-quality dollar-store headsets users
 * often prefer "Earpiece" — this learns that.
 *
 * Stored as a flat `key=route-id` map; `key` is either a stable BT device
 * name or a special string for the non-BT defaults.
 */
class RoutePreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("audio_route_prefs", Context.MODE_PRIVATE)

    fun remember(key: String, route: AudioRoute) {
        prefs.edit().putString(key, route.id).apply()
    }

    /** Returns the previously-chosen route for [key], or null if none. */
    fun recall(key: String, available: List<AudioRoute>): AudioRoute? {
        val id = prefs.getString(key, null) ?: return null
        return available.firstOrNull { it.id == id }
    }

    companion object {
        const val KEY_DEFAULT = "__default__"
        fun keyFor(route: AudioRoute): String = when (route) {
            is AudioRoute.Bluetooth -> "bt:${route.deviceName}"
            else -> KEY_DEFAULT
        }
    }
}
