package com.a55.wifiradar.core.wifiplatform

import android.app.Application
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.a55.wifiradar.core.model.GeigerFeedbackSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowVibrator
import kotlin.math.pow

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeigerAndProximityFeedbackTest {

    private lateinit var app: Application
    private lateinit var vibrator: Vibrator

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        ShadowVibrator.reset()
        ShadowAudioTrack.resetTest()
        vibrator = app.getSystemService(VibratorManager::class.java)!!.defaultVibrator
    }

    @After
    fun tearDown() {
        ShadowVibrator.reset()
        ShadowAudioTrack.resetTest()
    }

    @Test
    fun geigerSignalMathValidatesIntervalCurvePitchMonotonicityAndZeroEndpointHanningPcm() {
        assertEquals(0.0, GeigerSignalMath.computeNormalizedProximity(-110.0), 1e-6)
        assertEquals(0.0, GeigerSignalMath.computeNormalizedProximity(-92.0), 1e-6)
        assertEquals(0.5, GeigerSignalMath.computeNormalizedProximity(-60.0), 1e-6)
        assertEquals(1.0, GeigerSignalMath.computeNormalizedProximity(-28.0), 1e-6)
        assertEquals(1.0, GeigerSignalMath.computeNormalizedProximity(-10.0), 1e-6)
        assertEquals(0.0, GeigerSignalMath.computeNormalizedProximity(Double.NaN), 1e-6)

        assertEquals(1400L, GeigerSignalMath.computePulseIntervalMs(0.0))
        assertEquals(345L, GeigerSignalMath.computePulseIntervalMs(0.5))
        assertEquals(85L, GeigerSignalMath.computePulseIntervalMs(1.0))

        assertEquals(320.0, GeigerSignalMath.computeToneFrequencyHz(0.0), 1e-6)
        assertEquals(1400.0, GeigerSignalMath.computeToneFrequencyHz(1.0), 1e-6)

        var prevInterval = Long.MAX_VALUE
        var prevPitch = 0.0
        for (rssi in -92..-28) {
            val u = GeigerSignalMath.computeNormalizedProximity(rssi.toDouble())
            val interval = GeigerSignalMath.computePulseIntervalMs(u)
            val pitch = GeigerSignalMath.computeToneFrequencyHz(u)
            assertTrue(interval <= prevInterval)
            assertTrue(pitch > prevPitch)
            prevInterval = interval
            prevPitch = pitch
        }

        // Verify 18 ms Hanning-windowed 16-bit LE PCM burst (432 samples = 864 bytes)
        val lowBurst = GeigerSignalMath.synthesizeHanningSineBurstPcm16Le(320.0, 0.0)
        val highBurst = GeigerSignalMath.synthesizeHanningSineBurstPcm16Le(1400.0, 1.0)
        assertEquals(864, lowBurst.size)
        assertEquals(864, highBurst.size)

        // Exact 0 endpoints eliminate speaker pop/click
        assertEquals(0.toShort(), decodeLeShort(lowBurst, 0))
        assertEquals(0.toShort(), decodeLeShort(lowBurst, 862))
        assertEquals(0.toShort(), decodeLeShort(highBurst, 0))
        assertEquals(0.toShort(), decodeLeShort(highBurst, 862))

        val lowZeroCrossings = countZeroCrossings(lowBurst)
        val highZeroCrossings = countZeroCrossings(highBurst)
        assertTrue(lowZeroCrossings in 8..16)
        assertTrue(highZeroCrossings > lowZeroCrossings * 3)
    }

    @Test
    fun defaultOffAndThreeTierLraHapticDispatchAcrossCapabilitiesAndApiLevels() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = TestPlatformClock(this)
        val recordedPulses = mutableListOf<DispatchedHapticPulse>()
        val shadowVibrator = Shadows.shadowOf(vibrator)

        val controller = AndroidProximityFeedbackController(
            context = app,
            clock = clock,
            coroutineDispatcher = dispatcher,
            sdkInt = 34,
            onHapticPulseDispatched = { recordedPulses += it },
        )
        try {
            // Default OFF contract: zero vibrations or audio writes even with strong signal!
            assertEquals(GeigerFeedbackSettings(false, false), controller.feedbackSettings.value)
            controller.updateSignal(-40.0, isSignalLost = false)
            advanceTimeBy(5_000L)
            runCurrent()
            assertTrue(recordedPulses.isEmpty())

            // Tier 1: PRIMITIVE_TICK supported
            shadowVibrator.setSupportedPrimitives(listOf(VibrationEffect.Composition.PRIMITIVE_TICK))
            controller.setHapticsEnabled(true)
            runCurrent()

            assertEquals(1, recordedPulses.size)
            assertEquals(DispatchedHapticTier.TIER_1_PRIMITIVE_TICK, recordedPulses.last().tier)
            val uAtMinus40 = (-40.0 - (-92.0)) / 64.0
            val expectedScale = (0.20f + 0.80f * uAtMinus40.toFloat().pow(1.2f)).coerceIn(0.0f, 1.0f)
            val segments = shadowVibrator.primitiveSegmentsInPrimitiveEffects
            assertFalse(segments.isEmpty())
            assertEquals(VibrationEffect.Composition.PRIMITIVE_TICK, segments.last().id)
            assertEquals(expectedScale, segments.last().scale, 1e-4f)

            // Tier 2: Amplitude OneShot fallback when PRIMITIVE_TICK unsupported & hasAmplitudeControl == true
            controller.stopAll()
            recordedPulses.clear()
            shadowVibrator.setSupportedPrimitives(emptyList())
            shadowVibrator.setHasAmplitudeControl(true)
            controller.updateSignal(-60.0, isSignalLost = false) // u = 0.5
            runCurrent()

            assertEquals(1, recordedPulses.size)
            val tier2 = recordedPulses.single()
            assertEquals(DispatchedHapticTier.TIER_2_AMPLITUDE_ONESHOT, tier2.tier)
            assertEquals(17L, tier2.oneShotDurationMs)
            assertEquals(136, tier2.oneShotAmplitude)
            assertEquals(17L, shadowVibrator.milliseconds)

            // Tier 3: Predefined EFFECT_TICK (u <= 0.75) vs EFFECT_CLICK (u > 0.75)
            controller.stopAll()
            recordedPulses.clear()
            shadowVibrator.setHasAmplitudeControl(false)
            controller.updateSignal(-60.0, isSignalLost = false) // u = 0.5 -> EFFECT_TICK
            runCurrent()
            assertEquals(DispatchedHapticTier.TIER_3_PREDEFINED_EFFECT, recordedPulses.single().tier)
            assertEquals(VibrationEffect.EFFECT_TICK, recordedPulses.single().predefinedEffectId)

            controller.stopAll()
            recordedPulses.clear()
            controller.updateSignal(-35.0, isSignalLost = false) // u = 0.89 -> EFFECT_CLICK
            runCurrent()
            assertEquals(VibrationEffect.EFFECT_CLICK, recordedPulses.single().predefinedEffectId)

            // No hardware vibrator -> hasHardwareVibrator == false and skips cleanly
            shadowVibrator.setHasVibrator(false)
            assertFalse(controller.hasHardwareVibrator)

            // API 30 branch resolves Context.VIBRATOR_SERVICE and pre-33 AudioAttributes vibrate()
            shadowVibrator.setHasVibrator(true)
            val api30Pulses = mutableListOf<DispatchedHapticPulse>()
            val api30Controller = AndroidProximityFeedbackController(
                context = app,
                clock = clock,
                coroutineDispatcher = dispatcher,
                sdkInt = 30,
                onHapticPulseDispatched = { api30Pulses += it },
            )
            api30Controller.setHapticsEnabled(true)
            api30Controller.updateSignal(-50.0, isSignalLost = false)
            runCurrent()
            assertEquals(1, api30Pulses.size)
            api30Controller.close()
        } finally {
            controller.close()
        }
    }

    @Test
    fun reusesSingleModeStreamAudioTrackAcrossMultipleClickFreeBurstsAndIsolatesPlayErrors() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = TestPlatformClock(this)

        val writtenTracks = mutableListOf<Int>()
        val writtenPayloads = mutableListOf<ByteArray>()
        val writtenSampleRates = mutableListOf<Int>()

        val listener = ShadowAudioTrack.OnAudioDataWrittenListener { track, byteData, format ->
            writtenTracks += System.identityHashCode(track)
            writtenPayloads += byteData.clone()
            writtenSampleRates += format.sampleRate
            assertEquals(AudioFormat.ENCODING_PCM_16BIT, format.encoding)
        }
        ShadowAudioTrack.addAudioDataListener(listener)

        val controller = AndroidProximityFeedbackController(
            context = app,
            clock = clock,
            coroutineDispatcher = dispatcher,
        )
        try {
            controller.setAudioEnabled(true)
            controller.updateSignal(-28.0, isSignalLost = false) // 85 ms interval
            runCurrent()

            // Advance across 9 additional intervals (total 10 pulses)
            advanceTimeBy(85L * 9)
            runCurrent()

            assertEquals(10, writtenPayloads.size)
            assertTrue(writtenPayloads.all { it.size == 864 })
            assertTrue(writtenSampleRates.all { it == 24_000 })
            assertTrue(
                writtenPayloads.all {
                    decodeLeShort(it, 0) == 0.toShort() && decodeLeShort(it, 862) == 0.toShort()
                },
            )
            // Crucial: exact ONE AudioTrack instance reused across all 10 pulses (zero native leak!)
            assertEquals(1, writtenTracks.distinct().size)

            // Verify AudioTrack IllegalStateException isolation (F34)
            ShadowAudioTrack.enableIllegalStateOnPlay(true)
            controller.stopAll() // releases current track so next pulse creates & calls play()
            controller.updateSignal(-35.0, isSignalLost = false)
            advanceTimeBy(200L)
            runCurrent()
            // Must NOT throw!
        } finally {
            ShadowAudioTrack.enableIllegalStateOnPlay(false)
            ShadowAudioTrack.removeAudioDataListener(listener)
            controller.close()
        }
    }

    @Test
    fun wakesImmediatelyOnStrongSignalJumpAvoidsSteadyFastPathDoublePulseAndHaltsAt0ms() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = TestPlatformClock(this, initialElapsedMs = 0L)
        val pulseTimestampsMs = mutableListOf<Long>()

        val controller = AndroidProximityFeedbackController(
            context = app,
            clock = clock,
            coroutineDispatcher = dispatcher,
            sdkInt = 34,
            onHapticPulseDispatched = { pulseTimestampsMs += clock.elapsedRealtimeMs() },
        )
        try {
            controller.setHapticsEnabled(true)

            // Start at -92 dBm (1400 ms interval) at t = 0: Pulse #1 fires at t = 0
            controller.updateSignal(-92.0, isSignalLost = false)
            runCurrent()
            assertEquals(listOf(0L), pulseTimestampsMs)

            // Advance 150 ms: still waiting on 1400 ms interval
            advanceTimeBy(150L)
            runCurrent()
            assertEquals(listOf(0L), pulseTimestampsMs)

            // User walks right next to router: -28 dBm (85 ms interval) at t = 150 ms!
            // Since 150 ms >= 85 ms, wake channel MUST fire Pulse #2 immediately at t = 150 ms!
            controller.updateSignal(-28.0, isSignalLost = false)
            runCurrent()
            assertEquals(listOf(0L, 150L), pulseTimestampsMs)

            // Next pulse fires at exact +85 ms -> t = 235 ms
            advanceTimeBy(85L)
            runCurrent()
            assertEquals(listOf(0L, 150L, 235L), pulseTimestampsMs)

            // Steady 300 ms connected-AP fast-path updates at -85 dBm (1031 ms interval) must NOT double-pulse
            controller.stopAll()
            pulseTimestampsMs.clear()
            val startT = clock.elapsedRealtimeMs()
            controller.updateSignal(-85.0, isSignalLost = false) // interval = 1031 ms
            runCurrent()
            assertEquals(listOf(startT), pulseTimestampsMs)

            repeat(3) {
                advanceTimeBy(300L) // t + 300, t + 600, t + 900 (< 1031 ms)
                runCurrent()
                controller.updateSignal(-85.0, isSignalLost = false)
                runCurrent()
            }
            assertEquals("Must not double-pulse during 300 ms updates", listOf(startT), pulseTimestampsMs)

            advanceTimeBy(131L) // exact t + 1031 ms!
            runCurrent()
            assertEquals(listOf(startT, startT + 1031L), pulseTimestampsMs)

            // Immediate 0 ms halt on isSignalLost, null, NaN, stopAll (preserving settings!), and onStop
            val countBeforeDropout = pulseTimestampsMs.size
            controller.updateSignal(-50.0, isSignalLost = true)
            assertTrue(Shadows.shadowOf(vibrator).isCancelled)
            advanceTimeBy(5_000L)
            runCurrent()
            assertEquals(countBeforeDropout, pulseTimestampsMs.size)

            // Re-acquire, then test NaN
            controller.updateSignal(-45.0, isSignalLost = false)
            runCurrent()
            val countBeforeNan = pulseTimestampsMs.size
            controller.updateSignal(Double.NaN, isSignalLost = false)
            advanceTimeBy(5_000L)
            runCurrent()
            assertEquals(countBeforeNan, pulseTimestampsMs.size)

            // stopAll() halts pulses WITHOUT resetting user's hapticsEnabled toggle!
            controller.updateSignal(-45.0, isSignalLost = false)
            runCurrent()
            val countBeforeStopAll = pulseTimestampsMs.size
            controller.stopAll()
            assertTrue(controller.feedbackSettings.value.hapticsEnabled)
            advanceTimeBy(5_000L)
            runCurrent()
            assertEquals(countBeforeStopAll, pulseTimestampsMs.size)

            // Lifecycle bindToLifecycle ON_STOP halts immediately; ON_START resumes when signal updates
            val owner = SimpleTestLifecycleOwner()
            owner.moveTo(Lifecycle.State.STARTED)
            controller.bindToLifecycle(owner.lifecycle)
            controller.updateSignal(-40.0, isSignalLost = false)
            runCurrent()
            val countBeforeOnStop = pulseTimestampsMs.size

            owner.moveTo(Lifecycle.State.CREATED) // triggers ON_STOP
            advanceTimeBy(5_000L)
            runCurrent()
            assertEquals(countBeforeOnStop, pulseTimestampsMs.size)
        } finally {
            controller.close()
        }
    }

    private fun decodeLeShort(bytes: ByteArray, byteOffset: Int): Short {
        val lo = bytes[byteOffset].toInt() and 0xFF
        val hi = bytes[byteOffset + 1].toInt()
        return ((hi shl 8) or lo).toShort()
    }

    private fun countZeroCrossings(bytes: ByteArray): Int {
        var crossings = 0
        var prevSign = 0
        for (i in 0 until bytes.size step 2) {
            val s = decodeLeShort(bytes, i).toInt()
            val sign = when {
                s > 0 -> 1
                s < 0 -> -1
                else -> 0
            }
            if (sign != 0 && prevSign != 0 && sign != prevSign) {
                crossings++
            }
            if (sign != 0) prevSign = sign
        }
        return crossings
    }

    private class SimpleTestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }
}
