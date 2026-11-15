package com.a55.wifiradar.cli

import com.a55.wifiradar.core.model.DiagnosticExportDocument
import com.a55.wifiradar.core.model.DistanceMethod
import com.a55.wifiradar.core.model.EnvironmentHealthState
import com.a55.wifiradar.core.model.SemanticFormatters
import com.a55.wifiradar.core.model.SignalLockStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

class CliRunnerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun invokeCli(
        vararg args: String,
        stdinText: String = "",
    ): Triple<Int, String, String> {
        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val exitCode = runCli(
            args = arrayOf(*args),
            stdin = ByteArrayInputStream(stdinText.toByteArray(StandardCharsets.UTF_8)),
            stdout = PrintStream(outBytes, true, StandardCharsets.UTF_8),
            stderr = PrintStream(errBytes, true, StandardCharsets.UTF_8),
        )
        return Triple(
            exitCode,
            outBytes.toString(StandardCharsets.UTF_8),
            errBytes.toString(StandardCharsets.UTF_8),
        )
    }

    @Test
    fun analyzeFromFile_jsonFormat_parsesIesComputesHealthAndRoundTripsDocument() {
        val scenarioFile = tempFolder.newFile("scenario.json")
        scenarioFile.writeText(
            """
            {
              "deviceModel": "SM-A556B",
              "deviceHas6Ghz": false,
              "bssidNicknames": {
                "04:92:26:00:00:01": "Living Room Wi-Fi 6"
              },
              "frames": [
                {
                  "elapsedMs": 1000,
                  "connectedBssid": "04:92:26:00:00:01",
                  "scans": [
                    {
                      "ssid": "GalaxyA55_Lab_5G",
                      "rawSsidHex": null,
                      "bssid": "04:92:26:00:00:01",
                      "rssiDbm": -52,
                      "frequencyMhz": 5180,
                      "centerFreq0Mhz": 5210,
                      "centerFreq1Mhz": 0,
                      "channelWidthMhz": 80,
                      "wifiStandard": "11ax",
                      "capabilities": "[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]",
                      "ies": [
                        { "id": 11, "idExt": 0, "hex": "0c00400000" }
                      ]
                    },
                    {
                      "ssid": "Neighbor_Ch1",
                      "bssid": "50:c7:bf:11:22:01",
                      "rssiDbm": -66,
                      "frequencyMhz": 2412,
                      "channelWidthMhz": 20,
                      "wifiStandard": "11n",
                      "capabilities": "[WPA2-PSK-CCMP][ESS]"
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )

        val (code, stdout, stderr) = invokeCli(
            "analyze",
            "--input",
            scenarioFile.absolutePath,
            "--format",
            "json",
        )
        assertEquals("Unexpected stderr: $stderr", 0, code)
        assertTrue(stderr.isEmpty())

        val doc = json.decodeFromString<DiagnosticExportDocument>(stdout)
        assertFalse(doc.redactedMacs)
        assertEquals("SM-A556B", doc.deviceCapabilities.deviceModel)
        assertFalse(doc.deviceCapabilities.has6Ghz)
        assertEquals(EnvironmentHealthState.SCORED, doc.healthReport.state)
        assertNotNull(doc.healthReport.overallScore0To100)
        assertEquals(2, doc.detectedRadios.size)

        val labRadio = doc.detectedRadios.first { it.bssid == "04:92:26:00:00:01" }
        assertEquals("Living Room Wi-Fi 6", labRadio.effectiveDisplayName)
        assertEquals(12, labRadio.beaconDetails.bssLoad?.stationCount)
        assertEquals(25, labRadio.beaconDetails.bssLoad?.channelUtilizationPct)
        assertEquals(1, doc.proximityTrajectory.size)
        assertEquals(SignalLockStatus.LOCKED, doc.proximityTrajectory[0].lockStatus)
    }

    @Test
    fun analyzeFromStdin_multiFrameWalkDropoutRttAndRedaction_rendersCleanJsonAndMarkdown() {
        val multiFrameJson = """
            {
              "deviceHas6Ghz": false,
              "frames": [
                {
                  "elapsedMs": 1000,
                  "connectedBssid": "04:92:26:aa:bb:01",
                  "scans": [
                    {
                      "ssid": "HomeMesh",
                      "bssid": "04:92:26:aa:bb:01",
                      "rssiDbm": -74,
                      "frequencyMhz": 5180,
                      "centerFreq0Mhz": 5210,
                      "channelWidthMhz": 80,
                      "wifiStandard": "11ax",
                      "capabilities": "[RSN-SAE-CCMP][MFPR][ESS]",
                      "is80211mcResponder": true
                    }
                  ]
                },
                {
                  "elapsedMs": 3000,
                  "connectedBssid": "04:92:26:aa:bb:01",
                  "rttDistanceMm": 3420,
                  "rttStdDevMm": 300,
                  "scans": [
                    {
                      "ssid": "HomeMesh",
                      "bssid": "04:92:26:aa:bb:01",
                      "rssiDbm": -55,
                      "frequencyMhz": 5180,
                      "centerFreq0Mhz": 5210,
                      "channelWidthMhz": 80,
                      "wifiStandard": "11ax",
                      "capabilities": "[RSN-SAE-CCMP][MFPR][ESS]",
                      "is80211mcResponder": true
                    }
                  ]
                },
                {
                  "elapsedMs": 10000,
                  "scans": []
                },
                {
                  "elapsedMs": 15000,
                  "connectedBssid": "50:c7:bf:cc:dd:02",
                  "scans": [
                    {
                      "ssid": "HomeMesh",
                      "bssid": "50:c7:bf:cc:dd:02",
                      "rssiDbm": -60,
                      "frequencyMhz": 2437,
                      "channelWidthMhz": 20,
                      "wifiStandard": "11ax",
                      "capabilities": "[RSN-SAE-CCMP][MFPR][ESS]"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        // 1. Redacted JSON via stdin
        val (jsonCode, jsonOut, jsonErr) = invokeCli(
            "analyze",
            "--input=-",
            "--format=json",
            "--target-bssid",
            "04:92:26:aa:bb:01",
            "--env",
            "HEAVY_WALLS",
            "--redact-macs",
            stdinText = multiFrameJson,
        )
        assertEquals("Unexpected stderr: $jsonErr", 0, jsonCode)
        val doc = json.decodeFromString<DiagnosticExportDocument>(jsonOut)
        assertTrue(doc.redactedMacs)
        assertEquals(4, doc.proximityTrajectory.size)
        assertEquals(DistanceMethod.RTT_80211MC, doc.proximityTrajectory[1].distanceEstimate?.method)
        assertEquals(SignalLockStatus.STALE, doc.proximityTrajectory[2].lockStatus)
        assertEquals(SignalLockStatus.SIGNAL_LOST, doc.proximityTrajectory[3].lockStatus)

        assertFalse(jsonOut.contains("04:92:26:AA:BB:01", ignoreCase = true))
        assertFalse(jsonOut.contains("50:C7:BF:CC:DD:02", ignoreCase = true))
        assertFalse(jsonOut.contains("AA:BB", ignoreCase = true))
        assertFalse(jsonOut.contains("CC:DD", ignoreCase = true))
        assertTrue(jsonOut.contains("04:92:26:XX:XX:01"))

        // 2. Redacted Markdown via stdin
        val (mdCode, mdOut, mdErr) = invokeCli(
            "analyze",
            "--input",
            "-",
            "--format",
            "markdown",
            "--target-bssid",
            "04:92:26:aa:bb:01",
            "--redact-macs",
            stdinText = multiFrameJson,
        )
        assertEquals("Unexpected stderr: $mdErr", 0, mdCode)
        assertTrue(mdOut.contains("# Wi-Fi Radar Diagnostic Report"))
        assertTrue(mdOut.contains("## Best Channel Recommendations"))
        assertTrue(mdOut.contains("## Proximity Homing Trajectory"))
        assertFalse(mdOut.contains("04:92:26:AA:BB:01", ignoreCase = true))
        assertFalse(SemanticFormatters.containsRawEnumLeak(mdOut))
    }

    @Test
    fun cliErrorHandling_returnsNonZeroExitCodeWithDescriptiveStderrAndEmptyStdout() {
        val errorCases = listOf(
            emptyArray<String>() to "",
            arrayOf("unknown-cmd") to "",
            arrayOf("analyze", "--input", "/nonexistent/path/missing.json", "--format", "json") to "",
            arrayOf("analyze", "--input", "-", "--format", "xml") to "{}",
            arrayOf("analyze", "--input", "-", "--format", "json", "--target-bssid", "bad-mac") to "{}",
            arrayOf("analyze", "--input", "-", "--format", "json", "--env", "MARS") to "{}",
            arrayOf("analyze", "--input", "-", "--format", "json") to "   ",
            arrayOf("analyze", "--input", "-", "--format", "json") to "{not valid json",
            arrayOf("analyze", "--input", "-", "--format", "json") to """
                {"frames":[{"elapsedMs":1000,"scans":[{"bssid":"04:92:26:00:00:01","rssiDbm":-50,"frequencyMhz":5180,"ies":[{"id":11,"hex":"ZZZ"}]}]}]}
            """.trimIndent(),
        )

        for ((args, stdinPayload) in errorCases) {
            val (code, stdout, stderr) = invokeCli(*args, stdinText = stdinPayload)
            assertNotEquals("Expected non-zero exit code for args=${args.toList()}", 0, code)
            assertTrue("Expected empty stdout on error for args=${args.toList()}, got: $stdout", stdout.isEmpty())
            assertTrue("Expected descriptive stderr for args=${args.toList()}", stderr.isNotBlank())
        }

        // Verify --help returns 0
        val (helpCode, helpOut, _) = invokeCli("--help")
        assertEquals(0, helpCode)
        assertTrue(helpOut.contains("Usage:"))
    }
}
