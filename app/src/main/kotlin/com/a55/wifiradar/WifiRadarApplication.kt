package com.a55.wifiradar

import android.app.Application
import com.a55.wifiradar.core.model.WifiRadarRepository
import com.a55.wifiradar.core.rfengine.DefaultRfAnalyticsEngine
import com.a55.wifiradar.core.wifiplatform.AndroidProximityFeedbackController
import com.a55.wifiradar.core.wifiplatform.AndroidUserPreferencesDataSource
import com.a55.wifiradar.core.wifiplatform.AndroidWifiPlatformDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide container so scan state, RSSI history and Kalman filters survive Activity recreation
 * (rotation, dark-mode switch, One UI split-screen resize).
 */
class WifiRadarApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var platform: AndroidWifiPlatformDataSource
        private set
    lateinit var feedback: AndroidProximityFeedbackController
        private set
    lateinit var repository: WifiRadarRepository
        private set

    override fun onCreate() {
        super.onCreate()
        platform = AndroidWifiPlatformDataSource(this)
        feedback = AndroidProximityFeedbackController(this)
        repository = DefaultWifiRadarRepository(
            platform = platform,
            preferences = AndroidUserPreferencesDataSource(this),
            engine = DefaultRfAnalyticsEngine(),
            feedbackController = feedback,
            scope = appScope,
        )
    }
}
