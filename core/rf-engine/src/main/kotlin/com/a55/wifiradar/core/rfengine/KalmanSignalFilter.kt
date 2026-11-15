package com.a55.wifiradar.core.rfengine

import kotlin.math.abs
import kotlin.math.exp

/**
 * 1D motion-adaptive Kalman filter paired with a time-aware Exponential Moving Average (`F12`).
 *
 * - Calms stationary indoor Rayleigh/Rician multipath jitter.
 * - Boosts prediction covariance immediately when `|innovation| > 6.0 dB` during walking motion.
 * - Cleanly resets filter covariance after `> 15.0 s` telemetry gaps or target switches.
 */
class KalmanSignalFilter {

    companion object {
        const val MEASUREMENT_NOISE_R_DB2: Double = 6.0
        const val PROCESS_NOISE_RATE_DB2_PER_SEC: Double = 1.8
        const val RESET_GAP_SECONDS: Double = 15.0
        const val INNOVATION_BOOST_THRESHOLD_DB: Double = 6.0
        const val INNOVATION_BOOST_FACTOR: Double = 0.35
        const val OPPOSITE_BOUNCE_BOOST_FACTOR: Double = 0.15
        const val EMA_TAU_SECONDS: Double = 1.6

        fun sanitizeRssiDbm(rawRssiDbm: Int): Double? {
            if (rawRssiDbm <= -126 || rawRssiDbm >= 0) return null
            return rawRssiDbm.coerceIn(-110, -10).toDouble()
        }

        fun clampExtremeRssiDbm(rawRssiDbm: Int): Double =
            rawRssiDbm.coerceIn(-110, -10).toDouble()
    }

    data class FilterOutput(
        val smoothedDbm: Double,
        val emaDbm: Double,
        val innovationDb: Double,
        val varianceDb2: Double,
    )

    var initialized: Boolean = false
        private set

    var xHatDbm: Double = -70.0
        private set

    var varianceDb2: Double = MEASUREMENT_NOISE_R_DB2
        private set

    var emaDbm: Double = -70.0
        private set

    var lastInnovationDb: Double = 0.0
        private set

    var lastSampleElapsedMs: Long = Long.MIN_VALUE
        private set

    fun reset() {
        initialized = false
        varianceDb2 = MEASUREMENT_NOISE_R_DB2
        lastInnovationDb = 0.0
        lastSampleElapsedMs = Long.MIN_VALUE
    }

    fun update(rawRssiDbm: Int, timestampElapsedMs: Long): FilterOutput {
        val z = clampExtremeRssiDbm(rawRssiDbm)
        if (!initialized) {
            initialized = true
            xHatDbm = z
            varianceDb2 = MEASUREMENT_NOISE_R_DB2
            emaDbm = z
            lastInnovationDb = 0.0
            lastSampleElapsedMs = timestampElapsedMs
            return snapshotOutput()
        }

        val dtSec = (timestampElapsedMs - lastSampleElapsedMs).toDouble() / 1000.0
        if (!dtSec.isFinite() || dtSec > RESET_GAP_SECONDS || dtSec < 0.0) {
            xHatDbm = z
            varianceDb2 = MEASUREMENT_NOISE_R_DB2
            emaDbm = z
            lastInnovationDb = 0.0
            lastSampleElapsedMs = timestampElapsedMs
            return snapshotOutput()
        }

        val effDt = dtSec.coerceIn(0.05, 5.0)
        var pPred = varianceDb2 + PROCESS_NOISE_RATE_DB2_PER_SEC * effDt
        val nu = z - xHatDbm

        if (abs(nu) > INNOVATION_BOOST_THRESHOLD_DB) {
            val isConsistentOrLargeStep = (lastInnovationDb * nu >= 0.0) || abs(nu) >= 8.0
            val factor = if (isConsistentOrLargeStep) {
                INNOVATION_BOOST_FACTOR
            } else {
                OPPOSITE_BOUNCE_BOOST_FACTOR
            }
            pPred += factor * nu * nu
        }

        val gain = (pPred / (pPred + MEASUREMENT_NOISE_R_DB2)).finiteCoerceIn(0.0, 1.0, 0.5)
        xHatDbm = (xHatDbm + gain * nu).finiteCoerceIn(-115.0, -5.0, z)
        varianceDb2 = ((1.0 - gain) * pPred).finiteCoerceIn(0.05, 200.0, MEASUREMENT_NOISE_R_DB2)

        val alpha = (1.0 - exp(-effDt / EMA_TAU_SECONDS)).finiteCoerceIn(0.01, 1.0, 0.5)
        emaDbm = (emaDbm + alpha * (z - emaDbm)).finiteCoerceIn(-115.0, -5.0, z)

        lastInnovationDb = nu.finiteOr(0.0)
        lastSampleElapsedMs = timestampElapsedMs
        return snapshotOutput()
    }

    private fun snapshotOutput(): FilterOutput = FilterOutput(
        smoothedDbm = xHatDbm.finiteOr(-70.0),
        emaDbm = emaDbm.finiteOr(-70.0),
        innovationDb = lastInnovationDb.finiteOr(0.0),
        varianceDb2 = varianceDb2.finiteOr(MEASUREMENT_NOISE_R_DB2),
    )
}
