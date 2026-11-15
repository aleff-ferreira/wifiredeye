package com.a55.wifiradar.feature.networks

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.a55.wifiradar.core.model.ReadinessAction
import com.a55.wifiradar.core.model.ScannerReadinessState

internal const val ACTION_REQUEST_SCAN_ALWAYS_AVAILABLE_LITERAL =
    "android.net.wifi.action.REQUEST_SCAN_ALWAYS_AVAILABLE"

internal object NetworksSystemActions {

    fun requiredWifiScanPermissions(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> =
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }

    fun handlePermissionGrantResult(
        context: Context,
        grantResults: Map<String, Boolean>,
        priorReadiness: ScannerReadinessState,
        onReportDenial: (permanentlyDenied: Boolean) -> Unit,
        onRefreshReadiness: () -> Unit,
        onPermissionGrantedTriggerScan: () -> Unit,
    ) {
        val fineGranted = grantResults[Manifest.permission.ACCESS_FINE_LOCATION]
            ?: (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED)

        val coarseGranted = grantResults[Manifest.permission.ACCESS_COARSE_LOCATION]
            ?: (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED)

        val nearbyGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            grantResults[Manifest.permission.NEARBY_WIFI_DEVICES]
                ?: (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                ) == PackageManager.PERMISSION_GRANTED)
        } else {
            true
        }

        val activity = context.findActivity()
        when {
            fineGranted && nearbyGranted -> {
                onRefreshReadiness()
                onPermissionGrantedTriggerScan()
            }
            coarseGranted && !fineGranted -> {
                val permanentlyDenied = priorReadiness is ScannerReadinessState.ApproximateOnlyNeedsPrecise &&
                    activity != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    )
                onReportDenial(permanentlyDenied)
                onRefreshReadiness()
            }
            else -> {
                val permanentlyDenied = activity != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    )
                onReportDenial(permanentlyDenied)
                onRefreshReadiness()
            }
        }
    }

    /**
     * Launches system Settings intents defensively via `runCatching` with fallback chains.
     * Never gates behind `resolveActivity != null` so Android 11+ package visibility and
     * Robolectric `ShadowApplication.nextStartedActivity` work reliably.
     */
    fun launchReadinessSettingsAction(context: Context, action: ReadinessAction) {
        when (action) {
            ReadinessAction.OPEN_APP_PERMISSION_SETTINGS -> {
                val primary = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                )
                startWithFallbacks(context, primary, Intent(Settings.ACTION_SETTINGS))
            }

            ReadinessAction.OPEN_LOCATION_SETTINGS -> {
                startWithFallbacks(
                    context,
                    Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                    Intent(Settings.ACTION_SETTINGS),
                )
            }

            ReadinessAction.OPEN_WIFI_SETTINGS_PANEL -> {
                startWithFallbacks(
                    context,
                    Intent(Settings.Panel.ACTION_WIFI),
                    Intent(Settings.ACTION_WIFI_SETTINGS),
                    Intent(Settings.ACTION_SETTINGS),
                )
            }

            ReadinessAction.OPEN_SCAN_ALWAYS_AVAILABLE -> {
                startWithFallbacks(
                    context,
                    Intent(ACTION_REQUEST_SCAN_ALWAYS_AVAILABLE_LITERAL),
                    Intent(Settings.ACTION_WIFI_SETTINGS),
                    Intent(Settings.ACTION_SETTINGS),
                )
            }

            ReadinessAction.NONE,
            ReadinessAction.REQUEST_RUNTIME_PERMISSIONS,
            ReadinessAction.UPGRADE_TO_PRECISE_LOCATION -> Unit
        }
    }

    private fun startWithFallbacks(context: Context, vararg candidates: Intent) {
        val needsNewTask = context.findActivity() == null
        for (candidate in candidates) {
            val prepared = if (needsNewTask) {
                candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                candidate
            }
            val succeeded = runCatching {
                context.startActivity(prepared)
            }.isSuccess
            if (succeeded) return
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext?.findActivity()
    else -> null
}
