package com.a55.wifiradar.core.rfengine

import com.a55.wifiradar.core.model.DistanceEstimate
import com.a55.wifiradar.core.model.DistanceMethod
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.PropagationEnvironment
import com.a55.wifiradar.core.model.RttMeasurement
import com.a55.wifiradar.core.model.SemanticFormatters
import com.a55.wifiradar.core.model.WifiBand
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Handset-calibrated Free-Space + Log-Distance indoor range estimator with Beacon `TPC` (`IE 35`),
 * `-8.0 dBi` handheld body-loss offset, `1m` user calibration override, and automatic promotion to
 * IEEE `802.11mc` / `802.11az` Wi-Fi RTT Time-of-Flight ranging (`F13`).
 */
object DistanceEstimator {

    const val DEFAULT_TX_POWER_24_5_GHZ_DBM: Double = 20.0
    const val DEFAULT_TX_POWER_6_GHZ_LPI_DBM: Double = 18.0
    const val HANDSET_ANTENNA_BODY_OFFSET_DBI: Double = -8.0
    const val SHADOWING_SIGMA_DB: Double = 3.5
    private const val RTT_FRESHNESS_WINDOW_MS: Long = 10_000L

    fun estimate(
        smoothedRssiDbm: Double,
        frequencyMhz: Int,
        band: WifiBand,
        tpcApTxPowerDbm: Int? = null,
        environment: PropagationEnvironment = PropagationEnvironment.HOME_OFFICE,
        userCalibrated1mDbm: Double? = null,
        resolvedBssid: String? = null,
        rttMeasurement: RttMeasurement? = null,
        currentElapsedMs: Long = 0L,
    ): DistanceEstimate {
        if (rttMeasurement != null && resolvedBssid != null) {
            val rttBssid = MacAddressUtils.normalizeBssid(rttMeasurement.bssid)
            val ageMs = currentElapsedMs - rttMeasurement.timestampElapsedMs
            if (rttBssid == resolvedBssid && ageMs in -1_000L..RTT_FRESHNESS_WINDOW_MS) {
                return buildRttEstimate(
                    rtt = rttMeasurement,
                    frequencyMhz = frequencyMhz,
                    band = band,
                    tpcApTxPowerDbm = tpcApTxPowerDbm,
                    environment = environment,
                    userCalibrated1mDbm = userCalibrated1mDbm,
                )
            }
        }

        return estimatePathLoss(
            smoothedRssiDbm = smoothedRssiDbm,
            frequencyMhz = frequencyMhz,
            band = band,
            tpcApTxPowerDbm = tpcApTxPowerDbm,
            environment = environment,
            userCalibrated1mDbm = userCalibrated1mDbm,
        )
    }

    fun estimatePathLoss(
        smoothedRssiDbm: Double,
        frequencyMhz: Int,
        band: WifiBand,
        tpcApTxPowerDbm: Int? = null,
        environment: PropagationEnvironment = PropagationEnvironment.HOME_OFFICE,
        userCalibrated1mDbm: Double? = null,
    ): DistanceEstimate {
        val ref1mDbm = computeReferenceRssiAt1mDbm(
            frequencyMhz = frequencyMhz,
            band = band,
            tpcApTxPowerDbm = tpcApTxPowerDbm,
            userCalibrated1mDbm = userCalibrated1mDbm,
        )
        val method = if (userCalibrated1mDbm != null && userCalibrated1mDbm.isFinite()) {
            DistanceMethod.USER_CALIBRATED_PATH_LOSS
        } else {
            DistanceMethod.PATH_LOSS_ESTIMATE
        }

        val xClamped = smoothedRssiDbm.finiteCoerceIn(-100.0, -20.0, -70.0)
        val n = environment.pathLossExponent.finiteCoerceIn(1.5, 5.0, 2.7)

        val dRaw = 10.0.pow((ref1mDbm - xClamped) / (10.0 * n)).finiteOr(5.0)
        val dMinRaw = (dRaw * 10.0.pow(-SHADOWING_SIGMA_DB / (10.0 * n))).finiteOr(dRaw * 0.74)
        val dMaxRaw = (dRaw * 10.0.pow(+SHADOWING_SIGMA_DB / (10.0 * n))).finiteOr(dRaw * 1.35)

        val clampedNear = dRaw < 0.5
        val clampedFar = dRaw > 50.0
        val meters = dRaw.finiteCoerceIn(0.2, 60.0, 5.0)
        val rangeMin = dMinRaw.finiteCoerceIn(0.3, 55.0, 0.3)
        val rangeMax = dMaxRaw.finiteCoerceIn(max(0.5, rangeMin), 60.0, max(0.5, rangeMin))

        val formatted = SemanticFormatters.formatDistanceRange(
            meters = meters,
            rangeMinMeters = rangeMin,
            rangeMaxMeters = rangeMax,
            stdDevMeters = null,
            method = method,
        )

        return DistanceEstimate(
            meters = meters,
            rangeMinMeters = rangeMin,
            rangeMaxMeters = rangeMax,
            stdDevMeters = null,
            method = method,
            referenceRssiAt1mDbm = ref1mDbm,
            environment = environment,
            clampedNear = clampedNear,
            clampedFar = clampedFar,
            plainEnglishFormatted = formatted,
        )
    }

