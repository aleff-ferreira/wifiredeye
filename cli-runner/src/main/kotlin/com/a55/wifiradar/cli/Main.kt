package com.a55.wifiradar.cli

import com.a55.wifiradar.core.model.ChannelWidth
import com.a55.wifiradar.core.model.CliScanEntry
import com.a55.wifiradar.core.model.CliScenarioInput
import com.a55.wifiradar.core.model.ConnectedWifiObservation
import com.a55.wifiradar.core.model.DeviceRadioCapabilities
import com.a55.wifiradar.core.model.ExportFormat
import com.a55.wifiradar.core.model.MacAddressUtils
import com.a55.wifiradar.core.model.PropagationEnvironment
import com.a55.wifiradar.core.model.ProximityTargetKey
import com.a55.wifiradar.core.model.ProximityTelemetry
import com.a55.wifiradar.core.model.RadarSnapshot
import com.a55.wifiradar.core.model.RawInformationElement
import com.a55.wifiradar.core.model.RawScanBatch
import com.a55.wifiradar.core.model.RawScanEntry
import com.a55.wifiradar.core.model.RttMeasurement
import com.a55.wifiradar.core.model.ScanTelemetry
import com.a55.wifiradar.core.model.WifiGeneration
import com.a55.wifiradar.core.rfengine.DefaultRfAnalyticsEngine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.abs
import kotlin.system.exitProcess

private val cliJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
}

fun main(args: Array<String>) {
    val exitCode = runCli(args, System.`in`, System.out, System.err)
    if (exitCode != 0) {
        exitProcess(exitCode)
    }
}

/**
 * Pure-JVM, stream-injectable CLI entrypoint (`F17`) driving [DefaultRfAnalyticsEngine]
 * directly without ever calling `exitProcess` inside JUnit tests.
 */
fun runCli(
    args: Array<String>,
    stdin: InputStream = ByteArrayInputStream(ByteArray(0)),
    stdout: PrintStream = System.out,
    stderr: PrintStream = System.err,
): Int {
    if (args.any { it == "--help" || it == "-h" }) {
        printUsage(stdout)
        return 0
    }

    val parsedArgs = when (val res = parseCliArgs(args)) {
        is CliArgResult.Success -> res.options
        is CliArgResult.Error -> {
            stderr.println("CLI argument error: ${res.message}")
            stderr.flush()
            return 2
        }
    }

    val rawInputJson = try {
        if (parsedArgs.inputSource == "-") {
            stdin.readBytes().toString(StandardCharsets.UTF_8)
        } else {
            val file = File(parsedArgs.inputSource)
            if (!file.exists() || !file.isFile || !file.canRead()) {
                stderr.println("CLI input error: Input file does not exist or is not readable: ${parsedArgs.inputSource}")
                stderr.flush()
                return 2
            }
            file.readText(StandardCharsets.UTF_8)
        }
    } catch (e: Exception) {
        stderr.println("CLI I/O error reading '${parsedArgs.inputSource}': ${e.message}")
        stderr.flush()
        return 2
    }

    if (rawInputJson.isBlank()) {
        stderr.println("Payload error: Input JSON stream is empty.")
        stderr.flush()
        return 1
    }

    val scenario = try {
        cliJson.decodeFromString<CliScenarioInput>(rawInputJson)
    } catch (e: SerializationException) {
        stderr.println("Payload error: Malformed CliScenarioInput JSON — ${e.message}")
        stderr.flush()
        return 1
    } catch (e: IllegalArgumentException) {
        stderr.println("Payload error: Invalid CliScenarioInput schema — ${e.message}")
        stderr.flush()
        return 1
    }

    return try {
        executeScenarioReplay(
            scenario = scenario,
            options = parsedArgs,
            stdout = stdout,
        )
        0
    } catch (e: PayloadFormatException) {
        stderr.println("Payload error: ${e.message}")
        stderr.flush()
        1
    } catch (e: Exception) {
        stderr.println("Execution error: ${e.message}")
        stderr.flush()
        1
    }
}

private class PayloadFormatException(message: String) : RuntimeException(message)

private data class CliOptions(
    val inputSource: String,
    val format: ExportFormat,
    val targetBssid: String?,
    val environment: PropagationEnvironment,
    val redactMacs: Boolean,
)

private sealed interface CliArgResult {
    data class Success(val options: CliOptions) : CliArgResult
    data class Error(val message: String) : CliArgResult
}

