package com.emaktalk.cloudphone.sip

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.emaktalk.cloudphone.audio.AudioRoute
import com.emaktalk.cloudphone.audio.CallAudioManager
import com.emaktalk.cloudphone.audio.MediaButtonHandle
import com.emaktalk.cloudphone.network.NetworkMonitor
import com.emaktalk.cloudphone.network.NetworkSnapshot
import com.emaktalk.cloudphone.network.NetworkType
import com.emaktalk.cloudphone.push.PushTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.linphone.core.Account
import org.linphone.core.Call
import org.linphone.core.CallStats
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.PayloadType
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import java.io.File

/**
 * Thin wrapper around the Linphone [Core] that owns SIP registration, call
 * control, network handoff, and the platform audio session for the whole app.
 * Initialized once from [com.emaktalk.cloudphone.CloudPhoneApplication].
 *
 * All public methods are expected to be called from the main thread; Linphone
 * callbacks are delivered on the main thread via the core's auto-iterate timer.
 */
object SipCoreManager {

    private const val TAG = "SipCoreManager"

    private lateinit var core: Core
    private lateinit var appContext: Context
    private lateinit var audio: CallAudioManager
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var mediaButtons: MediaButtonHandle

    private val scope = CoroutineScope(Dispatchers.Main)

    private val _registrationState = MutableStateFlow(RegistrationState.None)
    val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    /** Last message reported by the SIP server (e.g. "Forbidden", "Timeout"). */
    private val _registrationMessage = MutableStateFlow("")
    val registrationMessage: StateFlow<String> = _registrationMessage.asStateFlow()

    private val _callState = MutableStateFlow<CallUiState?>(null)
    val callState: StateFlow<CallUiState?> = _callState.asStateFlow()

    /** One-shot reason the last call failed/ended, shown to the user then cleared. */
    private val _callError = MutableStateFlow<String?>(null)
    val callError: StateFlow<String?> = _callError.asStateFlow()

    private var muted = false
    private var phase: ConnectionPhase = ConnectionPhase.Healthy
    private var quality: CallQualityStats = CallQualityStats.EMPTY
    private var lastNetwork: NetworkSnapshot = NetworkSnapshot.DISCONNECTED

    // Quality-degradation hysteresis: avoid flapping the "Reconnecting" banner
    // on a single bad stats sample. We require [POOR_SAMPLES_TO_DEGRADE]
    // consecutive sub-MOS_FLOOR samples before flipping to Reconnecting, and
    // [GOOD_SAMPLES_TO_RECOVER] consecutive healthy samples before flipping back.
    private var consecutivePoorSamples = 0
    private var consecutiveGoodSamples = 0
    private var degradedByQuality = false

    private const val MOS_FLOOR = 2.0f
    private const val MOS_HEALTHY = 3.2f
    private const val POOR_SAMPLES_TO_DEGRADE = 3
    private const val GOOD_SAMPLES_TO_RECOVER = 5

    // Stored account params so we can reapply push contact params if the token
    // arrives after registration.
    private var pendingRegistration: PendingRegistration? = null

    private data class PendingRegistration(
        val username: String,
        val password: String,
        val domain: String,
        val transport: TransportType
    )

    // The Linphone core only makes progress (registration, calls, callbacks) while
    // iterate() is pumped regularly. We drive it ourselves on the main thread for
    // reliability instead of relying on auto-iterate.
    private val handler = Handler(Looper.getMainLooper())
    private val iterateRunnable = object : Runnable {
        override fun run() {
            if (::core.isInitialized) core.iterate()
            handler.postDelayed(this, 20)
        }
    }

