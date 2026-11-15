package com.a55.wifiradar.core.wifiplatform

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.a55.wifiradar.core.model.ReadinessAction

/**
 * Runtime permission helpers and 1-tap system Settings [Intent] builders/launchers (`F19`).
 */
object WifiPlatformIntents {

    fun requiredRuntimePermissions(sdkInt: Int = Build.VERSION.SDK_INT): Array<String> =
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

    fun wifiPanelIntent(): Intent = Intent(Settings.Panel.ACTION_WIFI)

    fun wifiSettingsFallbackIntent(): Intent = Intent(Settings.ACTION_WIFI_SETTINGS)

    fun locationSourceSettingsIntent(): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

    fun appDetailsSettingsIntent(packageName: String): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    )

    fun developerOptionsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)

    @Suppress("DEPRECATION")
    fun requestScanAlwaysAvailableIntent(): Intent =
        Intent(WifiManager.ACTION_REQUEST_SCAN_ALWAYS_AVAILABLE)

    fun intentCandidatesForAction(context: Context, action: ReadinessAction): List<Intent> =
        when (action) {
            ReadinessAction.OPEN_WIFI_SETTINGS_PANEL -> listOf(
                wifiPanelIntent(),
                wifiSettingsFallbackIntent(),
                Intent(Settings.ACTION_SETTINGS),
            )
            ReadinessAction.OPEN_LOCATION_SETTINGS -> listOf(
                locationSourceSettingsIntent(),
                Intent(Settings.ACTION_SETTINGS),
            )
            ReadinessAction.OPEN_APP_PERMISSION_SETTINGS -> listOf(
                appDetailsSettingsIntent(context.packageName),
                Intent(Settings.ACTION_SETTINGS),
            )
            ReadinessAction.OPEN_SCAN_ALWAYS_AVAILABLE -> listOf(
                requestScanAlwaysAvailableIntent(),
                wifiSettingsFallbackIntent(),
                Intent(Settings.ACTION_SETTINGS),
            )
            ReadinessAction.NONE,
            ReadinessAction.REQUEST_RUNTIME_PERMISSIONS,
            ReadinessAction.UPGRADE_TO_PRECISE_LOCATION,
            -> emptyList()
        }

    /**
     * Launches the first succeeding Settings intent via defensive [safeIpcCall] (handling
     * `ActivityNotFoundException` & `SecurityException`). Never pre-gates with
     * `resolveActivity != null` so Android 11+ package visibility and Robolectric
     * `ShadowApplication.getNextStartedActivity()` work reliably.
     */
    fun launchReadinessAction(context: Context, action: ReadinessAction): Boolean {
        val addNewTask = context.findActivityOrNull() == null
        for (intent in intentCandidatesForAction(context, action)) {
            if (addNewTask) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val launched = safeIpcCall(default = false) {
                context.startActivity(intent)
                true
            }
            if (launched) return true
        }
        return false
    }

    fun launchDeveloperOptions(context: Context): Boolean {
        val addNewTask = context.findActivityOrNull() == null
        for (intent in listOf(developerOptionsIntent(), Intent(Settings.ACTION_SETTINGS))) {
            if (addNewTask) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val launched = safeIpcCall(default = false) {
                context.startActivity(intent)
                true
            }
            if (launched) return true
        }
        return false
    }

    private tailrec fun Context.findActivityOrNull(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext?.findActivityOrNull()
        else -> null
    }
}