private fun parseCliArgs(args: Array<String>): CliArgResult {
    if (args.isEmpty()) {
        return CliArgResult.Error("Missing command. Expected: analyze --input <path|-> --format <json|markdown>")
    }
    if (args[0] != "analyze") {
        return CliArgResult.Error("Unknown subcommand '${args[0]}'. Expected 'analyze'.")
    }

    var inputSource: String? = null
    var formatRaw: String? = null
    var targetBssidRaw: String? = null
    var envRaw: String? = null
    var redactMacs = false

    var i = 1
    while (i < args.size) {
        val token = args[i]
        if (!token.startsWith("--")) {
            return CliArgResult.Error("Unexpected argument '$token'.")
        }

        val eqIdx = token.indexOf('=')
        val flagName = if (eqIdx > 0) token.substring(0, eqIdx) else token
        val inlineValue = if (eqIdx > 0) token.substring(eqIdx + 1) else null

        fun consumeValue(): String? {
            if (inlineValue != null) return inlineValue
            if (i + 1 >= args.size) return null
            i++
            return args[i]
        }

        when (flagName) {
            "--input" -> {
                val v = consumeValue()
                    ?: return CliArgResult.Error("Flag '--input' requires a file path or '-'.")
                if (v.isBlank()) {
                    return CliArgResult.Error("Flag '--input' cannot be blank.")
                }
                inputSource = v
            }
            "--format" -> {
                val v = consumeValue()
                    ?: return CliArgResult.Error("Flag '--format' requires 'json' or 'markdown'.")
                formatRaw = v
            }
            "--target-bssid" -> {
                val v = consumeValue()
                    ?: return CliArgResult.Error("Flag '--target-bssid' requires a 6-octet MAC address.")
                targetBssidRaw = v
            }
            "--env" -> {
                val v = consumeValue()
                    ?: return CliArgResult.Error("Flag '--env' requires OPEN, HOME_OFFICE, or HEAVY_WALLS.")
                envRaw = v
            }
            "--redact-macs" -> {
                if (inlineValue != null) {
                    redactMacs = when (inlineValue.lowercase(Locale.US)) {
                        "true", "1" -> true
                        "false", "0" -> false
                        else -> return CliArgResult.Error("Invalid boolean value '$inlineValue' for '--redact-macs'.")
                    }
                } else {
                    redactMacs = true
                }
            }
            // Accepted for script compatibility; the exported report already evaluates DFS channels
            // through the engine defaults, so the flag currently does not change the output.
            "--include-dfs" -> Unit
            else -> return CliArgResult.Error("Unknown flag '$flagName'.")
        }
        i++
    }

    val resolvedInput = inputSource
        ?: return CliArgResult.Error("Missing required flag '--input <path|->'.")

    val resolvedFormatStr = formatRaw
        ?: return CliArgResult.Error("Missing required flag '--format <json|markdown>'.")
    val resolvedFormat = when (resolvedFormatStr.trim().lowercase(Locale.US)) {
        "json" -> ExportFormat.JSON
        "markdown", "md" -> ExportFormat.MARKDOWN
        else -> return CliArgResult.Error(
            "Unsupported format '$resolvedFormatStr'. Expected 'json' or 'markdown'."
        )
    }

    val normalizedTargetBssid = if (targetBssidRaw != null) {
        if (!MacAddressUtils.isValidMac(targetBssidRaw)) {
            return CliArgResult.Error(
                "Invalid '--target-bssid' MAC address '$targetBssidRaw'. Expected format AA:BB:CC:DD:EE:FF."
            )
        }
        MacAddressUtils.normalizeBssid(targetBssidRaw)
    } else {
        null
    }

    val resolvedEnv = if (envRaw != null) {
        when (envRaw.trim().uppercase(Locale.US)) {
            "OPEN" -> PropagationEnvironment.OPEN
            "HOME_OFFICE" -> PropagationEnvironment.HOME_OFFICE
            "HEAVY_WALLS" -> PropagationEnvironment.HEAVY_WALLS
            else -> return CliArgResult.Error(
                "Unsupported '--env' value '$envRaw'. Expected OPEN, HOME_OFFICE, or HEAVY_WALLS."
            )
        }
    } else {
        PropagationEnvironment.HOME_OFFICE
    }

    return CliArgResult.Success(
        CliOptions(
            inputSource = resolvedInput,
            format = resolvedFormat,
            targetBssid = normalizedTargetBssid,
            environment = resolvedEnv,
            redactMacs = redactMacs,
        )
    )
}