    private val coreListener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState,
            message: String
        ) {
            Log.i(TAG, "Registration state -> $state ($message)")
            _registrationState.value = state
            _registrationMessage.value = message
        }

        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State,
            message: String
        ) {
            Log.i(TAG, "Call state -> $state ($message)")
            when (state) {
                Call.State.OutgoingInit, Call.State.IncomingReceived -> {
                    muted = false
                    quality = CallQualityStats.EMPTY
                    phase = ConnectionPhase.Healthy
                    consecutivePoorSamples = 0
                    consecutiveGoodSamples = 0
                    degradedByQuality = false
                    audio.beginCall()
                    mediaButtons.acquire()
                    CallForegroundService.start(appContext, call.remoteAddress.displayName ?: "Call")
                }
                Call.State.Connected, Call.State.StreamsRunning -> {
                    // Media flowing again after a handoff: clear the Reconnecting banner.
                    if (phase == ConnectionPhase.Reconnecting) phase = ConnectionPhase.Healthy
                }
                Call.State.Error -> {
                    val phrase = call.errorInfo.phrase
                    val reason = if (!phrase.isNullOrBlank()) phrase else call.reason.toString()
                    _callError.value = "Call failed: $reason"
                    Log.w(TAG, "Call error: reason=$reason message=$message")
                }
                else -> Unit
            }
            if (state == Call.State.Released) {
                audio.endCall()
                mediaButtons.release()
                CallForegroundService.stop(appContext)
                _callState.value = null
            } else {
                _callState.value = call.toUiState()
            }
        }

        override fun onCallStatsUpdated(core: Core, call: Call, stats: CallStats) {
            // Linphone surfaces stats per stream (audio/video); we only care about audio.
            if (stats.type != org.linphone.core.StreamType.Audio) return
            val mos = call.currentQuality
            quality = CallQualityStats(
                mos = mos,
                downloadKbps = stats.downloadBandwidth,
                uploadKbps = stats.uploadBandwidth,
                jitterMs = stats.jitterBufferSizeMs,
                roundTripMs = (stats.roundTripDelay * 1000).toFloat(),
                lossRate = stats.senderLossRate.toFloat()
            )

            // Hysteresis: don't flap the banner on a single bad sample. We
            // need a sustained dip to call it Reconnecting, and a sustained
            // recovery to clear it.
            if (mos in 0.01f..MOS_FLOOR) {
                consecutivePoorSamples++
                consecutiveGoodSamples = 0
                if (consecutivePoorSamples >= POOR_SAMPLES_TO_DEGRADE &&
                    phase == ConnectionPhase.Healthy
                ) {
                    degradedByQuality = true
                    phase = ConnectionPhase.Reconnecting
                    Log.w(TAG, "Quality degraded (MOS=$mos), surfacing Reconnecting banner")
                }
            } else if (mos >= MOS_HEALTHY) {
                consecutiveGoodSamples++
                consecutivePoorSamples = 0
                if (consecutiveGoodSamples >= GOOD_SAMPLES_TO_RECOVER &&
                    degradedByQuality && phase == ConnectionPhase.Reconnecting
                ) {
                    degradedByQuality = false
                    phase = ConnectionPhase.Healthy
                    Log.i(TAG, "Quality recovered (MOS=$mos), clearing banner")
                }
            }

            _callState.value = _callState.value?.copy(quality = quality, connectionPhase = phase)
        }
    }

    /** Creates and starts the Linphone core. Safe to call once. */
    fun initialize(context: Context) {
        if (::core.isInitialized) return
        appContext = context.applicationContext

        audio = CallAudioManager(appContext)
        networkMonitor = NetworkMonitor(appContext)
        mediaButtons = MediaButtonHandle(appContext)

        val factory = Factory.instance()
        // Route Linphone's native logs to logcat (tag "Emak") to aid diagnosis.
        // Set to false (or remove) for release builds.
        factory.setDebugMode(true, "Emak")

        // Persist account / settings between launches.
        val configFile = File(appContext.filesDir, ".linphonerc").absolutePath
        core = factory.createCore(configFile, null, appContext)
        core.addListener(coreListener)
        core.start()
        audio.attachCore(core)

        configureForCallQuality(factory)

        // We pump iterate() manually, so disable the built-in auto-iterate timer.
        core.isAutoIterateEnabled = false
        handler.post(iterateRunnable)

        // Start watching for network handoffs.
        networkMonitor.start { snapshot -> onNetworkChanged(snapshot) }

        // Plumb route/picker state from the audio manager into the call snapshot.
        scope.launch {
            audio.currentRoute.collect { route ->
                _callState.value = _callState.value?.copy(
                    audioRoute = route,
                    isSpeakerOn = route is AudioRoute.Speaker
                )
            }
        }
        scope.launch {
            audio.availableRoutes.collect { routes ->
                _callState.value = _callState.value?.copy(availableRoutes = routes)
            }
        }
        scope.launch {
            PushTokenStore.token.collect { _ ->
                // Token arrived/changed after we registered: refresh the contact params.
                pendingRegistration?.let { applyRegistration(it) }
            }
        }
    }

    /**
     * Apply the settings that have the biggest impact on perceived call quality:
     * tuned Opus, wideband fallbacks, echo cancellation, adaptive rate/jitter,
     * STUN/ICE for NAT traversal, and disabling video so we don't waste resources
     * negotiating it.
     */
    private fun configureForCallQuality(factory: Factory) {
        // ----- Codecs ------------------------------------------------------
        // Keep Opus + G.722 (wideband HD) and G.711 PCMA/PCMU as fallback.
        // Drop narrowband/legacy codecs (GSM, iLBC, AMR, speex, etc.) that
        // tank perceived quality when negotiated.
        val preferred = setOf("opus", "g722", "pcma", "pcmu")
        core.audioPayloadTypes.forEach { pt ->
            val keep = pt.mimeType.lowercase() in preferred
            pt.enable(keep)
            if (keep) tunePayload(pt)
        }

        // ----- Audio processing -------------------------------------------
        // Echo cancellation is essential for speakerphone and many handsets.
        // The Linphone EC runs in addition to whatever the platform applies
        // when MODE_IN_COMMUNICATION is set.
        core.isEchoCancellationEnabled = true

        // Let the codec bitrate adapt to network conditions instead of
        // stuttering when bandwidth drops.
        core.isAdaptiveRateControlEnabled = true

        // Adaptive de-jitter smooths bursty packet arrival. We bump the
        // initial buffer to 120ms which trades ~60ms of mouth-to-ear latency
        // for far better resilience on 3G/spotty WiFi.
        core.isAudioAdaptiveJittcompEnabled = true
        core.audioJittcomp = 120

        // Apply low-level Linphone configuration: these are the knobs that
        // aren't exposed as first-class Core properties but make a meaningful
        // perceptual difference on mobile.
        applyAdvancedConfig()

        // ----- NAT traversal ----------------------------------------------
        // Without STUN/ICE, audio packets often can't reach a phone behind
        // NAT (mobile networks, home routers) -> "connected but no audio".
        val natPolicy = core.createNatPolicy()
        natPolicy.stunServer = "stun.linphone.org"
        natPolicy.isStunEnabled = true
        natPolicy.isIceEnabled = true
        // Turn on UPnP-style port mapping when available; ICE re-uses the same
        // candidate pool which we want to refresh on handoff.
        core.natPolicy = natPolicy

        // ----- Audio-only -------------------------------------------------
        core.isVideoCaptureEnabled = false
        core.isVideoDisplayEnabled = false
        val vap = factory.createVideoActivationPolicy()
        vap.automaticallyInitiate = false
        vap.automaticallyAccept = false
        core.videoActivationPolicy = vap
    }

    /**
     * Per-codec tuning. The defaults Linphone ships with are conservative for
     * desktop; on mobile we want Opus in voip mode, with in-band FEC on so
     * we can recover from single-packet loss without re-transmission.
     */
    private fun tunePayload(pt: PayloadType) {
        when (pt.mimeType.lowercase()) {
            "opus" -> {
                // Target ~24 kbps which is the sweet spot for fullband speech
                // on cellular; adaptive rate will drop it lower if the link
                // can't sustain that.
                pt.normalBitrate = 24
                // RFC 7587 / 7845 SDP fmtp parameters tuned for mobile VoIP:
                //   maxplaybackrate=16000  request narrowband neighbour on poor links
                //   maxaveragebitrate=32000 hard ceiling so the codec can't burst
                //                          past adaptive-rate-control's budget
                //   stereo=0               mono saves bandwidth and CPU
                //   sprop-stereo=0
                //   useinbandfec=1         recover one lost packet without retransmit
                //   usedtx=0               DTX off (we never want comfort-noise gaps)
                //   cbr=0                  let the encoder vary bitrate within the cap
                //   ptime=20 / minptime=20 20ms frame size: best latency/quality trade
                //   expected-pkt-loss=10   bias the encoder toward redundancy upfront,
                //                          adaptive feedback drives it from there
                val opusFmtp =
                    "maxplaybackrate=16000;maxaveragebitrate=32000;stereo=0;sprop-stereo=0;" +
                        "useinbandfec=1;usedtx=0;cbr=0;ptime=20;minptime=20;expected-pkt-loss=10"
                pt.sendFmtp = opusFmtp
                pt.recvFmtp = opusFmtp
            }
            "g722" -> pt.normalBitrate = 64
        }
    }

    /**
     * Tune knobs exposed only via Linphone's flat `[section] key = value`
     * config. Each of these has a measurable impact on perceived quality on
     * mobile but isn't surfaced as a Core property.
     */
    private fun applyAdvancedConfig() {
        val cfg = core.config

        // ----- RTP layer (loss resilience + media-path health) -----
        // RFC 2198 redundant audio: send the prior payload alongside each new
        // one. Costs ~2x audio bandwidth (still <50 kbps with Opus) but
        // recovers single-packet loss instantly with no codec FEC delay.
        cfg.setInt("rtp", "audio_payload_redundancy", 1)
        // Send RTCP every 2.5s so we get up-to-date loss/jitter telemetry and
        // the remote can adapt its encoding.
        cfg.setFloat("rtp", "rtcp_interval", 2.5f)
        // Always-on RTCP for jitter buffer feedback.
        cfg.setInt("rtp", "rtcp_enabled", 1)
        // Use symmetric RTP/RTCP-mux so a single port survives NAT rebinds.
        cfg.setInt("rtp", "rtp_port", -1) // -1 = pick freely
        cfg.setInt("rtp", "rtcp_mux", 1)

        // ----- Jitter buffer bounds (mobile-tuned) -----
        // Default range is 40-1000ms; tighten the floor and widen the
        // working ceiling so we have headroom on cellular without
        // compressing on quiet links.
        cfg.setInt("audio", "jitter_buffer_min_size", 60)
        cfg.setInt("audio", "jitter_buffer_max_size", 500)
        cfg.setInt("audio", "jitter_buffer_nom_size", 120)
        // Allow PLC (packet loss concealment) — interpolates missing frames
        // from the previous one's spectrum instead of emitting silence.
        cfg.setInt("sound", "plc", 1)

        // ----- Echo limiter + comfort noise (post-AEC residual) -----
        // After Linphone's AEC the residual is small but still audible on
        // speakerphone; the echo limiter clamps it and the comfort-noise
        // generator fills silence so the line doesn't sound dead.
        cfg.setInt("sound", "el", 1)               // echo limiter on
        cfg.setFloat("sound", "el_thres", 0.012f)   // residual threshold
        cfg.setFloat("sound", "el_force", 1000f)    // ms of forced attenuation
        cfg.setInt("sound", "el_sustain", 50)       // ms hold
        cfg.setInt("sound", "cng", 1)              // comfort noise generator

        // ----- Mic gain / capture path -----
        // Slight positive mic gain compensates for the AGC dipping volume on
        // some devices when MODE_IN_COMMUNICATION is active.
        cfg.setFloat("sound", "mic_gain_db", 3.0f)
        // Use the device's voice-recognition input source when available; it
        // applies the OEM's voice-comm DSP (Qualcomm Fluence, Samsung CallSync,
        // etc.) on top of our chain.
        cfg.setInt("sound", "android_microphone_voice_communication", 1)

        // ----- SIP keepalive (catches NAT rebind before the call drops) -----
        // CRLF keepalive every 30s keeps UDP NAT mappings open on cellular,
        // which otherwise expire in ~60s and cause silent registration loss.
        cfg.setInt("sip", "keepalive_period", 30000)
        // Refresh registrations every 5 minutes (server's Expires header still
        // overrides if it's shorter).
        cfg.setInt("sip", "register_period", 300)
    }

    // region Registration

    fun register(
        username: String,
        password: String,
        domain: String,
        transport: TransportType = TransportType.Tls
    ) {
        val reg = PendingRegistration(username.trim(), password, domain.trim(), transport)
        pendingRegistration = reg
        applyRegistration(reg)
    }

    private fun applyRegistration(reg: PendingRegistration) {
        val factory = Factory.instance()

        // Start from a clean slate so re-registering replaces the previous account.
        core.clearAccounts()
        core.clearAllAuthInfo()

        val authInfo = factory.createAuthInfo(
            /* username = */ reg.username,
            /* userid = */ null,
            /* passwd = */ reg.password,
            /* ha1 = */ null,
            /* realm = */ null,
            /* domain = */ reg.domain
        )
        core.addAuthInfo(authInfo)

        val params = core.createAccountParams()
        params.identityAddress = factory.createAddress("sip:${reg.username}@${reg.domain}")
        val serverAddress = factory.createAddress("sip:${reg.domain}")
        serverAddress?.transport = reg.transport
        params.serverAddress = serverAddress
        params.isRegisterEnabled = true

        // Attach RFC 8599 push parameters to the SIP REGISTER contact when a
        // push token is available, so FreeSWITCH (mod_push) can fire an FCM
        // wakeup before sending the INVITE.
        PushTokenStore.token.value?.let { token ->
            params.contactParameters = token.toContactParams()
        }

        val account = core.createAccount(params)
        core.addAccount(account)
        core.defaultAccount = account

        // Optimistic feedback; the real state arrives via the registration callback.
        _registrationMessage.value = ""
        _registrationState.value = RegistrationState.Progress
        Log.i(TAG, "Registering sip:${reg.username}@${reg.domain} over ${reg.transport}")
    }

    fun unregister() {
        pendingRegistration = null
        core.clearAccounts()
        core.clearAllAuthInfo()
        _registrationState.value = RegistrationState.Cleared
    }

    val isRegistered: Boolean
        get() = _registrationState.value == RegistrationState.Ok

    // endregion

    // region Call control

    fun startCall(rawNumber: String) {
        val number = rawNumber.trim()
        if (number.isEmpty()) return

        _callError.value = null

        val remoteAddress = buildAddress(number) ?: run {
            Log.w(TAG, "Unable to build address for '$number'")
            _callError.value = if (isRegistered) {
                "Invalid number"
            } else {
                "Register an account first, or dial a full sip:user@domain address"
            }
            return
        }
        Log.i(TAG, "Placing call to ${remoteAddress.asStringUriOnly()}")
        val params = core.createCallParams(null)
        if (params == null) {
            _callError.value = "Couldn't start the call (no media parameters)."
            Log.w(TAG, "createCallParams returned null")
            return
        }

        val call = core.inviteAddressWithParams(remoteAddress, params)
        if (call == null) {
            _callError.value = "Couldn't start the call. Check microphone permission and network."
            Log.w(TAG, "inviteAddressWithParams returned null")
            return
        }

        // Optimistically publish the call so the in-call screen appears immediately,
        // even if Linphone conflates a fast OutgoingInit -> Error -> Released sequence.
        _callState.value = CallUiState(
            state = Call.State.OutgoingInit,
            remoteUri = remoteAddress.asStringUriOnly(),
            displayName = remoteAddress.displayName ?: remoteAddress.username.orEmpty(),
            isOutgoing = true,
            audioRoute = audio.currentRoute.value,
            availableRoutes = audio.availableRoutes.value,
            connectionPhase = phase,
            networkType = lastNetwork.type
        )
    }

    fun clearCallError() {
        _callError.value = null
    }

    fun acceptCall() {
        core.currentCall?.accept()
    }

    fun terminateCall() {
        val call = core.currentCall ?: core.calls.firstOrNull()
        call?.terminate()
    }

    fun toggleMute(): Boolean {
        muted = !muted
        core.isMicEnabled = !muted
        _callState.value = _callState.value?.copy(isMuted = muted)
        return muted
    }

    /** Cycles between earpiece and speaker; for richer routing use [setAudioRoute]. */
    fun toggleSpeaker(): Boolean {
        val next = if (audio.currentRoute.value is AudioRoute.Speaker) {
            AudioRoute.Earpiece
        } else {
            AudioRoute.Speaker
        }
        audio.setRoute(next)
        return next is AudioRoute.Speaker
    }

    fun setAudioRoute(route: AudioRoute) {
        audio.setRoute(route)
    }

    /** Sends a DTMF digit over the active call (RFC 4733) and plays a local tone. */
    fun sendDtmf(digit: Char) {
        core.currentCall?.sendDtmf(digit)
        core.playDtmf(digit, 100)
    }

    /** Plays a short local DTMF tone for keypad feedback when not in a call. */
    fun playKeypadTone(digit: Char) {
        core.playDtmf(digit, 100)
    }

    /**
     * Manually re-runs the network handoff sequence. Exposed to the UI as
     * a "Reconnect" button when the call is stuck in [ConnectionPhase.Lost]
     * or [ConnectionPhase.Reconnecting] for too long.
     */
    fun forceReconnect() {
        Log.i(TAG, "Manual reconnect requested")
        phase = ConnectionPhase.Reconnecting
        consecutivePoorSamples = 0
        consecutiveGoodSamples = 0
        degradedByQuality = false
        core.setNetworkReachable(false)
        handler.postDelayed({
            core.setNetworkReachable(true)
            core.refreshRegisters()
            core.currentCall?.let { it.update(it.currentParams) }
        }, 200)
        _callState.value = _callState.value?.copy(connectionPhase = phase)
    }

    /** Called from [MediaButtonReceiver] when the user presses a headset hook. */
    fun onHeadsetHook() {
        val call = core.currentCall ?: core.calls.firstOrNull() ?: return
        when (call.state) {
            Call.State.IncomingReceived -> call.accept()
            Call.State.StreamsRunning, Call.State.Connected,
            Call.State.OutgoingInit, Call.State.OutgoingProgress, Call.State.OutgoingRinging -> {
                call.terminate()
            }
            else -> Unit
        }
    }

    // endregion

    // region Network handoff

    /**
     * Drives Linphone through a WiFi <-> cellular handoff. We toggle
     * networkReachable so the core tears down the current RTP/SIP sockets,
     * refresh the registration on the new interface, then re-negotiate the
     * media path for any active call (this triggers ICE restart on the new
     * IP).
     */
    private fun onNetworkChanged(snapshot: NetworkSnapshot) {
        val previous = lastNetwork
        lastNetwork = snapshot

        val cameOnline = previous.type == NetworkType.NONE && snapshot.type != NetworkType.NONE
        val wentOffline = snapshot.type == NetworkType.NONE
        val swapped = previous.networkId != snapshot.networkId &&
            previous.type != NetworkType.NONE &&
            snapshot.type != NetworkType.NONE

        if (wentOffline) {
            phase = ConnectionPhase.Lost
            core.setNetworkReachable(false)
        } else if (swapped) {
            phase = ConnectionPhase.Reconnecting
            // Mark the old network unreachable so Linphone drops its stale sockets...
            core.setNetworkReachable(false)
            // ...then bring it back up after a beat so the new local IP is picked up.
            handler.postDelayed({
                core.setNetworkReachable(true)
                core.refreshRegisters()
                core.currentCall?.let { it.update(it.currentParams) }
            }, 200)
        } else if (cameOnline) {
            phase = ConnectionPhase.Healthy
            core.setNetworkReachable(true)
            core.refreshRegisters()
        }

        _callState.value = _callState.value?.copy(
            connectionPhase = phase,
            networkType = snapshot.type
        )
    }

    // endregion

    private fun buildAddress(number: String): org.linphone.core.Address? {
        if (number.startsWith("sip:")) {
            return core.interpretUrl(number)
        }
        val domain = core.defaultAccount?.params?.domain
        return if (!domain.isNullOrBlank()) {
            Factory.instance().createAddress("sip:$number@$domain")
        } else {
            core.interpretUrl(number)
        }
    }

    private fun Call.toUiState(): CallUiState {
        val remote = remoteAddress
        return CallUiState(
            state = state ?: Call.State.Idle,
            remoteUri = remote.asStringUriOnly(),
            displayName = remote.displayName ?: remote.username.orEmpty(),
            isOutgoing = dir == Call.Dir.Outgoing,
            isMuted = muted,
            isSpeakerOn = audio.currentRoute.value is AudioRoute.Speaker,
            audioRoute = audio.currentRoute.value,
            availableRoutes = audio.availableRoutes.value,
            connectionPhase = phase,
            networkType = lastNetwork.type,
            quality = quality
        )
    }
}
