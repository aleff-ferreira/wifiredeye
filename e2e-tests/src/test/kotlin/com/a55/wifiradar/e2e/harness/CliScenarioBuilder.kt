package com.a55.wifiradar.e2e.harness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Opaque-box JSON scenario builder constructing multi-frame wireless scan replays for
 * `:cli-runner analyze` without importing any production DTO classes.
 */
object CliScenarioBuilder {

    data class IeSpec(
        val id: Int,
        val idExt: Int = 0,
        val hex: String,
    ) {
        fun toScanResultIe(): ScanResultFactory.IeSpec =
            ScanResultFactory.IeSpec.fromHex(id = id, idExt = idExt, hex = hex)
    }

    data class ScanEntrySpec(
        val ssid: String? = "TestNet",
        val rawSsidHex: String? = null,
        val bssid: String = "04:d4:c4:10:20:01",
        val rssiDbm: Int = -55,
        val frequencyMhz: Int = 5180,
        val centerFreq0Mhz: Int = 5210,
        val centerFreq1Mhz: Int = 0,
        val channelWidthMhz: Int = 80,
        val wifiStandard: String = "11ax",
        val capabilities: String = "[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]",
        val securityTypes: List<Int> = emptyList(),
        val timestampMicros: Long? = null,
        val is80211mcResponder: Boolean = false,
        val is80211azResponder: Boolean = false,
        val apMldMacAddress: String? = null,
        val ies: List<IeSpec> = emptyList(),
    )

    data class FrameSpec(
        val elapsedMs: Long,
        val connectedBssid: String? = null,
        val connectedTxMbps: Int = 866,
        val connectedRxMbps: Int = 866,
        val rttDistanceMm: Int? = null,
        val rttStdDevMm: Int? = null,
        val scans: List<ScanEntrySpec>,
    )

    fun singleFrame(
        scans: List<ScanEntrySpec>,
        elapsedMs: Long = 1_000L,
        connectedBssid: String? = null,
        deviceModel: String = "SM-A556B",
        deviceHas6Ghz: Boolean = false,
        bssidNicknames: Map<String, String> = emptyMap(),
        oneMeterCalibrationsDbm: Map<String, Double> = emptyMap(),
        rttDistanceMm: Int? = null,
        rttStdDevMm: Int? = null,
    ): String = multiFrame(
        frames = listOf(
            FrameSpec(
                elapsedMs = elapsedMs,
                connectedBssid = connectedBssid,
                rttDistanceMm = rttDistanceMm,
                rttStdDevMm = rttStdDevMm,
                scans = scans,
            ),
        ),
        deviceModel = deviceModel,
        deviceHas6Ghz = deviceHas6Ghz,
        bssidNicknames = bssidNicknames,
        oneMeterCalibrationsDbm = oneMeterCalibrationsDbm,
    )

    fun multiFrame(
        frames: List<FrameSpec>,
        deviceModel: String = "SM-A556B",
        deviceHas6Ghz: Boolean = false,
        bssidNicknames: Map<String, String> = emptyMap(),
        oneMeterCalibrationsDbm: Map<String, Double> = emptyMap(),
    ): String {
        val root: JsonObject = buildJsonObject {
            put("deviceModel", deviceModel)
            put("deviceHas6Ghz", deviceHas6Ghz)
            if (bssidNicknames.isNotEmpty()) {
                put(
                    "bssidNicknames",
                    buildJsonObject {
                        bssidNicknames.forEach { (k, v) -> put(k, v) }
                    },
                )
            }
            if (oneMeterCalibrationsDbm.isNotEmpty()) {
                put(
                    "oneMeterCalibrationsDbm",
                    buildJsonObject {
                        oneMeterCalibrationsDbm.forEach { (k, v) -> put(k, v) }
                    },
                )
            }
            put(
                "frames",
                buildJsonArray {
                    for (frame in frames) {
                        add(
                            buildJsonObject {
                                put("elapsedMs", frame.elapsedMs)
                                if (frame.connectedBssid != null) {
                                    put("connectedBssid", frame.connectedBssid)
                                } else {
                                    put("connectedBssid", JsonNull)
                                }
                                put("connectedTxMbps", frame.connectedTxMbps)
                                put("connectedRxMbps", frame.connectedRxMbps)
                                if (frame.rttDistanceMm != null) {
                                    put("rttDistanceMm", frame.rttDistanceMm)
                                }
                                if (frame.rttStdDevMm != null) {
                                    put("rttStdDevMm", frame.rttStdDevMm)
                                }
                                put(
                                    "scans",
                                    JsonArray(frame.scans.map(::encodeScanEntry)),
                                )
                            },
                        )
                    }
                },
            )
        }
        return root.toString()
    }

    private fun encodeScanEntry(entry: ScanEntrySpec): JsonObject = buildJsonObject {
        if (entry.ssid != null) put("ssid", entry.ssid) else put("ssid", JsonNull)
        if (entry.rawSsidHex != null) put("rawSsidHex", entry.rawSsidHex)
        put("bssid", entry.bssid)
        put("rssiDbm", entry.rssiDbm)
        put("frequencyMhz", entry.frequencyMhz)
        put("centerFreq0Mhz", entry.centerFreq0Mhz)
        put("centerFreq1Mhz", entry.centerFreq1Mhz)
        put("channelWidthMhz", entry.channelWidthMhz)
        put("wifiStandard", entry.wifiStandard)
        put("capabilities", entry.capabilities)
        if (entry.securityTypes.isNotEmpty()) {
            put("securityTypes", JsonArray(entry.securityTypes.map(::JsonPrimitive)))
        }
        if (entry.timestampMicros != null) {
            put("timestampMicros", entry.timestampMicros)
        }
        put("is80211mcResponder", entry.is80211mcResponder)
        put("is80211azResponder", entry.is80211azResponder)
        if (entry.apMldMacAddress != null) {
            put("apMldMacAddress", entry.apMldMacAddress)
        }
        put(
            "ies",
            buildJsonArray {
                for (ie in entry.ies) {
                    add(
                        buildJsonObject {
                            put("id", ie.id)
                            put("idExt", ie.idExt)
                            put("hex", ie.hex)
                        },
                    )
                }
            },
        )
    }
}
