package com.a55.wifiradar.e2e.harness

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertNotNull

/**
 * Schema-resilient strict JSON extractor for `:cli-runner analyze --format json` and UI-exported
 * JSON diagnostic documents (`DiagnosticExportDocument`).
 */
object CliJsonLens {

    fun JsonObject.obj(vararg keys: String): JsonObject? {
        for (k in keys) {
            val v = this[k]
            if (v is JsonObject) return v
        }
        return null
    }

    fun JsonObject.arr(vararg keys: String): JsonArray? {
        for (k in keys) {
            val v = this[k]
            if (v is JsonArray) return v
        }
        return null
    }

    fun JsonObject.str(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k]
            if (v is JsonPrimitive && v !is JsonNull) return v.content
        }
        return null
    }

    fun JsonObject.int(vararg keys: String): Int? {
        for (k in keys) {
            val v = this[k]
            if (v is JsonPrimitive && v !is JsonNull) {
                v.intOrNull?.let { return it }
                v.doubleOrNull?.let { return kotlin.math.round(it).toInt() }
            }
        }
        return null
    }

    fun JsonObject.long(vararg keys: String): Long? {
        for (k in keys) {
            val v = this[k]
            if (v is JsonPrimitive && v !is JsonNull) {
                v.longOrNull?.let { return it }
            }
        }
        return null
    }

    fun JsonObject.dbl(vararg keys: String): Double? {
        for (k in keys) {
            val v = this[k]
            if (v is JsonPrimitive && v !is JsonNull) {
                v.doubleOrNull?.let { return it }
            }
        }
        return null
    }

    fun JsonObject.bool(vararg keys: String): Boolean? {
        for (k in keys) {
            val v = this[k]
            if (v is JsonPrimitive && v !is JsonNull) {
                v.booleanOrNull?.let { return it }
            }
        }
        return null
    }

    fun radios(root: JsonObject): List<JsonObject> {
        val arr = root.arr("detectedRadios", "radios", "networks", "bssidRadios")
            ?: root.obj("snapshot", "radarSnapshot")?.arr("radios", "detectedRadios", "networks")
        assertNotNull("Expected detectedRadios[] in CLI JSON output: ${root.keys}", arr)
        return arr!!.map { it.jsonObject }
    }

    fun radioByBssid(root: JsonObject, bssid: String): JsonObject {
        val norm = bssid.lowercase()
        return radios(root).firstOrNull { r ->
            r.str("bssid")?.lowercase() == norm
        } ?: throw AssertionError(
            "No radio found with bssid=$bssid in ${radios(root).map { it.str("bssid") }}",
        )
    }

    fun ssidObj(radio: JsonObject): JsonObject? = radio.obj("ssid", "decodedSsid")

    fun ssidDisplayName(radio: JsonObject): String {
        ssidObj(radio)?.str("displayName", "label")?.let { return it }
        return radio.str("effectiveDisplayName", "displayName", "ssid") ?: ""
    }

    fun channelObj(radio: JsonObject): JsonObject =
        radio.obj("channelDescriptor", "channel") ?: radio

    fun vendorObj(radio: JsonObject): JsonObject =
        radio.obj("vendorInfo", "vendor") ?: radio

    fun securityObj(radio: JsonObject): JsonObject =
        radio.obj("security", "securityAssessment") ?: radio

    fun beaconObj(radio: JsonObject): JsonObject =
        radio.obj("beaconDetails", "beaconIeDetails") ?: radio

    fun phyObj(radio: JsonObject): JsonObject =
        beaconObj(radio).obj("phyCapability", "phy") ?: beaconObj(radio)

    fun normalizeSecurityGrade(rawGrade: String?): String = when (rawGrade?.trim()?.uppercase()) {
        "A_PLUS", "A+" -> "A+"
        "A_MINUS", "A-" -> "A-"
        "A" -> "A"
        "B" -> "B"
        "C" -> "C"
        "D" -> "D"
        "F" -> "F"
        else -> rawGrade.orEmpty()
    }

    fun normalizeWidthMhz(widthElement: JsonElement?): Int? {
        if (widthElement == null || widthElement is JsonNull) return null
        if (widthElement is JsonPrimitive) {
            widthElement.intOrNull?.let { return it }
            val text = widthElement.content.uppercase()
            return when {
                text.contains("320") -> 320
                text.contains("80_PLUS_80") || text.contains("80+80") -> 160
                text.contains("160") -> 160
                text.contains("80") -> 80
                text.contains("40") -> 40
                text.contains("20") -> 20
                else -> null
            }
        }
        if (widthElement is JsonObject) {
            return widthElement.int("widthMhz", "mhz")
        }
        return null
    }

    fun groupedNetworks(root: JsonObject): List<JsonObject> {
        val arr = root.arr("groupedNetworks", "essGroups", "meshGroups")
            ?: root.obj("snapshot", "radarSnapshot")?.arr("groupedNetworks", "essGroups")
        assertNotNull("Expected groupedNetworks[] in CLI JSON output: ${root.keys}", arr)
        return arr!!.map { it.jsonObject }
    }

    fun physicalClusters(root: JsonObject): List<JsonObject> {
        val arr = root.arr("physicalClusters", "apClusters", "clusters")
            ?: root.obj("snapshot", "radarSnapshot")?.arr("physicalClusters", "apClusters")
        assertNotNull("Expected physicalClusters[] in CLI JSON output: ${root.keys}", arr)
        return arr!!.map { it.jsonObject }
    }

    fun spectrumForBand(root: JsonObject, bandKey: String): JsonObject {
        when (bandKey) {
            "2.4", "24", "BAND_2_4_GHZ" -> root.obj("spectrum24Ghz", "band24Ghz")?.let { return it }
            "5", "BAND_5_GHZ" -> root.obj("spectrum5Ghz", "band5Ghz")?.let { return it }
            "6", "BAND_6_GHZ" -> root.obj("spectrum6Ghz", "band6Ghz")?.let { return it }
        }
        root.obj("spectrum", "spectrumByBand")?.let { specObj ->
            for ((k, v) in specObj) {
                if (k.contains(bandKey, ignoreCase = true) && v is JsonObject) return v
            }
        }
        root.arr("spectrumAnalyses", "spectrum")?.forEach { el ->
            val o = el.jsonObject
            if (o.str("band")?.contains(bandKey.replace(".", "_"), ignoreCase = true) == true) {
                return o
            }
        }
        throw AssertionError("Missing spectrum analysis for band=$bandKey in keys=${root.keys}")
    }

    fun channelScores(spectrumObj: JsonObject): List<JsonObject> {
        val arr = spectrumObj.arr("channelScores", "scores", "channels")
        assertNotNull("Expected channelScores[] in spectrum object: ${spectrumObj.keys}", arr)
        return arr!!.map { it.jsonObject }
    }

    fun scoreForChannel(spectrumObj: JsonObject, channel: Int): JsonObject =
        channelScores(spectrumObj).firstOrNull {
            it.int("channel", "channelNumber", "primaryChannel") == channel
        } ?: throw AssertionError(
            "Missing score for channel=$channel in ${
                channelScores(spectrumObj).map { it.int("channel", "channelNumber", "primaryChannel") }
            }",
        )

    fun healthObj(root: JsonObject): JsonObject =
        root.obj("healthReport", "environmentHealth", "health")
            ?: throw AssertionError("Missing healthReport object in keys=${root.keys}")

    fun proximityFrames(root: JsonObject): List<JsonObject> {
        root.arr("proximityTrajectory", "proximityHistory", "proximityFrames")?.let { arr ->
            return arr.map { it.jsonObject }
        }
        root.obj("proximity", "proximityTelemetry")?.let { return listOf(it) }
        throw AssertionError("Missing proximityTrajectory in keys=${root.keys}")
    }

    fun latestProximity(root: JsonObject): JsonObject =
        proximityFrames(root).last()

    fun roamEvents(root: JsonObject): List<JsonObject> {
        val arr = root.arr("roamEvents", "roamingHistory")
            ?: root.obj("snapshot", "radarSnapshot")?.arr("roamEvents")
            ?: JsonArray(emptyList())
        return arr.map { it.jsonObject }
    }
}
