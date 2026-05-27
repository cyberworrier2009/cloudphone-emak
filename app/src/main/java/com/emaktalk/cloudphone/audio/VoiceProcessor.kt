package com.emaktalk.cloudphone.audio

/**
 * Pluggable audio frame post-processor for advanced noise suppression / echo
 * cancellation. The default implementation is a no-op; production builds drop
 * in a Krisp or RNNoise-backed implementation that actually scrubs frames.
 *
 * **Why this is an interface, not a concrete impl:** Linphone owns the audio
 * pipeline via its native mediastreamer2 filter graph. To intercept frames
 * we either (a) ship a custom mediastreamer2 filter as a JNI library that
 * Linphone loads at boot, or (b) integrate a SDK like Krisp that hooks the
 * Android audio path. Both require external work; the interface marks the
 * seam so the rest of the app doesn't change when that work happens.
 *
 * Wire-up path for an RNNoise implementation:
 *  1. Compile RNNoise as `librnnoise.so` and ship in `app/src/main/jniLibs/`.
 *  2. Implement a Linphone mediastreamer2 filter (`MSFilter`) in C++ that
 *     calls `rnnoise_process_frame()` on each 480-sample buffer.
 *  3. Build that filter as `libmsrnnoise.so`, drop alongside `librnnoise.so`.
 *  4. From Kotlin, call `Factory.instance().setMsPluginsDir(...)` so the SDK
 *     picks up the filter, and `core.config.setString("sound", "noise_suppression_engine", "rnnoise")`.
 *  5. Implement [VoiceProcessor] backed by the same filter for any frames
 *     the app processes outside Linphone (e.g. preview meters).
 *
 * For Krisp: link against `krisp-android.aar`, implement [VoiceProcessor]
 * forwarding to its SDK, and apply the same `Factory.setMsPluginsDir` trick
 * with the Krisp-provided MS2 filter.
 */
interface VoiceProcessor {
    /** Process a 16-bit mono PCM frame in place at the given sample rate. */
    fun processFrame(pcm: ShortArray, sampleRate: Int)
    /** Free native resources. */
    fun release()
}

/** No-op default. Replace via [VoiceProcessing.install] at app start. */
object PassthroughVoiceProcessor : VoiceProcessor {
    override fun processFrame(pcm: ShortArray, sampleRate: Int) = Unit
    override fun release() = Unit
}

/** App-wide handle so the SIP layer (or future MS2 bridge) can find the processor. */
object VoiceProcessing {
    @Volatile var current: VoiceProcessor = PassthroughVoiceProcessor
        private set

    fun install(processor: VoiceProcessor) {
        current.release()
        current = processor
    }
}
