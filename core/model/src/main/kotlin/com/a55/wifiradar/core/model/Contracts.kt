package com.a55.wifiradar.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Implemented in `:core:wifi-platform` (M3). Zero UI dependencies.
 */
interface WifiPlatformDataSource {
    val rawScanBatches: StateFlow<RawScanBatch?>
    val scanTelemetry: StateFlow<ScanTelemetry>
    val connectedWifi: StateFlow<ConnectedWifiObservation?>
    val readinessState: StateFlow<ScannerReadinessState>
    val radioCapabilities: StateFlow<DeviceRadioCapabilities>

    fun triggerActiveScan(burstMode: Boolean = false): ScanTriggerResult
    fun setHighFrequencyConnectedPolling(enabled: Boolean)
    fun refreshReadiness()
    fun reportPermissionDenial(permanentlyDenied: Boolean) {}
    suspend fun requestRttRange(bssid: String): RttMeasurement?
}

/**
 * Implemented in `:core:wifi-platform` (M3) for local offline nickname & 1m RSSI persistence (`F23`).
 */
interface UserPreferencesDataSource {
    val bssidNicknames: StateFlow<Map<String, String>>
    val oneMeterCalibrationsDbm: StateFlow<Map<String, Double>>

    suspend fun setNickname(bssid: String, nickname: String?)
    suspend fun setOneMeterCalibration(bssid: String, rssiAtOneMeterDbm: Double?)
}

/**
 * Implemented in `:core:wifi-platform` (M3) for 3-tier LRA haptic & Hanning-sine Geiger pulses (`F22`).
 * Default toggles MUST be OFF (`GeigerFeedbackSettings(false, false)`).
 */
interface ProximityFeedbackController {
    val feedbackSettings: StateFlow<GeigerFeedbackSettings>
    val hasHardwareVibrator: Boolean
        get() = true

    fun setHapticsEnabled(enabled: Boolean)
    fun setAudioEnabled(enabled: Boolean)
    fun updateSignal(smoothedRssiDbm: Double?, isSignalLost: Boolean)
    fun stopAll()
}

/**
 * Implemented in `:core:rf-engine` (M2) as 100% pure, deterministic Kotlin JVM logic.
 *
 * Contract Notes:
 * - Never call `System.currentTimeMillis()` or Android `SystemClock` inside `RfAnalyticsEngine`;
 *   use the caller-provided `currentElapsedMs` / `currentEpochMs`.
 * - Callers of `computeSpectrumAnalysis` should pass the full `snapshot.radios` list so when
 *   `band == WifiBand.BAND_6_GHZ`, `computeSpectrumAnalysis` filters 6 GHz curves internally while
 *   also harvesting `Colocated6GhzNeighbor` (`IE 201` RNR) records advertised on 2.4 / 5 GHz beacons.
 */
interface RfAnalyticsEngine {
    fun processScanSnapshot(
        rawBatch: RawScanBatch?,
        connectedWifi: ConnectedWifiObservation? = null,
        scanTelemetry: ScanTelemetry = ScanTelemetry(),
        radioCapabilities: DeviceRadioCapabilities = DeviceRadioCapabilities(),
        bssidNicknames: Map<String, String> = emptyMap(),
        oneMeterCalibrationsDbm: Map<String, Double> = emptyMap(),
        currentElapsedMs: Long = rawBatch?.receivedElapsedMs ?: 0L,
        currentEpochMs: Long = rawBatch?.receivedEpochMs ?: 0L,
    ): RadarSnapshot

    fun updateProximityTarget(
        targetKey: ProximityTargetKey,
        snapshot: RadarSnapshot,
        environment: PropagationEnvironment = PropagationEnvironment.HOME_OFFICE,
        rttMeasurement: RttMeasurement? = null,
        oneMeterCalibrationsDbm: Map<String, Double> = emptyMap(),
        currentElapsedMs: Long = snapshot.timestampElapsedMs,
    ): ProximityTelemetry

    fun resetProximityTarget(targetKey: ProximityTargetKey? = null)

    fun computeSpectrumAnalysis(
        networks: List<BssidRadio>,
        band: WifiBand,
        includeDfs: Boolean = false,
        excludeConnectedSsid: Boolean = false,
        radioCapabilities: DeviceRadioCapabilities = DeviceRadioCapabilities(),
    ): SpectrumAnalysis

    fun computeEnvironmentHealth(snapshot: RadarSnapshot): EnvironmentHealthReport

    fun exportDiagnosticJson(
        snapshot: RadarSnapshot,
        health: EnvironmentHealthReport,
        redactMacs: Boolean = false,
        proximityHistory: List<ProximityTelemetry> = emptyList(),
    ): String

    fun exportDiagnosticMarkdown(
        snapshot: RadarSnapshot,
        health: EnvironmentHealthReport,
        redactMacs: Boolean = false,
        proximityHistory: List<ProximityTelemetry> = emptyList(),
    ): String
}

/**
 * Consumed by `:feature:networks`, `:feature:proximity`, `:feature:spectrum`, `:feature:insights`
 * and wired in `:app` (M6). Zero Android `WifiManager` dependency!
 */
interface WifiRadarRepository {
    val radarSnapshot: StateFlow<RadarSnapshot>
    val readinessState: StateFlow<ScannerReadinessState>
    val environmentHealth: StateFlow<EnvironmentHealthReport>
    val feedbackController: ProximityFeedbackController

    fun observeProximity(
        targetKey: ProximityTargetKey,
        environment: PropagationEnvironment = PropagationEnvironment.HOME_OFFICE,
    ): Flow<ProximityTelemetry>

    fun computeSpectrum(
        band: WifiBand,
        includeDfs: Boolean = false,
        excludeOwnSsid: Boolean = false,
    ): Flow<SpectrumAnalysis>

    fun requestManualScan(burstMode: Boolean = false): ScanTriggerResult

    fun resetProximityStats(targetKey: ProximityTargetKey? = null) {}

    fun refreshReadiness() {}

    fun reportPermissionDenial(permanentlyDenied: Boolean) {}

    suspend fun setBssidNickname(bssid: String, nickname: String?)

    suspend fun calibrateOneMeter(bssid: String, currentSmoothedDbm: Double)

    suspend fun clearOneMeterCalibration(bssid: String)

    fun exportSnapshot(format: ExportFormat, redactMacs: Boolean = false): ExportPayload
}
