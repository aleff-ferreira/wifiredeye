package com.a55.wifiradar.core.wifiplatform

import android.os.VibrationEffect
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Pure signal-to-pulse math and click-free `18 ms` Hanning-windowed 16-bit Little-Endian PCM
 * sine burst synthesizer for the Proximity Geiger feedback engine (`F22`).
 */
internal object GeigerSignalMath {
    const val MIN_RSSI_DBM = -92.0
    const val MAX_RSSI_DBM = -28.0
    const val MIN_INTERVAL_MS = 85L
    const val MAX_INTERVAL_MS = 1400L
    const val MIN_TONE_HZ = 320.0
    const val MAX_TONE_HZ = 1400.0
    const val TONE_SPAN_HZ = MAX_TONE_HZ - MIN_TONE_HZ // 1080.0 Hz
    const val TONE_EXPONENT = 1.35

    const val SAMPLE_RATE_HZ = 24_000
    const val BURST_DURATION_MS = 18
    const val BURST_SAMPLES = (SAMPLE_RATE_HZ * BURST_DURATION_MS) / 1000 // 432 samples
    const val BURST_PCM_BYTES = BURST_SAMPLES * 2 // 864 bytes (16-bit mono LE)

    private val HANNING_WINDOW = DoubleArray(BURST_SAMPLES) { n ->
        0.5 * (1.0 - cos((2.0 * PI * n.toDouble()) / (BURST_SAMPLES - 1).toDouble()))
    }

    fun computeNormalizedProximity(smoothedRssiDbm: Double): Double {
        if (!smoothedRssiDbm.isFinite()) return 0.0
        return ((smoothedRssiDbm - MIN_RSSI_DBM) / (MAX_RSSI_DBM - MIN_RSSI_DBM))
            .coerceIn(0.0, 1.0)
    }

    fun computePulseIntervalMs(u: Double): Long {
        val clamped = if (u.isFinite()) u.coerceIn(0.0, 1.0) else 0.0
        val ratio = MIN_INTERVAL_MS.toDouble() / MAX_INTERVAL_MS.toDouble()
        return (MAX_INTERVAL_MS.toDouble() * ratio.pow(clamped))
            .roundToLong()
            .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    }

    fun computeToneFrequencyHz(u: Double): Double {
        val clamped = if (u.isFinite()) u.coerceIn(0.0, 1.0) else 0.0
        return (MIN_TONE_HZ + TONE_SPAN_HZ * clamped.pow(TONE_EXPONENT))
            .coerceIn(MIN_TONE_HZ, MAX_TONE_HZ)
    }

    fun computeTier1PrimitiveScale(u: Double): Float {
        val clamped = if (u.isFinite()) u.coerceIn(0.0, 1.0) else 0.0
        return (0.20f + 0.80f * clamped.toFloat().pow(1.2f)).coerceIn(0.0f, 1.0f)
    }

    fun computeTier2OneShotDurationMs(u: Double): Long {
        val clamped = if (u.isFinite()) u.coerceIn(0.0, 1.0) else 0.0
        return (10.0 + 14.0 * clamped).roundToLong().coerceIn(10L, 24L)
    }

    fun computeTier2OneShotAmplitude(u: Double): Int {
        val clamped = if (u.isFinite()) u.coerceIn(0.0, 1.0) else 0.0
        return (45.0 + 210.0 * clamped.pow(1.2)).roundToInt().coerceIn(1, 255)
    }

    fun computeTier3PredefinedEffectId(u: Double): Int {
        val clamped = if (u.isFinite()) u.coerceIn(0.0, 1.0) else 0.0
        return if (clamped > 0.75) {
            VibrationEffect.EFFECT_CLICK
        } else {
            VibrationEffect.EFFECT_TICK
        }
    }

    /**
     * Populates [targetPcmLeBytes] (length >= [BURST_PCM_BYTES] = `864`) with an `18 ms`
     * Hanning-windowed 16-bit Little-Endian mono PCM sine burst at [frequencyHz].
     *
     * Endpoints `sample[0]` and `sample[BURST_SAMPLES - 1]` are guaranteed to be exact `0`
     * to eliminate click/pop DC discontinuities.
     */
    fun synthesizeHanningSineBurstPcm16Le(
        frequencyHz: Double,
        u: Double,
        targetPcmLeBytes: ByteArray = ByteArray(BURST_PCM_BYTES),
    ): ByteArray {
        require(targetPcmLeBytes.size >= BURST_PCM_BYTES) {
            "targetPcmLeBytes must be at least $BURST_PCM_BYTES bytes"
        }
        val clampedU = if (u.isFinite()) u.coerceIn(0.0, 1.0) else 0.0
        val safeFreqHz = if (frequencyHz.isFinite()) {
            frequencyHz.coerceIn(MIN_TONE_HZ, MAX_TONE_HZ)
        } else {
            MIN_TONE_HZ
        }
        val gain = 0.35 + 0.50 * clampedU
        val angularStep = (2.0 * PI * safeFreqHz) / SAMPLE_RATE_HZ.toDouble()

        // Exact 0 endpoints at n = 0 and n = BURST_SAMPLES - 1
        targetPcmLeBytes[0] = 0
        targetPcmLeBytes[1] = 0
        val lastByteOffset = (BURST_SAMPLES - 1) * 2
        targetPcmLeBytes[lastByteOffset] = 0
        targetPcmLeBytes[lastByteOffset + 1] = 0

        for (n in 1 until (BURST_SAMPLES - 1)) {
            val sampleFloat = 32767.0 * gain * HANNING_WINDOW[n] * sin(angularStep * n.toDouble())
            val pcm16 = sampleFloat.roundToInt().coerceIn(-32767, 32767)
            val byteIdx = n * 2
            targetPcmLeBytes[byteIdx] = (pcm16 and 0xFF).toByte()
            targetPcmLeBytes[byteIdx + 1] = ((pcm16 shr 8) and 0xFF).toByte()
        }
        return targetPcmLeBytes
    }
}