private fun executeScenarioReplay(
    scenario: CliScenarioInput,
    options: CliOptions,
    stdout: PrintStream,
) {
    val supportedStandards = buildSet {
        add(WifiGeneration.LEGACY_11B)
        add(WifiGeneration.LEGACY_11G)
        add(WifiGeneration.LEGACY_11A)
        add(WifiGeneration.WIFI_4)
        add(WifiGeneration.WIFI_5)
        add(WifiGeneration.WIFI_6)
        if (scenario.deviceHas6Ghz) add(WifiGeneration.WIFI_6E)
    }

    val capabilities = DeviceRadioCapabilities(
        deviceModel = scenario.deviceModel,
        has24Ghz = true,
        has5Ghz = true,
        has6Ghz = scenario.deviceHas6Ghz,
        maxSupportedWifiGeneration = if (scenario.deviceHas6Ghz) {
            WifiGeneration.WIFI_6E
        } else {
            WifiGeneration.WIFI_6
        },
        supportedStandards = supportedStandards,
    )

    val engine = DefaultRfAnalyticsEngine()
    val sortedFrames = scenario.frames
        .withIndex()
        .sortedWith(compareBy({ it.value.elapsedMs }, { it.index }))
        .map { it.value }

    var lastSnapshot: RadarSnapshot
    val proximityTrajectory = ArrayList<ProximityTelemetry>(sortedFrames.size)

    if (sortedFrames.isEmpty()) {
        lastSnapshot = engine.processScanSnapshot(
            rawBatch = null,
            connectedWifi = null,
            scanTelemetry = ScanTelemetry(),
            radioCapabilities = capabilities,
            bssidNicknames = scenario.bssidNicknames,
            oneMeterCalibrationsDbm = scenario.oneMeterCalibrationsDbm,
            currentElapsedMs = 0L,
            currentEpochMs = 0L,
        )
    } else {
        var lockedTargetKey: ProximityTargetKey? = options.targetBssid?.let {
            ProximityTargetKey.BssidLock(bssid = it)
        }

        val knownSsidByBssid = HashMap<String, String>()
        val knownFreqByBssid = HashMap<String, Int>()
        val knownRssiByBssid = HashMap<String, Int>()
        val knownStdByBssid = HashMap<String, Int>()

        var latestSnapshot: RadarSnapshot? = null

        for (frame in sortedFrames) {
            val rawEntries = frame.scans.map { scan ->
                mapCliScanEntry(scan, frame.elapsedMs).also { mapped ->
                    val norm = MacAddressUtils.normalizeBssid(mapped.bssid)
                    if (norm.isNotEmpty()) {
                        scan.ssid?.takeIf { it.isNotBlank() }?.let { knownSsidByBssid[norm] = it }
                        knownFreqByBssid[norm] = mapped.frequencyMhz
                        knownRssiByBssid[norm] = mapped.rssiDbm
                        knownStdByBssid[norm] = mapped.wifiStandardCode
                    }
                }
            }

            val connectedObs = frame.connectedBssid?.takeIf { it.isNotBlank() }?.let { rawConnMac ->
                if (!MacAddressUtils.isValidMac(rawConnMac)) {
                    throw PayloadFormatException("Malformed connectedBssid '$rawConnMac' at elapsedMs=${frame.elapsedMs}.")
                }
                val normConn = MacAddressUtils.normalizeBssid(rawConnMac)
                val matchingEntry = rawEntries.firstOrNull {
                    MacAddressUtils.normalizeBssid(it.bssid) == normConn
                }
                val matchingCliScan = frame.scans.firstOrNull {
                    MacAddressUtils.normalizeBssid(it.bssid) == normConn
                }
                val connSsid = matchingCliScan?.ssid ?: knownSsidByBssid[normConn]
                val connFreq = matchingEntry?.frequencyMhz ?: knownFreqByBssid[normConn] ?: 5180
                val connRssi = matchingEntry?.rssiDbm ?: knownRssiByBssid[normConn] ?: -60
                val connStd = matchingEntry?.wifiStandardCode ?: knownStdByBssid[normConn] ?: 6

                ConnectedWifiObservation(
                    bssid = normConn,
                    ssid = connSsid,
                    isLocationRedacted = MacAddressUtils.isAndroidRedactedMac(normConn),
                    rssiDbm = connRssi,
                    frequencyMhz = connFreq,
                    txLinkSpeedMbps = frame.connectedTxMbps,
                    rxLinkSpeedMbps = frame.connectedRxMbps,
                    wifiStandardCode = connStd,
                    apMldMacAddress = matchingEntry?.apMldMacAddress,
                    timestampElapsedMs = frame.elapsedMs,
                )
            }

            val batch = RawScanBatch(
                entries = rawEntries,
                receivedElapsedMs = frame.elapsedMs,
                receivedEpochMs = frame.elapsedMs,
                isFreshHardwareSweep = true,
            )

            val snapshot = engine.processScanSnapshot(
                rawBatch = batch,
                connectedWifi = connectedObs,
                scanTelemetry = ScanTelemetry(
                    lastScanTimestampMs = frame.elapsedMs,
                    lastScanAgeMs = 0L,
                ),
                radioCapabilities = capabilities,
                bssidNicknames = scenario.bssidNicknames,
                oneMeterCalibrationsDbm = scenario.oneMeterCalibrationsDbm,
                currentElapsedMs = frame.elapsedMs,
                currentEpochMs = frame.elapsedMs,
            )
            latestSnapshot = snapshot

            if (lockedTargetKey == null) {
                if (connectedObs != null && !connectedObs.isLocationRedacted) {
                    lockedTargetKey = ProximityTargetKey.BssidLock(
                        bssid = connectedObs.bssid,
                        ssidHint = connectedObs.ssid,
                    )
                } else if (snapshot.radios.isNotEmpty()) {
                    val top = snapshot.radios.first()
                    lockedTargetKey = ProximityTargetKey.BssidLock(
                        bssid = top.bssid,
                        ssidHint = top.effectiveDisplayName,
                    )
                }
            }

            val currentTarget = lockedTargetKey
            if (currentTarget != null) {
                val rttMeasurement = frame.rttDistanceMm?.let { distMm ->
                    val rttBssid = (currentTarget as? ProximityTargetKey.BssidLock)?.bssid
                        ?: connectedObs?.bssid
                        ?: snapshot.radios.firstOrNull()?.bssid
                    if (rttBssid != null) {
                        val matchingRadio = snapshot.radios.firstOrNull { it.bssid == rttBssid }
                        RttMeasurement(
                            bssid = rttBssid,
                            distanceMeters = distMm.toDouble() / 1000.0,
                            rawDistanceMm = distMm,
                            distanceStdDevMeters = (frame.rttStdDevMm ?: 350).coerceAtLeast(50).toDouble() / 1000.0,
                            rssiDbm = matchingRadio?.rssiDbm ?: connectedObs?.rssiDbm ?: -60,
                            is80211azNtb = matchingRadio?.is80211azResponder == true,
                            timestampElapsedMs = frame.elapsedMs,
                        )
                    } else {
                        null
                    }
                }

                proximityTrajectory.add(
                    engine.updateProximityTarget(
                        targetKey = currentTarget,
                        snapshot = snapshot,
                        environment = options.environment,
                        rttMeasurement = rttMeasurement,
                        oneMeterCalibrationsDbm = scenario.oneMeterCalibrationsDbm,
                        currentElapsedMs = frame.elapsedMs,
                    )
                )
            }
        }

        lastSnapshot = latestSnapshot!!
    }

    val health = engine.computeEnvironmentHealth(lastSnapshot)
    val output = when (options.format) {
        ExportFormat.JSON -> engine.exportDiagnosticJson(
            snapshot = lastSnapshot,
            health = health,
            redactMacs = options.redactMacs,
            proximityHistory = proximityTrajectory,
        )
        ExportFormat.MARKDOWN -> engine.exportDiagnosticMarkdown(
            snapshot = lastSnapshot,
            health = health,
            redactMacs = options.redactMacs,
            proximityHistory = proximityTrajectory,
        )
    }

    stdout.println(output)
    stdout.flush()
}

