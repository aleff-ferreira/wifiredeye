package com.a55.wifiradar

import com.a55.wifiradar.core.model.EnvironmentHealthReport
import com.a55.wifiradar.core.model.ExportFormat
import com.a55.wifiradar.core.model.ExportPayload
import com.a55.wifiradar.core.model.PropagationEnvironment
import com.a55.wifiradar.core.model.ProximityFeedbackController
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.ProximityTelemetry
import com.a55.wifiradar.core.model.RadarSnapshot
import com.a55.wifiradar.core.model.RfAnalyticsEngine
import com.a55.wifiradar.core.model.ScanTriggerResult
import com.a55.wifiradar.core.model.ScannerReadinessState
import com.a55.wifiradar.core.model.SignalLockStatus
import com.a55.wifiradar.core.model.SpectrumAnalysis
import com.a55.wifiradar.core.model.UserPreferencesDataSource
import com.a55.wifiradar.core.model.WifiBand
import com.a55.wifiradar.core.model.WifiPlatformDataSource
import com.a55.wifiradar.core.model.WifiRadarRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * Production wiring of the Android platform layer, pure-JVM RF engine and local preferences into the
 * single [WifiRadarRepository] contract consumed by every feature screen (`M6`).
 *
 * The RF engine is stateful (RSSI timelines, Kalman filters, roam history), so every call into it is
 * serialized behind [engineLock]; there is exactly one snapshot pipeline shared by all screens.
 */
class DefaultWifiRadarRepository(
    private val platform: WifiPlatformDataSource,
    private val preferences: UserPreferencesDataSource,
    private val engine: RfAnalyticsEngine,
    override val feedbackController: ProximityFeedbackController,
    scope: CoroutineScope,
    private val epochClock: () -> Long = System::currentTimeMillis,
) : WifiRadarRepository {

    private val engineLock = Any()

    private val prefsFlow = combine(
        preferences.bssidNicknames,
        preferences.oneMeterCalibrationsDbm,
    ) { nicknames, calibrations -> nicknames to calibrations }

    override val radarSnapshot: StateFlow<RadarSnapshot> = combine(
        platform.rawScanBatches,
        platform.connectedWifi,
        platform.scanTelemetry,
        platform.radioCapabilities,
        prefsFlow,
    ) { batch, connected, telemetry, capabilities, prefs ->
        synchronized(engineLock) {
            engine.processScanSnapshot(
                rawBatch = batch,
                connectedWifi = connected,
                scanTelemetry = telemetry,
                radioCapabilities = capabilities,
                bssidNicknames = prefs.first,
                oneMeterCalibrationsDbm = prefs.second,
            )
        }
    }
        .catch { emit(RadarSnapshot()) }
        .stateIn(scope, SharingStarted.Eagerly, RadarSnapshot())

    override val readinessState: StateFlow<ScannerReadinessState> = platform.readinessState

    override val environmentHealth: StateFlow<EnvironmentHealthReport> = radarSnapshot
        .map { snapshot -> synchronized(engineLock) { engine.computeEnvironmentHealth(snapshot) } }
        .catch { emit(EnvironmentHealthReport()) }
        .stateIn(scope, SharingStarted.Eagerly, EnvironmentHealthReport())

    override fun observeProximity(
        targetKey: ProximityTargetKey,
        environment: PropagationEnvironment,
    ): Flow<ProximityTelemetry> = combine(
        radarSnapshot,
        preferences.oneMeterCalibrationsDbm,
    ) { snapshot, calibrations ->
        synchronized(engineLock) {
            engine.updateProximityTarget(
                targetKey = targetKey,
                snapshot = snapshot,
                environment = environment,
                oneMeterCalibrationsDbm = calibrations,
            )
        }
    }
        .onEach { telemetry ->
            feedbackController.updateSignal(
                smoothedRssiDbm = telemetry.smoothedRssiDbm,
                isSignalLost = telemetry.lockStatus == SignalLockStatus.SIGNAL_LOST,
            )
        }
        .onStart { platform.setHighFrequencyConnectedPolling(true) }
        .onCompletion {
            platform.setHighFrequencyConnectedPolling(false)
            feedbackController.stopAll()
        }

    override fun computeSpectrum(
        band: WifiBand,
        includeDfs: Boolean,
        excludeOwnSsid: Boolean,
    ): Flow<SpectrumAnalysis> = radarSnapshot
        .map { snapshot ->
            synchronized(engineLock) {
                engine.computeSpectrumAnalysis(
                    networks = snapshot.radios,
                    band = band,
                    includeDfs = includeDfs,
                    excludeConnectedSsid = excludeOwnSsid,
                    radioCapabilities = snapshot.radioCapabilities,
                )
            }
        }
        .distinctUntilChanged()

    override fun requestManualScan(burstMode: Boolean): ScanTriggerResult =
        platform.triggerActiveScan(burstMode)

    override fun resetProximityStats(targetKey: ProximityTargetKey?) {
        synchronized(engineLock) { engine.resetProximityTarget(targetKey) }
    }

    override fun refreshReadiness() = platform.refreshReadiness()

    override fun reportPermissionDenial(permanentlyDenied: Boolean) =
        platform.reportPermissionDenial(permanentlyDenied)

    override suspend fun setBssidNickname(bssid: String, nickname: String?) =
        preferences.setNickname(bssid, nickname)

    override suspend fun calibrateOneMeter(bssid: String, currentSmoothedDbm: Double) =
        preferences.setOneMeterCalibration(bssid, currentSmoothedDbm)

    override suspend fun clearOneMeterCalibration(bssid: String) =
        preferences.setOneMeterCalibration(bssid, null)

    override fun exportSnapshot(format: ExportFormat, redactMacs: Boolean): ExportPayload {
        val snapshot = radarSnapshot.value
        val health = environmentHealth.value
        val content = synchronized(engineLock) {
            when (format) {
                ExportFormat.JSON -> engine.exportDiagnosticJson(snapshot, health, redactMacs)
                ExportFormat.MARKDOWN -> engine.exportDiagnosticMarkdown(snapshot, health, redactMacs)
            }
        }
        val now = epochClock()
        return ExportPayload(
            format = format,
            fileName = "wifi-radar-$now.${format.fileExtension}",
            mimeType = format.mimeType,
            content = content,
            redactedMacs = redactMacs,
            generatedAtEpochMs = now,
            networkCount = snapshot.radios.size,
            sizeBytes = content.toByteArray(Charsets.UTF_8).size,
        )
    }
}
