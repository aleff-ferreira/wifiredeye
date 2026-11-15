package com.a55.wifiradar

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.a55.wifiradar.core.designsystem.component.ScanStatusTopPill
import com.a55.wifiradar.core.designsystem.layout.RadarScaffold
import com.a55.wifiradar.core.designsystem.theme.WifiRadarTheme
import com.a55.wifiradar.core.model.InsightActionTarget
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.RadarDestination
import com.a55.wifiradar.core.model.WifiBand
import com.a55.wifiradar.core.model.WifiRadarRepository
import com.a55.wifiradar.feature.insights.InsightsRoute
import com.a55.wifiradar.feature.networks.NetworksRoute
import com.a55.wifiradar.feature.proximity.ProximityRoute
import com.a55.wifiradar.feature.spectrum.SpectrumRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        val app = application as WifiRadarApplication
        // Scanning, haptics and audio only run while the screen is visible (battery + OS throttle safe).
        app.platform.bindToLifecycle(lifecycle)
        app.feedback.bindToLifecycle(lifecycle)

        val repository = app.repository
        setContent {
            WifiRadarTheme {
                WifiRadarApp(repository = repository)
            }
        }
    }
}

@Composable
fun WifiRadarApp(repository: WifiRadarRepository) {
    var selectedTabName by rememberSaveable { mutableStateOf(RadarDestination.NETWORKS.name) }
    val selectedTab = RadarDestination.entries.firstOrNull { it.name == selectedTabName }
        ?: RadarDestination.NETWORKS

    // Cross-screen hand-offs (tap a router -> jump to Proximity; tap an insight -> jump to Spectrum).
    var proximityTargetEncoded by rememberSaveable { mutableStateOf<String?>(null) }
    var spectrumBandName by rememberSaveable { mutableStateOf<String?>(null) }
    var spectrumChannel by rememberSaveable { mutableStateOf<Int?>(null) }
    var spectrumNonce by rememberSaveable { mutableLongStateOf(0L) }

    fun openProximity(key: ProximityTargetKey) {
        proximityTargetEncoded = key.toSavedStateString()
        selectedTabName = RadarDestination.PROXIMITY.name
    }

    fun openSpectrum(band: WifiBand, channel: Int?) {
        spectrumBandName = band.name
        spectrumChannel = channel
        spectrumNonce += 1
        selectedTabName = RadarDestination.SPECTRUM.name
    }

    val snapshot by repository.radarSnapshot.collectAsState()
    val readiness by repository.readinessState.collectAsState()

    RadarScaffold(
        selectedTab = selectedTab,
        onSelectTab = { selectedTabName = it.name },
        topStatusSlot = {
            ScanStatusTopPill(
                networkCount = snapshot.groupedNetworks.size.takeIf { it > 0 } ?: snapshot.radios.size,
                telemetry = snapshot.scanTelemetry,
                readinessState = readiness,
            )
        },
    ) { innerPadding ->
        when (selectedTab) {
            RadarDestination.NETWORKS -> NetworksRoute(
                repository = repository,
                onOpenProximity = ::openProximity,
                modifier = Modifier,
                contentPadding = innerPadding,
            )

            RadarDestination.PROXIMITY -> ProximityRoute(
                repository = repository,
                contentPadding = innerPadding,
                externalTargetKey = ProximityTargetKey.fromSavedStateString(proximityTargetEncoded),
                onExternalTargetConsumed = { proximityTargetEncoded = null },
            )

            RadarDestination.SPECTRUM -> SpectrumRoute(
                repository = repository,
                contentPadding = innerPadding,
                externalBandRequest = spectrumBandName?.let { name ->
                    WifiBand.entries.firstOrNull { it.name == name }
                },
                externalHighlightChannel = spectrumChannel,
                externalRequestNonce = spectrumNonce,
                onExternalRequestConsumed = {
                    spectrumBandName = null
                    spectrumChannel = null
                },
                onOpenProximity = ::openProximity,
                onOpenNetworkDetail = { selectedTabName = RadarDestination.NETWORKS.name },
            )

            RadarDestination.INSIGHTS -> InsightsRoute(
                repository = repository,
                contentPadding = PaddingValues(
                    start = 14.dp,
                    end = 14.dp,
                    top = innerPadding.calculateTopPadding() + 10.dp,
                    bottom = innerPadding.calculateBottomPadding() + 10.dp,
                ),
                onNavigateToActionTarget = { target ->
                    when (target) {
                        is InsightActionTarget.OpenNetworkDetail ->
                            selectedTabName = RadarDestination.NETWORKS.name
                        is InsightActionTarget.LaunchProximity -> openProximity(target.targetKey)
                        is InsightActionTarget.OpenSpectrumBand ->
                            openSpectrum(target.band, target.highlightChannel)
                        else -> Unit
                    }
                },
            )
        }
    }
}