private fun mapCliScanEntry(scan: CliScanEntry, frameElapsedMs: Long): RawScanEntry {
    if (scan.bssid.isBlank() || !MacAddressUtils.isValidMac(scan.bssid)) {
        throw PayloadFormatException("Invalid scan entry BSSID '${scan.bssid}' at elapsedMs=$frameElapsedMs.")
    }
    val rawSsidBytes: ByteArray? = when {
        scan.rawSsidHex != null -> decodeStrictHex(scan.rawSsidHex!!, "rawSsidHex")
        scan.ssid != null -> scan.ssid!!.encodeToByteArray()
        else -> ByteArray(0)
    }

    val widthCode = resolveChannelWidthCode(
        channelWidthMhz = scan.channelWidthMhz,
        centerFreq0Mhz = scan.centerFreq0Mhz,
        centerFreq1Mhz = scan.centerFreq1Mhz,
    )
    val wifiStdCode = parseWifiStandardCode(scan.wifiStandard)
    val tsMicros = scan.timestampMicros ?: (frameElapsedMs.coerceAtLeast(0L) * 1_000L)

    val ies = scan.ies.map { ie ->
        RawInformationElement(
            id = ie.id,
            idExt = ie.idExt,
            bytes = decodeStrictHex(ie.hex, "ies[id=${ie.id},idExt=${ie.idExt}].hex"),
        )
    }

    return RawScanEntry(
        bssid = MacAddressUtils.normalizeBssid(scan.bssid),
        rawSsidBytes = rawSsidBytes,
        legacySsid = scan.ssid,
        rssiDbm = scan.rssiDbm,
        frequencyMhz = scan.frequencyMhz,
        centerFreq0Mhz = scan.centerFreq0Mhz,
        centerFreq1Mhz = scan.centerFreq1Mhz,
        channelWidthCode = widthCode,
        wifiStandardCode = wifiStdCode,
        capabilities = scan.capabilities,
        securityTypes = scan.securityTypes,
        timestampMicros = tsMicros,
        is80211mcResponder = scan.is80211mcResponder,
        is80211azResponder = scan.is80211azResponder,
        apMldMacAddress = scan.apMldMacAddress,
        informationElements = ies,
    )
}

