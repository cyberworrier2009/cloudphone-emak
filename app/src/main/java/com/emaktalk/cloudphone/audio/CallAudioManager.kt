package com.emaktalk.cloudphone.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.AudioDevice
import org.linphone.core.Core

/**
 * Owns Android's audio plumbing for an active SIP call: communication audio mode,
 * audio focus, platform AEC/NS/AGC effects, wired headset detection, and the
 * Bluetooth HFP SCO lifecycle. The actual RTP audio path stays inside Linphone;
 * this class only picks which device Linphone reads/writes through.
 */
class CallAudioManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val routePrefs = RoutePreferences(context)

    private var core: Core? = null

    // Stored so we can restore the system audio mode after the call ends.
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedSpeakerphoneOn = false
    private var focusRequest: AudioFocusRequest? = null

    // Platform DSP effects (best-effort; not all devices expose them).
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    private val _availableRoutes = MutableStateFlow<List<AudioRoute>>(listOf(AudioRoute.Earpiece, AudioRoute.Speaker))
    val availableRoutes: StateFlow<List<AudioRoute>> = _availableRoutes.asStateFlow()

    private val _currentRoute = MutableStateFlow<AudioRoute>(AudioRoute.Earpiece)
    val currentRoute: StateFlow<AudioRoute> = _currentRoute.asStateFlow()

    // Bluetooth HFP profile state -------------------------------------------
    private var bluetoothHeadset: BluetoothHeadset? = null
    private var connectedBtDevice: BluetoothDevice? = null
    private var connectedA2dpDevice: BluetoothDevice? = null
    private var connectedLeAudioDevice: BluetoothDevice? = null
    private var scoActive = false

    private val bluetoothScoReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            scoActive = state == AudioManager.SCO_AUDIO_STATE_CONNECTED
            Log.i(TAG, "SCO state -> $state (active=$scoActive)")
            if (scoActive) {
                // Once SCO is up, Linphone will see the BT audio device; route to it.
                applyRouteToCore(_currentRoute.value)
            }
            refreshAvailableRoutes()
        }
    }

    private val headsetProfileListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.HEADSET -> {
                    bluetoothHeadset = proxy as BluetoothHeadset
                    connectedBtDevice = runCatching { proxy.connectedDevices.firstOrNull() }.getOrNull()
                }
                BluetoothProfile.A2DP -> {
                    // A2DP carries music-quality audio and is what AirPods /
                    // BT speakers use until SCO kicks in for the call. We
                    // track it so we can show the device label even when HFP
                    // hasn't negotiated yet.
                    connectedA2dpDevice = runCatching { proxy.connectedDevices.firstOrNull() }.getOrNull()
                }
                LE_AUDIO_PROFILE -> {
                    // LE Audio (API 33+) replaces HFP/A2DP with LC3-coded
                    // unicast streams. We don't yet route Linphone audio
                    // through it (Linphone defaults to SCO/A2DP), but track
                    // the device so the picker shows it.
                    connectedLeAudioDevice = runCatching { proxy.connectedDevices.firstOrNull() }.getOrNull()
                }
            }
            refreshAvailableRoutes()
        }
        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.HEADSET -> { bluetoothHeadset = null; connectedBtDevice = null }
                BluetoothProfile.A2DP -> { connectedA2dpDevice = null }
                LE_AUDIO_PROFILE -> { connectedLeAudioDevice = null }
            }
            refreshAvailableRoutes()
        }
    }

    private val bluetoothConnectionReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED)
            val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            when (state) {
                BluetoothHeadset.STATE_CONNECTED -> connectedBtDevice = device
                BluetoothHeadset.STATE_DISCONNECTED -> if (device == connectedBtDevice) connectedBtDevice = null
            }
            refreshAvailableRoutes()
        }
    }

    // Wired headset / general device hot-plug --------------------------------
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshAvailableRoutes()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshAvailableRoutes()
    }

    /** Bind to a Linphone [Core]; must be called once after init. */
    fun attachCore(core: Core) {
        this.core = core
    }

    /**
     * Start the in-call audio session: switch to communication mode, grab audio
     * focus, register hot-plug listeners, and try to enable platform DSP effects.
     */
    @SuppressLint("MissingPermission")
    fun beginCall() {
        savedAudioMode = audioManager.mode
        savedSpeakerphoneOn = audioManager.isSpeakerphoneOn

        // VoIP profile: enables the platform's AEC/NS path, routes through the
        // earpiece by default, and ducks other media.
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        requestAudioFocus()

        // Mic-side platform effects. Session 0 (global) is what Linphone's
        // OpenSL recorder uses; some devices/OEMs throw when attaching to
        // session 0 specifically, so each call is independently guarded —
        // we'd rather have a working call without one effect than no call.
        if (AcousticEchoCanceler.isAvailable()) {
            aec = runCatching { AcousticEchoCanceler.create(0)?.apply { enabled = true } }
                .onFailure { Log.w(TAG, "AcousticEchoCanceler unavailable", it) }
                .getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = runCatching { NoiseSuppressor.create(0)?.apply { enabled = true } }
                .onFailure { Log.w(TAG, "NoiseSuppressor unavailable", it) }
                .getOrNull()
        }
        if (AutomaticGainControl.isAvailable()) {
            agc = runCatching { AutomaticGainControl.create(0)?.apply { enabled = true } }
                .onFailure { Log.w(TAG, "AutomaticGainControl unavailable", it) }
                .getOrNull()
        }
        Log.i(TAG, "Platform DSP: aec=${aec != null} ns=${ns != null} agc=${agc != null}")

        registerDeviceListeners()
        connectBluetoothProfile()
        refreshAvailableRoutes()
    }

    /** Tear down the audio session and restore prior system state. */
    fun endCall() {
        stopScoIfActive()
        unregisterDeviceListeners()
        disconnectBluetoothProfile()

        aec?.release(); aec = null
        ns?.release(); ns = null
        agc?.release(); agc = null

        abandonAudioFocus()

        // Restore — don't yank the mode out from under any other VoIP app.
        audioManager.mode = savedAudioMode
        audioManager.isSpeakerphoneOn = savedSpeakerphoneOn

        _currentRoute.value = AudioRoute.Earpiece
    }

    /** Pick an output device; routes the call's audio path via Linphone. */
    fun setRoute(route: AudioRoute) {
        _currentRoute.value = route
        applyRouteToCore(route)
        // Remember this choice keyed by the route itself (so the next time the
        // same BT headset connects we re-apply the user's preference).
        routePrefs.remember(RoutePreferences.keyFor(route), route)
    }

    @SuppressLint("MissingPermission")
    private fun applyRouteToCore(route: AudioRoute) {
        val core = core ?: return
        when (route) {
            is AudioRoute.Speaker -> {
                stopScoIfActive()
                audioManager.isSpeakerphoneOn = true
                core.pickAudioDevice(AudioDevice.Type.Speaker)
            }
            is AudioRoute.Earpiece -> {
                stopScoIfActive()
                audioManager.isSpeakerphoneOn = false
                core.pickAudioDevice(AudioDevice.Type.Earpiece)
            }
            is AudioRoute.WiredHeadset -> {
                stopScoIfActive()
                audioManager.isSpeakerphoneOn = false
                // Linphone reports the wired headset under either Headphones or Earpiece on
                // some OEMs; try both.
                if (!core.pickAudioDevice(AudioDevice.Type.Headphones)) {
                    core.pickAudioDevice(AudioDevice.Type.Earpiece)
                }
            }
            is AudioRoute.Bluetooth -> {
                audioManager.isSpeakerphoneOn = false
                startScoIfNeeded()
                core.pickAudioDevice(AudioDevice.Type.Bluetooth)
            }
        }
    }

    private fun Core.pickAudioDevice(type: AudioDevice.Type): Boolean {
        val device = audioDevices.firstOrNull {
            it.type == type && it.hasCapability(AudioDevice.Capabilities.CapabilityPlay)
        } ?: return false
        currentCall?.outputAudioDevice = device
        // For incoming/early media (no current call yet), set defaults too.
        outputAudioDevice = device
        // Wire the matching capture device when present (typically same hw).
        audioDevices.firstOrNull {
            it.type == type && it.hasCapability(AudioDevice.Capabilities.CapabilityRecord)
        }?.let {
            currentCall?.inputAudioDevice = it
            inputAudioDevice = it
        }
        return true
    }

    // region Bluetooth lifecycle

    @SuppressLint("MissingPermission")
    private fun connectBluetoothProfile() {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = btManager?.adapter
            ?: BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) return
        runCatching {
            adapter.getProfileProxy(context, headsetProfileListener, BluetoothProfile.HEADSET)
        }
        runCatching {
            adapter.getProfileProxy(context, headsetProfileListener, BluetoothProfile.A2DP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                adapter.getProfileProxy(context, headsetProfileListener, LE_AUDIO_PROFILE)
            }
        }
        context.registerReceiver(
            bluetoothConnectionReceiver,
            IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        )
        context.registerReceiver(
            bluetoothScoReceiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        )
    }

    private fun disconnectBluetoothProfile() {
        runCatching { context.unregisterReceiver(bluetoothScoReceiver) }
        runCatching { context.unregisterReceiver(bluetoothConnectionReceiver) }
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        bluetoothHeadset?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        bluetoothHeadset = null
        connectedBtDevice = null
    }

    private fun startScoIfNeeded() {
        if (scoActive) return
        @Suppress("DEPRECATION") audioManager.isBluetoothScoOn = true
        @Suppress("DEPRECATION") audioManager.startBluetoothSco()
    }

    private fun stopScoIfActive() {
        if (!scoActive && !audioManager.isBluetoothScoOn) return
        @Suppress("DEPRECATION") audioManager.stopBluetoothSco()
        @Suppress("DEPRECATION") audioManager.isBluetoothScoOn = false
        scoActive = false
    }

    // endregion

    private fun registerDeviceListeners() {
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
    }

    private fun unregisterDeviceListeners() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    @SuppressLint("MissingPermission")
    private fun refreshAvailableRoutes() {
        val routes = mutableListOf<AudioRoute>()
        routes += AudioRoute.Earpiece
        routes += AudioRoute.Speaker

        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasWired = outputs.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && it.type == AudioDeviceInfo.TYPE_USB_HEADSET)
        }
        if (hasWired) routes += AudioRoute.WiredHeadset

        // Pick the best-known label for the BT device. HFP carries the user's
        // chosen friendly name; A2DP and LE Audio are fallbacks for devices
        // that only paired one profile.
        val btDevice = connectedBtDevice ?: connectedA2dpDevice ?: connectedLeAudioDevice
        val btName = runCatching { btDevice?.name }.getOrNull()
        if (btDevice != null) routes += AudioRoute.Bluetooth(btName ?: "Bluetooth")

        val prior = _availableRoutes.value
        _availableRoutes.value = routes

        // If a new BT device just appeared, recall the route the user picked
        // last time they had this headset connected. Skips when nothing
        // actually changed so we don't fight the user mid-call.
        if (routes != prior) {
            val newlyAvailable = routes - prior.toSet()
            val recall = newlyAvailable
                .firstNotNullOfOrNull { routePrefs.recall(RoutePreferences.keyFor(it), routes) }
            if (recall != null && recall != _currentRoute.value) {
                Log.i(TAG, "Recalling preferred route for new device: $recall")
                _currentRoute.value = recall
                applyRouteToCore(recall)
                return
            }
        }

        // If the current route disappeared (e.g. headset unplugged), fall back.
        if (_currentRoute.value !in routes) {
            val fallback = if (hasWired) AudioRoute.WiredHeadset else AudioRoute.Earpiece
            setRoute(fallback)
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { /* handled implicitly by communication mode */ }
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    companion object {
        private const val TAG = "CallAudioManager"
        /**
         * BluetoothProfile.LE_AUDIO. Constant resolved at runtime so we can
         * compile against API 24 without a SDK-version stub. Available on
         * Android 13+; getProfileProxy() returns false on older devices.
         */
        private const val LE_AUDIO_PROFILE = 22
    }
}