    fun computeReferenceRssiAt1mDbm(
        frequencyMhz: Int,
        band: WifiBand,
        tpcApTxPowerDbm: Int? = null,
        userCalibrated1mDbm: Double? = null,
    ): Double {
        if (userCalibrated1mDbm != null && userCalibrated1mDbm.isFinite()) {
            return userCalibrated1mDbm.coerceIn(-85.0, -15.0)
        }
        val fEff = if (frequencyMhz >= 2400) frequencyMhz.toDouble() else 2437.0
        val fspl1m = 20.0 * log10(fEff) - 27.55
        val txPowerDbm = when {
            tpcApTxPowerDbm != null && tpcApTxPowerDbm in -10..36 -> tpcApTxPowerDbm.toDouble()
            band == WifiBand.BAND_6_GHZ || fEff >= 5925.0 -> DEFAULT_TX_POWER_6_GHZ_LPI_DBM
            else -> DEFAULT_TX_POWER_24_5_GHZ_DBM
        }
        return ((txPowerDbm + HANDSET_ANTENNA_BODY_OFFSET_DBI) - fspl1m).finiteOr(-48.2)
    }

    private fun buildRttEstimate(
        rtt: RttMeasurement,
        frequencyMhz: Int,
        band: WifiBand,
        tpcApTxPowerDbm: Int?,
        environment: PropagationEnvironment,
        userCalibrated1mDbm: Double?,
    ): DistanceEstimate {
        val rawMetersFromMm = rtt.rawDistanceMm.toDouble() / 1000.0
        val candidateMeters = if (rtt.distanceMeters.isFinite()) rtt.distanceMeters else rawMetersFromMm
        val rawMeters = max(0.0, candidateMeters).finiteCoerceIn(0.0, 200.0, 0.0)
        val clampedNear = rawMeters < 0.3 || rtt.rawDistanceMm < 300
        val clampedFar = rawMeters > 50.0
        val sd = rtt.distanceStdDevMeters.finiteOr(0.3).coerceIn(0.1, 25.0)
        val minM = (rawMeters - sd).coerceAtLeast(0.05)
        val maxM = (rawMeters + sd).coerceAtLeast(minM)
        val method = if (rtt.is80211azNtb) {
            DistanceMethod.RTT_80211AZ
        } else {
            DistanceMethod.RTT_80211MC
        }
        val ref1m = computeReferenceRssiAt1mDbm(
            frequencyMhz = frequencyMhz,
            band = band,
            tpcApTxPowerDbm = tpcApTxPowerDbm,
            userCalibrated1mDbm = userCalibrated1mDbm,
        )
        val formatted = SemanticFormatters.formatDistanceRange(
            meters = rawMeters,
            rangeMinMeters = minM,
            rangeMaxMeters = maxM,
            stdDevMeters = sd,
            method = method,
        )
        return DistanceEstimate(
            meters = rawMeters,
            rangeMinMeters = minM,
            rangeMaxMeters = maxM,
            stdDevMeters = sd,
            method = method,
            referenceRssiAt1mDbm = ref1m,
            environment = environment,
            clampedNear = clampedNear,
            clampedFar = clampedFar,
            plainEnglishFormatted = formatted,
        )
    }
}