private fun resolveChannelWidthCode(
    channelWidthMhz: Int,
    centerFreq0Mhz: Int,
    centerFreq1Mhz: Int,
): Int {
    if ((channelWidthMhz == 80 && centerFreq1Mhz > 0) ||
        (channelWidthMhz == 160 && centerFreq1Mhz > 0 && abs(centerFreq1Mhz - centerFreq0Mhz) > 40)
    ) {
        return ChannelWidth.WIDTH_80_PLUS_80_MHZ.androidCode
    }
    return ChannelWidth.fromWidthMhz(channelWidthMhz, 0).androidCode.coerceAtLeast(0)
}

private fun parseWifiStandardCode(raw: String): Int {
    val norm = raw.trim().lowercase(Locale.US).replace("-", "").replace("_", "").replace("802.", "")
    return when (norm) {
        "11be", "wifi7", "be", "8" -> 8
        "11ad", "11ay", "wigig", "7" -> 7
        "11ax", "wifi6", "wifi6e", "ax", "6" -> 6
        "11ac", "wifi5", "ac", "5" -> 5
        "11n", "wifi4", "n", "4" -> 4
        "11a", "11b", "11g", "abg", "legacy", "1" -> 1
        else -> 0
    }
}

private fun decodeStrictHex(hexRaw: String, fieldLabel: String): ByteArray {
    val cleaned = buildString(hexRaw.length) {
        for (ch in hexRaw) {
            if (!ch.isWhitespace() && ch != ':' && ch != '-') {
                append(ch)
            }
        }
    }.let { s ->
        if (s.startsWith("0x", ignoreCase = true)) s.substring(2) else s
    }

    if (cleaned.isEmpty()) return ByteArray(0)
    if (cleaned.length % 2 != 0) {
        throw PayloadFormatException("Odd-length hex string in $fieldLabel: '$hexRaw'.")
    }

    val out = ByteArray(cleaned.length / 2)
    var i = 0
    while (i < cleaned.length) {
        val hi = hexDigitToInt(cleaned[i])
        val lo = hexDigitToInt(cleaned[i + 1])
        if (hi < 0 || lo < 0) {
            throw PayloadFormatException("Non-hex character in $fieldLabel: '$hexRaw'.")
        }
        out[i / 2] = ((hi shl 4) or lo).toByte()
        i += 2
    }
    return out
}

private fun hexDigitToInt(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

private fun printUsage(out: PrintStream) {
    out.println(
        """
        WIFIREDEYE Diagnostic CLI
        Usage:
          cli-runner analyze --input <path|-> --format <json|markdown> [options]

        Options:
          --input <path|->                    Input CliScenarioInput JSON file path or '-' for stdin
          --format <json|markdown>            Output diagnostic report format
          --target-bssid <AA:BB:CC:DD:EE:FF>  Optional target BSSID for proximity homing trajectory
          --env <OPEN|HOME_OFFICE|HEAVY_WALLS> Indoor propagation environment (default: HOME_OFFICE)
          --redact-macs                       Mask middle BSSID/MAC octets (AA:BB:CC:XX:XX:01)
          -h, --help                          Show this help message
        """.trimIndent()
    )
    out.flush()
}
