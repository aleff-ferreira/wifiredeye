package com.a55.wifiradar.core.wifiplatform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.a55.wifiradar.core.model.GeigerFeedbackSettings
import com.a55.wifiradar.core.model.ProximityFeedbackController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

internal enum class DispatchedHapticTier {
    TIER_1_PRIMITIVE_TICK,
    TIER_2_AMPLITUDE_ONESHOT,
    TIER_3_PREDEFINED_EFFECT,
}

internal data class DispatchedHapticPulse(
    val tier: DispatchedHapticTier,
    val normalizedU: Double,
    val primitiveScale: Float? = null,
    val oneShotDurationMs: Long? = null,
    val oneShotAmplitude: Int? = null,
    val predefinedEffectId: Int? = null,
)

internal interface GeigerHapticDriver {
    val hasVibrator: Boolean
    fun emitPulse(u: Double): DispatchedHapticPulse?
    fun cancel()
}

internal interface GeigerAudioDriver {
    fun emitToneBurst(frequencyHz: Double, u: Double)
    fun stopAndRelease()
}

internal class AndroidVibratorHapticDriver(
    context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val onPulseDispatched: ((DispatchedHapticPulse) -> Unit)? = null,
) : GeigerHapticDriver {

    private val appContext: Context = context.applicationContext ?: context

    private val sonificationAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    override val hasVibrator: Boolean
        get() = safeIpcCall(default = false) {
            resolveVibrator()?.hasVibrator() == true
        }

    override fun emitPulse(u: Double): DispatchedHapticPulse? =
        safeIpcCall(default = null) {
            val vibrator = resolveVibrator() ?: return@safeIpcCall null
            if (!vibrator.hasVibrator()) return@safeIpcCall null

            val clampedU = if (u.isFinite()) u.coerceIn(0.0, 1.0) else 0.0
            val supportsPrimitiveTick = safeIpcCall(default = false) {
                vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)
            }
            val supportsAmplitude = safeIpcCall(default = false) {
                vibrator.hasAmplitudeControl()
            }

            val (effect, record) = when {
                supportsPrimitiveTick -> {
                    val scale = GeigerSignalMath.computeTier1PrimitiveScale(clampedU)
                    val composed = VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, scale)
                        .compose()
                    composed to DispatchedHapticPulse(
                        tier = DispatchedHapticTier.TIER_1_PRIMITIVE_TICK,
                        normalizedU = clampedU,
                        primitiveScale = scale,
                    )
                }

                supportsAmplitude -> {
                    val durationMs = GeigerSignalMath.computeTier2OneShotDurationMs(clampedU)
                    val amplitude = GeigerSignalMath.computeTier2OneShotAmplitude(clampedU)
                    val oneShot = VibrationEffect.createOneShot(durationMs, amplitude)
                    oneShot to DispatchedHapticPulse(
                        tier = DispatchedHapticTier.TIER_2_AMPLITUDE_ONESHOT,
                        normalizedU = clampedU,
                        oneShotDurationMs = durationMs,
                        oneShotAmplitude = amplitude,
                    )
                }

                else -> {
                    val effectId = GeigerSignalMath.computeTier3PredefinedEffectId(clampedU)
                    val predefined = VibrationEffect.createPredefined(effectId)
                    predefined to DispatchedHapticPulse(
                        tier = DispatchedHapticTier.TIER_3_PREDEFINED_EFFECT,
                        normalizedU = clampedU,
                        predefinedEffectId = effectId,
                    )
                }
            }

            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                Api33PlatformCompat.vibrateHardwareFeedback(vibrator, effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, sonificationAudioAttributes)
            }
            onPulseDispatched?.invoke(record)
            record
        }

    override fun cancel() {
        safeIpcUnit {
            resolveVibrator()?.cancel()
        }
    }

    private fun resolveVibrator(): Vibrator? =
        if (sdkInt >= Build.VERSION_CODES.S) {
            Api31PlatformCompat.getDefaultVibratorOrNull(appContext)
        } else {
            @Suppress("DEPRECATION")
            safeIpcCall(default = null) {
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }
}

internal class AndroidAudioTrackBurstDriver : GeigerAudioDriver {
    private val audioLock = Any()
    private val reusablePcmBuffer = ByteArray(GeigerSignalMath.BURST_PCM_BYTES)
    private var audioTrack: AudioTrack? = null

