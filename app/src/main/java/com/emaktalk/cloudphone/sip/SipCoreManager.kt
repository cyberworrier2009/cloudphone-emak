package com.emaktalk.cloudphone.sip

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.linphone.core.Account
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import java.io.File

/**
 * Thin wrapper around the Linphone [Core] that owns SIP registration and call
 * control for the whole app. Initialized once from [com.emaktalk.cloudphone.CloudPhoneApplication].
 *
 * All public methods are expected to be called from the main thread; Linphone
 * callbacks are delivered on the main thread via the core's auto-iterate timer.
 */
object SipCoreManager {

    private const val TAG = "SipCoreManager"

    private lateinit var core: Core

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
    private var speakerOn = false

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
                    // New call: reset toggles.
                    muted = false
                    speakerOn = false
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
                _callState.value = null
            } else {
                _callState.value = call.toUiState()
            }
        }
    }

    /** Creates and starts the Linphone core. Safe to call once. */
    fun initialize(context: Context) {
        if (::core.isInitialized) return

        val factory = Factory.instance()
        // Route Linphone's native logs to logcat (tag "Emak") to aid diagnosis.
        // Set to false (or remove) for release builds.
        factory.setDebugMode(true, "Emak")

        // Persist account / settings between launches.
        val configFile = File(context.filesDir, ".linphonerc").absolutePath
        core = factory.createCore(configFile, null, context)
        core.addListener(coreListener)
        core.start()

        configureForCallQuality(factory)

        // We pump iterate() manually, so disable the built-in auto-iterate timer.
        core.isAutoIterateEnabled = false
        handler.post(iterateRunnable)
    }

    /**
     * Apply the settings that have the biggest impact on perceived call quality:
     * wideband codecs, echo cancellation, adaptive rate/jitter, STUN/ICE for NAT
     * traversal, and disabling video so we don't waste resources negotiating it.
     */
    private fun configureForCallQuality(factory: Factory) {
        // ----- Codecs ------------------------------------------------------
        // Keep Opus + G.722 (wideband HD) and G.711 PCMA/PCMU as fallback.
        // Drop narrowband/legacy codecs (GSM, iLBC, AMR, speex, etc.) that
        // tank perceived quality when negotiated.
        val preferred = setOf("opus", "g722", "pcma", "pcmu")
        core.audioPayloadTypes.forEach { pt ->
            pt.enable(pt.mimeType.lowercase() in preferred)
        }

        // ----- Audio processing -------------------------------------------
        // Echo cancellation is essential for speakerphone and many handsets.
        core.isEchoCancellationEnabled = true

        // Let the codec bitrate adapt to network conditions instead of
        // stuttering when bandwidth drops.
        core.isAdaptiveRateControlEnabled = true

        // Adaptive de-jitter smooths bursty packet arrival.
        core.isAudioAdaptiveJittcompEnabled = true
        core.audioJittcomp = 60 // initial jitter buffer, ms

        // ----- NAT traversal ----------------------------------------------
        // Without STUN/ICE, audio packets often can't reach a phone behind
        // NAT (mobile networks, home routers) -> "connected but no audio".
        val natPolicy = core.createNatPolicy()
        natPolicy.stunServer = "stun.linphone.org"
        natPolicy.isStunEnabled = true
        natPolicy.isIceEnabled = true
        core.natPolicy = natPolicy

        // ----- Audio-only -------------------------------------------------
        core.isVideoCaptureEnabled = false
        core.isVideoDisplayEnabled = false
        val vap = factory.createVideoActivationPolicy()
        vap.automaticallyInitiate = false
        vap.automaticallyAccept = false
        core.videoActivationPolicy = vap
    }

    // region Registration

    fun register(
        username: String,
        password: String,
        domain: String,
        transport: TransportType = TransportType.Tls
    ) {
        val factory = Factory.instance()

        // Start from a clean slate so re-registering replaces the previous account.
        core.clearAccounts()
        core.clearAllAuthInfo()

        val authInfo = factory.createAuthInfo(
            /* username = */ username,
            /* userid = */ null,
            /* passwd = */ password,
            /* ha1 = */ null,
            /* realm = */ null,
            /* domain = */ domain
        )
        core.addAuthInfo(authInfo)

        val params = core.createAccountParams()
        params.identityAddress = factory.createAddress("sip:$username@$domain")
        val serverAddress = factory.createAddress("sip:$domain")
        serverAddress?.transport = transport
        params.serverAddress = serverAddress
        params.isRegisterEnabled = true

        val account = core.createAccount(params)
        core.addAccount(account)
        core.defaultAccount = account

        // Optimistic feedback; the real state arrives via the registration callback.
        _registrationMessage.value = ""
        _registrationState.value = RegistrationState.Progress
        Log.i(TAG, "Registering sip:$username@$domain over $transport")
    }

    fun unregister() {
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
            // Usually means the mic couldn't be acquired or there's no network.
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
            isOutgoing = true
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
        refreshCallToggles()
        return muted
    }

    fun toggleSpeaker(): Boolean {
        speakerOn = !speakerOn
        val targetType = if (speakerOn) AudioDevice.Type.Speaker else AudioDevice.Type.Earpiece
        val device = core.audioDevices.firstOrNull {
            it.type == targetType && it.hasCapability(AudioDevice.Capabilities.CapabilityPlay)
        }
        if (device != null) {
            core.currentCall?.outputAudioDevice = device
        }
        refreshCallToggles()
        return speakerOn
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

    // endregion

    private fun refreshCallToggles() {
        val current = _callState.value ?: return
        _callState.value = current.copy(isMuted = muted, isSpeakerOn = speakerOn)
    }

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
            isSpeakerOn = speakerOn
        )
    }
}
