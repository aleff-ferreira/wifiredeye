package com.a55.wifiradar.core.wifiplatform

import android.content.Context
import android.content.SharedPreferences
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.UserPreferencesDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Offline persistent store for user-defined BSSID router nicknames and `1m` RSSI distance
 * calibrations (`F23, F34`).
 *
 * - Synchronously loads persisted JSON maps during `init` so [bssidNicknames] and
 *   [oneMeterCalibrationsDbm] hold saved values immediately upon construction.
 * - Normalizes MAC addresses to canonical uppercase `AA:BB:CC:DD:EE:FF`, rejecting malformed,
 *   all-zero, and Android-redacted (`02:00:00:00:00:00`) BSSIDs.
 * - Serializes concurrent updates via [Mutex] and commits synchronously on [ioDispatcher].
 * - Recovers cleanly from corrupt JSON without crashing (`F34`).
 */
class AndroidUserPreferencesDataSource internal constructor(
    private val sharedPreferences: SharedPreferences,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : UserPreferencesDataSource {

    @JvmOverloads
    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        preferencesName: String = DEFAULT_PREFS_NAME,
    ) : this(
        sharedPreferences = (context.applicationContext ?: context).getSharedPreferences(
            preferencesName,
            Context.MODE_PRIVATE,
        ),
        ioDispatcher = ioDispatcher,
    )

    private val mutex = Mutex()

    private val _bssidNicknames = MutableStateFlow(loadNicknamesSafely())
    override val bssidNicknames: StateFlow<Map<String, String>> = _bssidNicknames.asStateFlow()

    private val _oneMeterCalibrationsDbm = MutableStateFlow(loadCalibrationsSafely())
    override val oneMeterCalibrationsDbm: StateFlow<Map<String, Double>> =
        _oneMeterCalibrationsDbm.asStateFlow()

    override suspend fun setNickname(bssid: String, nickname: String?) {
        val key = normalizeValidTargetBssid(bssid) ?: return
        val sanitized = nickname?.trim()?.take(MAX_NICKNAME_LENGTH)

        mutex.withLock {
            val current = _bssidNicknames.value
            val updated = LinkedHashMap(current)
            if (sanitized.isNullOrEmpty()) {
                if (!updated.containsKey(key)) return@withLock
                updated.remove(key)
            } else {
                if (updated[key] == sanitized) return@withLock
                updated[key] = sanitized
            }

            val snapshot = updated.toMap()
            withContext(ioDispatcher) {
                safeIpcUnit {
                    val encoded = jsonCodec.encodeToString(snapshot)
                    @Suppress("ApplySharedPref")
                    sharedPreferences.edit().putString(KEY_NICKNAMES_JSON, encoded).commit()
                }
            }
            _bssidNicknames.value = snapshot
        }
    }

    override suspend fun setOneMeterCalibration(bssid: String, rssiAtOneMeterDbm: Double?) {
        val key = normalizeValidTargetBssid(bssid) ?: return
        if (rssiAtOneMeterDbm != null && !rssiAtOneMeterDbm.isFinite()) return
        val sanitizedDbm = rssiAtOneMeterDbm?.coerceIn(MIN_CALIBRATION_DBM, MAX_CALIBRATION_DBM)

        mutex.withLock {
            val current = _oneMeterCalibrationsDbm.value
            val updated = LinkedHashMap(current)
            if (sanitizedDbm == null) {
                if (!updated.containsKey(key)) return@withLock
                updated.remove(key)
            } else {
                if (updated[key] == sanitizedDbm) return@withLock
                updated[key] = sanitizedDbm
            }

            val snapshot = updated.toMap()
            withContext(ioDispatcher) {
                safeIpcUnit {
                    val encoded = jsonCodec.encodeToString(snapshot)
                    @Suppress("ApplySharedPref")
                    sharedPreferences.edit().putString(KEY_CALIBRATIONS_JSON, encoded).commit()
                }
            }
            _oneMeterCalibrationsDbm.value = snapshot
        }
    }

    private fun loadNicknamesSafely(): Map<String, String> =
        safeIpcCall(default = emptyMap()) {
            val raw = sharedPreferences.getString(KEY_NICKNAMES_JSON, null)
            if (raw.isNullOrBlank()) return@safeIpcCall emptyMap()
            val decoded = jsonCodec.decodeFromString<Map<String, String>>(raw)
            buildMap {
                for ((rawBssid, rawName) in decoded) {
                    val key = normalizeValidTargetBssid(rawBssid) ?: continue
                    val cleanName = rawName.trim().take(MAX_NICKNAME_LENGTH)
                    if (cleanName.isNotEmpty()) {
                        put(key, cleanName)
                    }
                }
            }
        }

    private fun loadCalibrationsSafely(): Map<String, Double> =
        safeIpcCall(default = emptyMap()) {
            val raw = sharedPreferences.getString(KEY_CALIBRATIONS_JSON, null)
            if (raw.isNullOrBlank()) return@safeIpcCall emptyMap()
            val decoded = jsonCodec.decodeFromString<Map<String, Double>>(raw)
            buildMap {
                for ((rawBssid, rawDbm) in decoded) {
                    val key = normalizeValidTargetBssid(rawBssid) ?: continue
                    if (rawDbm.isFinite()) {
                        put(key, rawDbm.coerceIn(MIN_CALIBRATION_DBM, MAX_CALIBRATION_DBM))
                    }
                }
            }
        }

    private fun normalizeValidTargetBssid(rawBssid: String): String? {
        val normalized = MacAddressUtils.normalizeBssid(rawBssid)
        if (!MacAddressUtils.isValidMac(normalized) ||
            normalized == ZERO_MAC ||
            MacAddressUtils.isAndroidRedactedMac(normalized)
        ) {
            return null
        }
        return normalized
    }

    companion object {
        const val DEFAULT_PREFS_NAME = "a55_wifi_radar_user_prefs"
        internal const val KEY_NICKNAMES_JSON = "bssid_nicknames_v1"
        internal const val KEY_CALIBRATIONS_JSON = "one_meter_calibrations_dbm_v1"
        const val MAX_NICKNAME_LENGTH = 64
        const val MIN_CALIBRATION_DBM = -100.0
        const val MAX_CALIBRATION_DBM = -10.0
        private const val ZERO_MAC = "00:00:00:00:00:00"

        private val jsonCodec = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            allowSpecialFloatingPointValues = false
        }
    }
}
