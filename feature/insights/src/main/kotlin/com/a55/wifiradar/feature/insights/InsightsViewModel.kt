package com.a55.wifiradar.feature.insights

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.a55.wifiradar.core.model.ExportFormat
import com.a55.wifiradar.core.model.ExportPayload
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.RssiHistoryWindow
import com.a55.wifiradar.core.model.SavedStateKeys
import com.a55.wifiradar.core.model.ScanTriggerResult
import com.a55.wifiradar.core.model.WifiRadarRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class InsightsViewModel(
    private val repository: WifiRadarRepository,
    private val savedStateHandle: SavedStateHandle,
    private val exportDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private data class SavedControlsSlice(
        val historyWindow: RssiHistoryWindow,
        val redactMacs: Boolean,
        val exportFormat: ExportFormat,
        val isPreviewExpanded: Boolean,
        val highlightedBssid: String?,
        val showAllSecurity: Boolean,
        val showAllRoams: Boolean,
    )

    private data class EphemeralFeedbackSlice(
        val copyConfirmation: String?,
        val statusBanner: String?,
    )

    private val historyWindowFlow: StateFlow<RssiHistoryWindow> = savedStateHandle
        .getStateFlow(
            key = SavedStateKeys.INSIGHTS_HISTORY_WINDOW,
            initialValue = RssiHistoryWindow.FIVE_MINUTES.name,
        )
        .map(::parseHistoryWindow)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = parseHistoryWindow(
                savedStateHandle.get<String>(SavedStateKeys.INSIGHTS_HISTORY_WINDOW)
            ),
        )

    private val redactMacsFlow: StateFlow<Boolean> = savedStateHandle.getStateFlow(
        key = SavedStateKeys.INSIGHTS_REDACT_MACS,
        initialValue = false,
    )

    private val exportFormatFlow: StateFlow<ExportFormat> = savedStateHandle
        .getStateFlow(
            key = KEY_EXPORT_FORMAT,
            initialValue = ExportFormat.MARKDOWN.name,
        )
        .map(::parseExportFormat)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = parseExportFormat(savedStateHandle.get<String>(KEY_EXPORT_FORMAT)),
        )

    private val isPreviewExpandedFlow: StateFlow<Boolean> = savedStateHandle.getStateFlow(
        key = KEY_PREVIEW_EXPANDED,
        initialValue = true,
    )

    private val highlightedTimelineBssidFlow: StateFlow<String?> = savedStateHandle.getStateFlow(
        key = KEY_HIGHLIGHTED_TIMELINE_BSSID,
        initialValue = null,
    )

    private val showAllSecurityRowsFlow: StateFlow<Boolean> = savedStateHandle.getStateFlow(
        key = KEY_SHOW_ALL_SECURITY,
        initialValue = false,
    )

    private val showAllRoamEventsFlow: StateFlow<Boolean> = savedStateHandle.getStateFlow(
        key = KEY_SHOW_ALL_ROAMS,
        initialValue = false,
    )

    private val copyConfirmationFlow = MutableStateFlow<String?>(null)
    private val statusBannerFlow = MutableStateFlow<String?>(null)

    private val controlsPartAFlow = combine(
        historyWindowFlow,
        redactMacsFlow,
        exportFormatFlow,
        isPreviewExpandedFlow,
    ) { window, redact, format, expanded ->
        SavedControlsSlice(
            historyWindow = window,
            redactMacs = redact,
            exportFormat = format,
            isPreviewExpanded = expanded,
            highlightedBssid = null,
            showAllSecurity = false,
            showAllRoams = false,
        )
    }

    private val savedControlsFlow = combine(
        controlsPartAFlow,
        highlightedTimelineBssidFlow,
        showAllSecurityRowsFlow,
        showAllRoamEventsFlow,
    ) { base, highlighted, allSec, allRoams ->
        base.copy(
            highlightedBssid = highlighted,
            showAllSecurity = allSec,
            showAllRoams = allRoams,
        )
    }.distinctUntilChanged()

    private val livePreviewPayloadFlow = combine(
        repository.radarSnapshot,
        repository.environmentHealth,
        exportFormatFlow,
        redactMacsFlow,
        isPreviewExpandedFlow,
    ) { _, _, format, redactMacs, expanded ->
        if (!expanded) {
            null
        } else {
            runCatching {
                repository.exportSnapshot(format = format, redactMacs = redactMacs)
            }.getOrNull()
        }
    }.flowOn(exportDispatcher)

    private val feedbackSliceFlow = combine(
        copyConfirmationFlow,
        statusBannerFlow,
    ) { copyConfirmation, statusBanner ->
        EphemeralFeedbackSlice(
            copyConfirmation = copyConfirmation,
            statusBanner = statusBanner,
        )
    }

    private val domainTripleFlow = combine(
        repository.radarSnapshot,
        repository.environmentHealth,
        repository.readinessState,
    ) { snapshot, health, readiness ->
        Triple(snapshot, health, readiness)
    }

    val uiState: StateFlow<InsightsUiState> = combine(
        domainTripleFlow,
        savedControlsFlow,
        livePreviewPayloadFlow,
        feedbackSliceFlow,
    ) { (snapshot, health, readiness), controls, previewPayload, feedback ->
        InsightsStateMapper.mapToUiState(
            snapshot = snapshot,
            health = health,
            readinessState = readiness,
            historyWindow = controls.historyWindow,
            highlightedTimelineBssid = controls.highlightedBssid,
            redactMacs = controls.redactMacs,
            exportFormat = controls.exportFormat,
            isExportPreviewExpanded = controls.isPreviewExpanded,
            showAllSecurityRows = controls.showAllSecurity,
            showAllRoamEvents = controls.showAllRoams,
            exportPayload = previewPayload,
            copyConfirmationMessage = feedback.copyConfirmation,
            statusBannerMessage = feedback.statusBanner,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = buildInitialUiState(),
    )

    private fun buildInitialUiState(): InsightsUiState {
        val window = parseHistoryWindow(savedStateHandle.get<String>(SavedStateKeys.INSIGHTS_HISTORY_WINDOW))
        val redact = savedStateHandle.get<Boolean>(SavedStateKeys.INSIGHTS_REDACT_MACS) ?: false
        val format = parseExportFormat(savedStateHandle.get<String>(KEY_EXPORT_FORMAT))
        val expanded = savedStateHandle.get<Boolean>(KEY_PREVIEW_EXPANDED) ?: true
        val highlighted = savedStateHandle.get<String?>(KEY_HIGHLIGHTED_TIMELINE_BSSID)
        val showAllSec = savedStateHandle.get<Boolean>(KEY_SHOW_ALL_SECURITY) ?: false
        val showAllRoams = savedStateHandle.get<Boolean>(KEY_SHOW_ALL_ROAMS) ?: false
        val initialPayload = if (expanded) {
            runCatching { repository.exportSnapshot(format = format, redactMacs = redact) }.getOrNull()
        } else {
            null
        }
        return InsightsStateMapper.mapToUiState(
            snapshot = repository.radarSnapshot.value,
            health = repository.environmentHealth.value,
            readinessState = repository.readinessState.value,
            historyWindow = window,
            highlightedTimelineBssid = highlighted,
            redactMacs = redact,
            exportFormat = format,
            isExportPreviewExpanded = expanded,
            showAllSecurityRows = showAllSec,
            showAllRoamEvents = showAllRoams,
            exportPayload = initialPayload,
            copyConfirmationMessage = null,
            statusBannerMessage = null,
        )
    }

    fun selectHistoryWindow(window: RssiHistoryWindow) {
        savedStateHandle[SavedStateKeys.INSIGHTS_HISTORY_WINDOW] = window.name
    }

    fun toggleHighlightTimelineBssid(bssid: String) {
        val norm = MacAddressUtils.normalizeBssid(bssid).ifEmpty { return }
        val current = savedStateHandle.get<String?>(KEY_HIGHLIGHTED_TIMELINE_BSSID)
            ?.let(MacAddressUtils::normalizeBssid)
        savedStateHandle[KEY_HIGHLIGHTED_TIMELINE_BSSID] = if (current == norm) null else norm
    }

    fun setRedactMacs(redact: Boolean) {
        savedStateHandle[SavedStateKeys.INSIGHTS_REDACT_MACS] = redact
        copyConfirmationFlow.value = null
    }

    fun selectExportFormat(format: ExportFormat) {
        savedStateHandle[KEY_EXPORT_FORMAT] = format.name
        copyConfirmationFlow.value = null
    }

    fun setExportPreviewExpanded(expanded: Boolean) {
        savedStateHandle[KEY_PREVIEW_EXPANDED] = expanded
    }

    fun toggleShowAllSecurityRows() {
        val current = savedStateHandle.get<Boolean>(KEY_SHOW_ALL_SECURITY) ?: false
        savedStateHandle[KEY_SHOW_ALL_SECURITY] = !current
    }

    fun toggleShowAllRoamEvents() {
        val current = savedStateHandle.get<Boolean>(KEY_SHOW_ALL_ROAMS) ?: false
        savedStateHandle[KEY_SHOW_ALL_ROAMS] = !current
    }

    fun requestManualScan(): ScanTriggerResult {
        val result = runCatching {
            repository.requestManualScan(burstMode = false)
        }.getOrElse {
            ScanTriggerResult.RejectedByOs(nextRetryInMs = 5_000L)
        }
        statusBannerFlow.value = when (result) {
            is ScanTriggerResult.Triggered -> if (result.unthrottledMode) {
                "Active Wi-Fi sweep triggered · Unthrottled live mode"
            } else {
                "Active Wi-Fi sweep triggered · ${result.remainingTokens} burst pulses left"
            }
            is ScanTriggerResult.ThrottledByTokenBucket -> {
                val waitSec = ((result.nextTokenInMs + 999L) / 1000L).coerceAtLeast(1L)
                "Android battery guard active · Next active sweep in ${waitSec}s (passive live)"
            }
            is ScanTriggerResult.RejectedByOs -> {
                val waitSec = ((result.nextRetryInMs + 999L) / 1000L).coerceAtLeast(1L)
                "System busy · Retrying sweep in ${waitSec}s"
            }
            is ScanTriggerResult.BlockedByReadiness ->
                result.readinessState.headline
        }
        return result
    }

    fun dismissStatusBanner() {
        statusBannerFlow.value = null
    }

    fun refreshReadiness() {
        runCatching { repository.refreshReadiness() }
    }

    fun reportPermissionDenial(permanentlyDenied: Boolean) {
        runCatching { repository.reportPermissionDenial(permanentlyDenied) }
    }

    /**
     * Synchronously produces a fresh, unmodified [ExportPayload] directly from [WifiRadarRepository]
     * for Copy to Clipboard, Share Markdown, or Share JSON so rapid toggle-then-click sequences
     * are 100% immune to async preview race conditions.
     */
    fun buildFreshExportPayload(
        formatOverride: ExportFormat? = null,
    ): ExportPayload {
        val format = formatOverride ?: parseExportFormat(savedStateHandle.get<String>(KEY_EXPORT_FORMAT))
        val redact = savedStateHandle.get<Boolean>(SavedStateKeys.INSIGHTS_REDACT_MACS) ?: false
        return runCatching {
            repository.exportSnapshot(format = format, redactMacs = redact)
        }.getOrElse {
            ExportPayload(
                format = format,
                fileName = "wifi-radar-report.${format.fileExtension}",
                mimeType = format.mimeType,
                content = "",
                redactedMacs = redact,
                generatedAtEpochMs = 0L,
                networkCount = 0,
                sizeBytes = 0,
            )
        }
    }

    fun onExportCopiedToClipboard(payload: ExportPayload, succeeded: Boolean) {
        copyConfirmationFlow.value = if (succeeded) {
            val privacySuffix = if (payload.redactedMacs) " (MACs redacted)" else ""
            "Copied to clipboard · ${payload.format.displayLabel}$privacySuffix"
        } else {
            "Clipboard unavailable on this device"
        }
    }

    fun dismissCopyConfirmation() {
        copyConfirmationFlow.value = null
    }

    companion object {
        const val KEY_EXPORT_FORMAT = "insights_export_format"
        const val KEY_PREVIEW_EXPANDED = "insights_preview_expanded"
        const val KEY_HIGHLIGHTED_TIMELINE_BSSID = "insights_highlighted_timeline_bssid"
        const val KEY_SHOW_ALL_SECURITY = "insights_show_all_security"
        const val KEY_SHOW_ALL_ROAMS = "insights_show_all_roams"

        internal fun parseHistoryWindow(raw: String?): RssiHistoryWindow =
            RssiHistoryWindow.entries.firstOrNull { it.name == raw }
                ?: RssiHistoryWindow.FIVE_MINUTES

        internal fun parseExportFormat(raw: String?): ExportFormat =
            ExportFormat.entries.firstOrNull { it.name == raw }
                ?: ExportFormat.MARKDOWN

        fun provideFactory(
            repository: WifiRadarRepository,
            exportDispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                InsightsViewModel(
                    repository = repository,
                    savedStateHandle = createSavedStateHandle(),
                    exportDispatcher = exportDispatcher,
                )
            }
        }
    }
}
