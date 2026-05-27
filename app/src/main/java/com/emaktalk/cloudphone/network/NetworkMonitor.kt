package com.emaktalk.cloudphone.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Network type, surfaced to the UI and used by [SipCoreManager] to decide
 * whether a handoff happened (which requires re-REGISTER + ICE restart).
 */
enum class NetworkType { NONE, WIFI, CELLULAR, ETHERNET, OTHER }

data class NetworkSnapshot(
    val type: NetworkType,
    val networkId: Long,
    val isMetered: Boolean
) {
    companion object {
        val DISCONNECTED = NetworkSnapshot(NetworkType.NONE, -1, false)
    }
}

/**
 * Watches the OS-level active network and notifies a single listener when:
 *
 *  - The phone goes online or offline ([onAvailable] / [onLost]).
 *  - The user moves between two distinct networks (e.g. WiFi -> LTE). We detect
 *    this by tracking the network's [Network.getNetworkHandle], which is stable
 *    per OS network instance.
 *
 * Designed for one consumer (the SIP layer). Start once at app launch, stop on
 * teardown.
 */
class NetworkMonitor(private val context: Context) {

    fun interface Listener {
        /**
         * Called on the main thread when network reachability or identity changes.
         * Compare against the prior snapshot to decide whether a handoff occurred
         * (the [NetworkSnapshot.networkId] will differ).
         */
        fun onNetworkChanged(snapshot: NetworkSnapshot)
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _state = MutableStateFlow(NetworkSnapshot.DISCONNECTED)
    val state: StateFlow<NetworkSnapshot> = _state.asStateFlow()

    private var listener: Listener? = null
    private var registered = false

    // Tracks the current per-network metadata so we can resolve capability /
    // link-properties updates that arrive after onAvailable.
    private var current: NetworkSnapshot = NetworkSnapshot.DISCONNECTED

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = cm.getNetworkCapabilities(network)
            val snapshot = snapshotFor(network, caps)
            publish(snapshot)
        }

        override fun onLost(network: Network) {
            if (network.networkHandle == current.networkId) {
                publish(NetworkSnapshot.DISCONNECTED)
            }
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            // The transport (WiFi <-> Cellular) can swap inside a single
            // network handle on some devices; refresh whenever caps change.
            if (network.networkHandle == current.networkId || current == NetworkSnapshot.DISCONNECTED) {
                publish(snapshotFor(network, capabilities))
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            // Local IP changes (e.g. DHCP renewal, new VPN) also need a refresh
            // so SIP can re-establish a media path from the new source address.
            if (network.networkHandle == current.networkId) {
                listener?.onNetworkChanged(current)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(listener: Listener) {
        if (registered) return
        this.listener = listener
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, callback)
        registered = true

        // Seed initial state.
        val active = cm.activeNetwork
        if (active != null) {
            val caps = cm.getNetworkCapabilities(active)
            publish(snapshotFor(active, caps))
        }
    }

    fun stop() {
        if (!registered) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
        listener = null
    }

    private fun publish(snapshot: NetworkSnapshot) {
        val changed = snapshot != current
        current = snapshot
        _state.value = snapshot
        if (changed) {
            Log.i(TAG, "Network changed -> $snapshot")
            listener?.onNetworkChanged(snapshot)
        }
    }

    private fun snapshotFor(network: Network, caps: NetworkCapabilities?): NetworkSnapshot {
        val type = when {
            caps == null -> NetworkType.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.OTHER
        }
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        return NetworkSnapshot(type, network.networkHandle, metered)
    }

    companion object { private const val TAG = "NetworkMonitor" }
}