    override fun emitToneBurst(frequencyHz: Double, u: Double) {
        synchronized(audioLock) {
            GeigerSignalMath.synthesizeHanningSineBurstPcm16Le(
                frequencyHz = frequencyHz,
                u = u,
                targetPcmLeBytes = reusablePcmBuffer,
            )

            val succeeded = safeIpcCall(default = false) {
                val track = getOrCreateTrackLocked() ?: return@safeIpcCall false
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                }
                val written = track.write(
                    reusablePcmBuffer,
                    0,
                    GeigerSignalMath.BURST_PCM_BYTES,
                    AudioTrack.WRITE_NON_BLOCKING,
                )
                written >= 0
            }

            if (!succeeded) {
                stopAndReleaseLocked()
            }
        }
    }

    override fun stopAndRelease() {
        synchronized(audioLock) {
            stopAndReleaseLocked()
        }
    }

    private fun getOrCreateTrackLocked(): AudioTrack? {
        val existing = audioTrack
        if (existing != null &&
            safeIpcCall(default = AudioTrack.STATE_UNINITIALIZED) { existing.state } ==
            AudioTrack.STATE_INITIALIZED
        ) {
            return existing
        }
        stopAndReleaseLocked()

        return safeIpcCall(default = null) {
            val minBuffer = AudioTrack.getMinBufferSize(
                GeigerSignalMath.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val safeMin = if (minBuffer > 0) minBuffer else GeigerSignalMath.BURST_PCM_BYTES * 4
            val bufferBytes = max(safeMin, GeigerSignalMath.BURST_PCM_BYTES * 4)

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(GeigerSignalMath.SAMPLE_RATE_HZ)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            // Note: Do NOT call AudioTrack.Builder.setContext(...) (API 34+) so API 30-33 stays clean
            val created = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            if (created.state != AudioTrack.STATE_INITIALIZED) {
                safeIpcUnit { created.release() }
                null
            } else {
                audioTrack = created
                created
            }
        }
    }

    private fun stopAndReleaseLocked() {
        val track = audioTrack ?: return
        audioTrack = null
        // AudioTrack.flush() is a no-op unless paused first in MODE_STREAM
        safeIpcUnit { track.pause() }
        safeIpcUnit { track.flush() }
        safeIpcUnit { track.stop() }
        safeIpcUnit { track.release() }
    }
}

/**
 * 3-Tier LRA Haptic & Click-Free `18 ms` Hanning-Sine Audio Geiger Engine (`F22, F33, F34`).
 *
 * - Default toggles are strictly OFF (`GeigerFeedbackSettings(false, false)`).
 * - Exponential `1400 ms -> 85 ms` pulse interval & `320 -> 1400 Hz` pitch curve.
 * - Conflated wake channel anchored to `lastPulseElapsedMs` responds instantaneously when walking
 *   into strong signal without double-pulsing on `300 ms` steady connected-AP polls.
 * - Immediately cancels vibration and pauses/flushes/stops/releases `AudioTrack` (`0 ms` delay) on
 *   `isSignalLost`, `null`/`NaN` signal, toggle OFF, [stopAll] (preserving user toggle settings!),
 *   and lifecycle `onStop`.
 */
class AndroidProximityFeedbackController internal constructor(
    private val clock: PlatformClock,
    coroutineDispatcher: CoroutineDispatcher,
    internal val hapticDriver: GeigerHapticDriver,
    internal val audioDriver: GeigerAudioDriver,
) : ProximityFeedbackController, DefaultLifecycleObserver, Closeable {

    @JvmOverloads
    constructor(
        context: Context,
        clock: PlatformClock = SystemPlatformClock,
        coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(
        clock = clock,
        coroutineDispatcher = coroutineDispatcher,
        hapticDriver = AndroidVibratorHapticDriver(context = context),
        audioDriver = AndroidAudioTrackBurstDriver(),
    )

    internal constructor(
        context: Context,
        clock: PlatformClock = SystemPlatformClock,
        coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default,
        sdkInt: Int = Build.VERSION.SDK_INT,
        onHapticPulseDispatched: ((DispatchedHapticPulse) -> Unit)? = null,
    ) : this(
        clock = clock,
        coroutineDispatcher = coroutineDispatcher,
        hapticDriver = AndroidVibratorHapticDriver(
            context = context,
            sdkInt = sdkInt,
            onPulseDispatched = onHapticPulseDispatched,
        ),
        audioDriver = AndroidAudioTrackBurstDriver(),
    )

    private data class RuntimeState(
        val settings: GeigerFeedbackSettings = GeigerFeedbackSettings(
            hapticsEnabled = false,
            audioEnabled = false,
        ),
        val smoothedRssiDbm: Double? = null,
        val isSignalLost: Boolean = false,
        val isLifecycleActive: Boolean = true,
    ) {
        val shouldPulse: Boolean
            get() = isLifecycleActive &&
                (settings.hapticsEnabled || settings.audioEnabled) &&
                !isSignalLost &&
                smoothedRssiDbm != null &&
                smoothedRssiDbm.isFinite()
    }

    private val stateLock = Any()
    private val isClosed = AtomicBoolean(false)
    private val boundLifecycleRefCount = AtomicInteger(0)

    private val exceptionHandler = CoroutineExceptionHandler { _, _ ->
        // Swallowed defensively per F34
    }
    private val scope = CoroutineScope(SupervisorJob() + coroutineDispatcher + exceptionHandler)
    private val wakeChannel = Channel<Unit>(Channel.CONFLATED)

    private var runtimeState = RuntimeState()
    private var pulseJob: Job? = null
    @Volatile
    private var lastPulseElapsedMs: Long? = null

    private val _feedbackSettings = MutableStateFlow(
        GeigerFeedbackSettings(hapticsEnabled = false, audioEnabled = false),
    )
    override val feedbackSettings: StateFlow<GeigerFeedbackSettings> =
        _feedbackSettings.asStateFlow()

    override val hasHardwareVibrator: Boolean
        get() = hapticDriver.hasVibrator

    override fun setHapticsEnabled(enabled: Boolean) {
        if (isClosed.get()) return
        val shouldRunAfterUpdate: Boolean
        val updatedSettings: GeigerFeedbackSettings
        synchronized(stateLock) {
            updatedSettings = runtimeState.settings.copy(hapticsEnabled = enabled)
            runtimeState = runtimeState.copy(settings = updatedSettings)
            shouldRunAfterUpdate = runtimeState.shouldPulse
        }
        _feedbackSettings.value = updatedSettings

        if (!enabled) {
            hapticDriver.cancel()
        }
        syncPulseLoop(shouldRunAfterUpdate)
    }

    override fun setAudioEnabled(enabled: Boolean) {
        if (isClosed.get()) return
        val shouldRunAfterUpdate: Boolean
        val updatedSettings: GeigerFeedbackSettings
        synchronized(stateLock) {
            updatedSettings = runtimeState.settings.copy(audioEnabled = enabled)
            runtimeState = runtimeState.copy(settings = updatedSettings)
            shouldRunAfterUpdate = runtimeState.shouldPulse
        }
        _feedbackSettings.value = updatedSettings

        if (!enabled) {
            audioDriver.stopAndRelease()
        }
        syncPulseLoop(shouldRunAfterUpdate)
    }

    override fun updateSignal(smoothedRssiDbm: Double?, isSignalLost: Boolean) {
        if (isClosed.get()) return
        val validSignal = !isSignalLost && smoothedRssiDbm != null && smoothedRssiDbm.isFinite()
        val shouldRunAfterUpdate: Boolean
        synchronized(stateLock) {
            runtimeState = runtimeState.copy(
                smoothedRssiDbm = if (validSignal) smoothedRssiDbm else null,
                isSignalLost = isSignalLost || !validSignal,
            )
            shouldRunAfterUpdate = runtimeState.shouldPulse
        }

        if (!validSignal) {
            haltOutputsAndCancelJob()
            return
        }

        syncPulseLoop(shouldRunAfterUpdate)
    }

    /**
     * Immediately halts all vibration & audio output and clears the active signal without
     * wiping the user's [feedbackSettings] toggle preferences.
     */
    override fun stopAll() {
        synchronized(stateLock) {
            runtimeState = runtimeState.copy(
                smoothedRssiDbm = null,
                isSignalLost = false,
            )
        }
        haltOutputsAndCancelJob()
    }

    /**
     * Attaches a ref-counted [DefaultLifecycleObserver] so backgrounding immediately silences
     * haptics and audio (`F33`), while overlapping Activity recreation remains seamless.
     */
    fun bindToLifecycle(lifecycle: Lifecycle) {
        if (isClosed.get()) return
        val observer = object : DefaultLifecycleObserver {
            private var countedAsStarted = false

            override fun onStart(owner: LifecycleOwner) {
                if (!countedAsStarted) {
                    countedAsStarted = true
                    if (boundLifecycleRefCount.incrementAndGet() == 1) {
                        setLifecycleActive(true)
                    }
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                if (countedAsStarted) {
                    countedAsStarted = false
                    if (boundLifecycleRefCount.decrementAndGet().coerceAtLeast(0) == 0) {
                        setLifecycleActive(false)
                    }
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                if (countedAsStarted) {
                    countedAsStarted = false
                    if (boundLifecycleRefCount.decrementAndGet().coerceAtLeast(0) == 0) {
                        setLifecycleActive(false)
                    }
                }
                owner.lifecycle.removeObserver(this)
            }
        }
        lifecycle.addObserver(observer)
    }

    override fun onStart(owner: LifecycleOwner) {
        setLifecycleActive(true)
    }

    override fun onStop(owner: LifecycleOwner) {
        setLifecycleActive(false)
    }

    override fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        stopAll()
        scope.cancel()
    }

    private fun setLifecycleActive(active: Boolean) {
        if (isClosed.get()) return
        val shouldRunAfterUpdate: Boolean
        synchronized(stateLock) {
            runtimeState = runtimeState.copy(isLifecycleActive = active)
            shouldRunAfterUpdate = runtimeState.shouldPulse
        }
        if (!active) {
            haltOutputsAndCancelJob()
        } else {
            syncPulseLoop(shouldRunAfterUpdate)
        }
    }

    private fun syncPulseLoop(shouldRun: Boolean) {
        if (!shouldRun) {
            haltOutputsAndCancelJob()
            return
        }
        var launchedNewJob = false
        synchronized(stateLock) {
            if (runtimeState.shouldPulse && pulseJob?.isActive != true) {
                lastPulseElapsedMs = null
                pulseJob = scope.launch {
                    runPulseLoop()
                }
                launchedNewJob = true
            }
        }
        if (!launchedNewJob) {
            wakeChannel.trySend(Unit)
        }
    }

    private fun haltOutputsAndCancelJob() {
        val jobToCancel: Job?
        synchronized(stateLock) {
            jobToCancel = pulseJob
            pulseJob = null
            lastPulseElapsedMs = null
        }
        jobToCancel?.cancel()
        hapticDriver.cancel()
        audioDriver.stopAndRelease()
    }

    private suspend fun runPulseLoop() {
        while (currentCoroutineContext().isActive) {
            while (wakeChannel.tryReceive().isSuccess) {
                // Drain conflated wake tokens before reading latest state snapshot
            }

            val snapshot = synchronized(stateLock) { runtimeState }
            if (!snapshot.shouldPulse) break

            val rssi = snapshot.smoothedRssiDbm ?: break
            val u = GeigerSignalMath.computeNormalizedProximity(rssi)
            val targetIntervalMs = GeigerSignalMath.computePulseIntervalMs(u)
            val nowMs = clock.elapsedRealtimeMs()
            val prevPulseMs = lastPulseElapsedMs

            if (prevPulseMs == null) {
                dispatchPulse(snapshot.settings, u)
                lastPulseElapsedMs = clock.elapsedRealtimeMs()
                continue
            }

            val elapsedMs = (nowMs - prevPulseMs).coerceAtLeast(0L)
            val remainingMs = targetIntervalMs - elapsedMs
            if (remainingMs <= 0L) {
                dispatchPulse(snapshot.settings, u)
                lastPulseElapsedMs = clock.elapsedRealtimeMs()
            } else {
                withTimeoutOrNull(remainingMs) {
                    wakeChannel.receive()
                }
            }
        }
    }

    private fun dispatchPulse(settings: GeigerFeedbackSettings, u: Double) {
        if (settings.hapticsEnabled) {
            hapticDriver.emitPulse(u)
        }
        if (settings.audioEnabled) {
            val freqHz = GeigerSignalMath.computeToneFrequencyHz(u)
            audioDriver.emitToneBurst(freqHz, u)
        }
    }
}
