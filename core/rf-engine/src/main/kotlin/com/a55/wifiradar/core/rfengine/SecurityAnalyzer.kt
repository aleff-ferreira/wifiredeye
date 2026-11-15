package com.a55.wifiradar.core.rfengine

import com.a55.wifiradar.core.model.AkmSuite
import com.a55.wifiradar.core.model.BssidRadio
import com.a55.wifiradar.core.model.CipherSuite
import com.a55.wifiradar.core.model.EvilTwinAlert
import com.a55.wifiradar.core.model.PmfState
import com.a55.wifiradar.core.model.SecurityAssessment
import com.a55.wifiradar.core.model.SecurityFamily
import com.a55.wifiradar.core.model.SecurityGrade
import com.a55.wifiradar.core.model.SecurityProtocol
import java.util.Locale

/**
 * Multi-source Wi-Fi security posture analyzer (`0–100` / `A+`..`F`) and cross-cluster
 * Open-downgrade Evil-Twin detector (`F11`).
 */
object SecurityAnalyzer {

    private val WPA1_CAP_REGEX = Regex("""\[WPA-(PSK|EAP)""")
    private val WPA2_RSN_CAP_REGEX = Regex("""\[(WPA2|RSN|WPA3)-""")
    private val PURE_OWE_TOKEN_REGEX = Regex("""\[(RSN-)?OWE(-CCMP|-GCMP|-256)?\]""")

    fun assess(
        capabilities: String,
        securityTypes: List<Int>,
        ieResult: BeaconIeInspector.IeInspectionResult,
    ): SecurityAssessment {
        val caps = capabilities.uppercase(Locale.US)
        val rsn = ieResult.rsnInfo
        val wpa1 = ieResult.wpa1Info

        val wpsEnabled = ieResult.wpsPresent || caps.contains("[WPS]")
        val saeH2eRequired = ieResult.beaconDetails.hasSaeH2eBssMembershipSelector
        val saeH2eSupported = ieResult.rsnxSaeH2e || saeH2eRequired || caps.contains("SAE-H2E")
        val beaconProtection = ieResult.beaconDetails.roaming.beaconProtection
        val oweTransition = ieResult.oweTransitionIePresent ||
            caps.contains("OWE_TRANSITION") ||
            (0 in securityTypes && 6 in securityTypes)

        val akms = LinkedHashSet<AkmSuite>()
        val pairwise = LinkedHashSet<CipherSuite>()

        if (rsn != null) {
            akms.addAll(rsn.akmSuites)
            pairwise.addAll(rsn.pairwiseCiphers)
        }
        if (wpa1 != null) {
            akms.addAll(wpa1.akmSuites)
            pairwise.addAll(wpa1.pairwiseCiphers)
        }

        if (rsn == null && wpa1 == null) {
            inferAkmsAndCiphersFromStringsAndCodes(
                caps = caps,
                securityTypes = securityTypes,
                outAkms = akms,
                outPairwise = pairwise,
            )
        }

        val hasWpa1 = wpa1 != null || WPA1_CAP_REGEX.containsMatchIn(caps)
        val hasWpa2OrRsn = rsn != null ||
            WPA2_RSN_CAP_REGEX.containsMatchIn(caps) ||
            PURE_OWE_TOKEN_REGEX.containsMatchIn(caps) ||
            securityTypes.any { it in setOf(2, 3, 4, 5, 6, 9, 11, 12) }

        val hasSuiteB192 = AkmSuite.SUITE_B_EAP_SHA384 in akms ||
            5 in securityTypes ||
            caps.contains("SUITE_B_192") ||
            caps.contains("SUITE-B-192")
        val hasSae = AkmSuite.SAE in akms || AkmSuite.FT_OVER_SAE in akms || 4 in securityTypes
        val hasPsk = AkmSuite.PSK in akms ||
            AkmSuite.FT_OVER_PSK in akms ||
            AkmSuite.PSK_SHA256 in akms ||
            2 in securityTypes
        val hasEnterprise = AkmSuite.EAP_SHA1 in akms ||
            AkmSuite.EAP_SHA256 in akms ||
            AkmSuite.FT_OVER_8021X in akms ||
            securityTypes.any { it in setOf(3, 9, 11, 12) }
        val hasOwe = AkmSuite.OWE in akms || 6 in securityTypes || PURE_OWE_TOKEN_REGEX.containsMatchIn(caps)
        val hasWep = 1 in securityTypes ||
            caps.contains("[WEP]") ||
            CipherSuite.WEP_40 in pairwise ||
            CipherSuite.WEP_104 in pairwise

        val wpa3EnterpriseHint = 9 in securityTypes ||
            caps.contains("WPA3-EAP") ||
            (rsn?.mfpr == true && AkmSuite.EAP_SHA256 in akms)

        val protocol: SecurityProtocol
        val family: SecurityFamily
        var isTransitionMode = false

        when {
            hasSuiteB192 -> {
                protocol = SecurityProtocol.WPA3_ENTERPRISE_192
                family = SecurityFamily.WPA_ENTERPRISE_FAMILY
            }
            hasEnterprise && wpa3EnterpriseHint -> {
                protocol = SecurityProtocol.WPA3_ENTERPRISE
                family = SecurityFamily.WPA_ENTERPRISE_FAMILY
            }
            hasSae && hasPsk -> {
                protocol = SecurityProtocol.WPA2_WPA3_TRANSITION
                family = SecurityFamily.WPA_PERSONAL_FAMILY
                isTransitionMode = true
            }
            hasSae -> {
                protocol = SecurityProtocol.WPA3_SAE
                family = SecurityFamily.WPA_PERSONAL_FAMILY
            }
            hasOwe -> {
                protocol = SecurityProtocol.OWE_ENHANCED_OPEN
                family = SecurityFamily.OWE_FAMILY
                isTransitionMode = oweTransition
            }
            hasEnterprise -> {
                protocol = SecurityProtocol.WPA2_ENTERPRISE
                family = SecurityFamily.WPA_ENTERPRISE_FAMILY
                isTransitionMode = hasWpa1
            }
            hasPsk -> {
                family = SecurityFamily.WPA_PERSONAL_FAMILY
                if (hasWpa1 && hasWpa2OrRsn) {
                    protocol = SecurityProtocol.WPA1_WPA2_MIXED
                    isTransitionMode = true
                } else if (hasWpa1) {
                    protocol = SecurityProtocol.WPA1_LEGACY
                } else {
                    protocol = SecurityProtocol.WPA2_PSK
                }
            }
            hasWep -> {
                protocol = SecurityProtocol.WEP
                family = SecurityFamily.WEP_FAMILY
            }
            isOpenCapabilities(caps, securityTypes) -> {
                protocol = SecurityProtocol.OPEN
                family = SecurityFamily.OPEN_FAMILY
                isTransitionMode = oweTransition
                if (akms.isEmpty()) akms.add(AkmSuite.NONE)
                if (pairwise.isEmpty()) pairwise.add(CipherSuite.NONE)
            }
            else -> {
                protocol = SecurityProtocol.UNKNOWN
                family = SecurityFamily.UNKNOWN_FAMILY
            }
        }

        val groupCipher = when {
            rsn != null -> rsn.groupCipher
            wpa1 != null -> wpa1.groupCipher
            protocol == SecurityProtocol.OPEN -> CipherSuite.NONE
            protocol == SecurityProtocol.WEP -> CipherSuite.WEP_104
            CipherSuite.TKIP in pairwise -> CipherSuite.TKIP
            pairwise.isNotEmpty() -> pairwise.first()
            else -> CipherSuite.UNKNOWN
        }

        val pmfState = resolvePmfState(
            protocol = protocol,
            rsn = rsn,
            caps = caps,
        )

        val pairwiseList = pairwise.toList()
        val akmList = akms.toList()

        val score0To100 = computeSecurityScore(
            protocol = protocol,
            pairwiseCiphers = pairwiseList,
            groupCipher = groupCipher,
            pmfState = pmfState,
            wpsEnabled = wpsEnabled,
            isTransitionMode = isTransitionMode,
            saeH2eSupported = saeH2eSupported,
            saeH2eRequired = saeH2eRequired,
            beaconProtection = beaconProtection,
        )

        val grade = computeSecurityGrade(
            protocol = protocol,
            pairwiseCiphers = pairwiseList,
            score0To100 = score0To100,
        )

        val warnings = buildSecurityWarnings(
            protocol = protocol,
            family = family,
            pairwiseCiphers = pairwiseList,
            pmfState = pmfState,
            wpsEnabled = wpsEnabled,
        )

        val plainEnglishLabel = "${protocol.displayLabel} · ${protocol.plainEnglishSummary}"

        return SecurityAssessment(
            grade = grade,
            score0To100 = score0To100,
            protocol = protocol,
            securityFamily = family,
            groupCipher = groupCipher,
            pairwiseCiphers = pairwiseList,
            akmSuites = akmList,
            pmfState = pmfState,
            wpsEnabled = wpsEnabled,
            isTransitionMode = isTransitionMode,
            saeH2eSupported = saeH2eSupported,
            saeH2eRequired = saeH2eRequired,
            beaconProtection = beaconProtection,
            isEvilTwinSuspect = false,
            plainEnglishLabel = plainEnglishLabel,
            warnings = warnings,
            rawCapabilities = capabilities,
        )
    }

    /**
     * Cross-cluster Open downgrade Evil-Twin detector (`F11`).
     * Returns `(updatedRadios, evilTwinAlerts)` with `isEvilTwinSuspect = true` marked on rogue clones.
     */
    fun detectEvilTwinsAndAnnotate(radios: List<BssidRadio>): Pair<List<BssidRadio>, List<EvilTwinAlert>> {
        if (radios.isEmpty()) return emptyList<BssidRadio>() to emptyList()

        val visibleBySsid = radios
            .filter { !it.ssid.isHidden || it.ssid.isRevealedFromConnection }
            .groupBy { it.ssid.displayName }

        val alerts = ArrayList<EvilTwinAlert>()
        val suspectBssidToSsid = HashMap<String, String>()

        for ((ssidName, ssidRadios) in visibleBySsid.entries.sortedBy { it.key }) {
            val protectedRadios = ssidRadios.filter {
                it.security.securityFamily == SecurityFamily.WPA_PERSONAL_FAMILY ||
                    it.security.securityFamily == SecurityFamily.WPA_ENTERPRISE_FAMILY ||
                    it.security.securityFamily == SecurityFamily.WEP_FAMILY
            }
            if (protectedRadios.isEmpty()) continue

            val protectedClusterIds = protectedRadios.mapTo(HashSet()) { it.physicalClusterId }
            val suspectOpenRadios = ssidRadios.filter { radio ->
                radio.security.protocol == SecurityProtocol.OPEN &&
                    !radio.security.isTransitionMode &&
                    radio.physicalClusterId !in protectedClusterIds
            }
            if (suspectOpenRadios.isEmpty()) continue

            val legitProtocol = protectedRadios.maxBy { it.security.score0To100 }.security.protocol
            val strongestSuspect = suspectOpenRadios.maxWith(
                compareBy<BssidRadio> { it.rssiDbm }.thenBy { it.bssid }
            )
            val suspectBssidsSorted = suspectOpenRadios.map { it.bssid }.sorted()
            val legitBssidsSorted = protectedRadios.map { it.bssid }.sorted()

            alerts.add(
                EvilTwinAlert(
                    ssid = ssidName,
                    legitimateBssids = legitBssidsSorted,
                    suspectOpenBssids = suspectBssidsSorted,
                    legitimateProtocol = legitProtocol,
                    suspectRssiDbm = strongestSuspect.rssiDbm,
                    plainEnglishWarning = "Unencrypted Open clone of \"$ssidName\" detected on a separate transmitter (${strongestSuspect.bssid} at ${strongestSuspect.rssiDbm} dBm) while the protected network uses ${legitProtocol.displayLabel}. Avoid connecting to the open signal.",
                )
            )
            for (suspect in suspectOpenRadios) {
                suspectBssidToSsid[suspect.bssid] = ssidName
            }
        }

        if (suspectBssidToSsid.isEmpty()) {
            return radios to alerts
        }

        val updatedRadios = radios.map { radio ->
            val clonedSsid = suspectBssidToSsid[radio.bssid]
            if (clonedSsid == null) {
                radio
            } else {
                val extraWarning = "Evil-Twin risk: unencrypted Open clone of protected network \"$clonedSsid\"."
                val mergedWarnings = if (extraWarning in radio.security.warnings) {
                    radio.security.warnings
                } else {
                    radio.security.warnings + extraWarning
                }
                radio.copy(
                    security = radio.security.copy(
                        grade = SecurityGrade.F,
                        score0To100 = 0,
                        isEvilTwinSuspect = true,
                        warnings = mergedWarnings,
                    )
                )
            }
        }
        return updatedRadios to alerts
    }

    private fun inferAkmsAndCiphersFromStringsAndCodes(
        caps: String,
        securityTypes: List<Int>,
        outAkms: MutableSet<AkmSuite>,
        outPairwise: MutableSet<CipherSuite>,
    ) {
        if (5 in securityTypes || caps.contains("SUITE_B_192") || caps.contains("SUITE-B-192")) {
            outAkms.add(AkmSuite.SUITE_B_EAP_SHA384)
            outPairwise.add(CipherSuite.GCMP_256)
        }
        if (4 in securityTypes || caps.contains("SAE")) {
            if (caps.contains("FT/SAE") || caps.contains("FT-SAE")) {
                outAkms.add(AkmSuite.FT_OVER_SAE)
            } else {
                outAkms.add(AkmSuite.SAE)
            }
        }
        if (6 in securityTypes || PURE_OWE_TOKEN_REGEX.containsMatchIn(caps)) {
            outAkms.add(AkmSuite.OWE)
        }
        if (9 in securityTypes || caps.contains("WPA3-EAP")) {
            outAkms.add(AkmSuite.EAP_SHA256)
        } else if (securityTypes.any { it in setOf(3, 11, 12) } || caps.contains("EAP") || caps.contains("802.1X")) {
            if (caps.contains("FT/EAP") || caps.contains("FT-EAP")) {
                outAkms.add(AkmSuite.FT_OVER_8021X)
            } else if (caps.contains("SHA256")) {
                outAkms.add(AkmSuite.EAP_SHA256)
            } else {
                outAkms.add(AkmSuite.EAP_SHA1)
            }
        }
        if (2 in securityTypes || caps.contains("PSK")) {
            if (caps.contains("FT/PSK") || caps.contains("FT-PSK")) {
                outAkms.add(AkmSuite.FT_OVER_PSK)
            } else if (caps.contains("PSK-SHA256")) {
                outAkms.add(AkmSuite.PSK_SHA256)
            } else {
                outAkms.add(AkmSuite.PSK)
            }
        }

        if (caps.contains("GCMP-256") || caps.contains("GCMP_256")) outPairwise.add(CipherSuite.GCMP_256)
        else if (caps.contains("GCMP")) outPairwise.add(CipherSuite.GCMP_128)
        if (caps.contains("CCMP-256") || caps.contains("CCMP_256")) outPairwise.add(CipherSuite.CCMP_256)
        else if (caps.contains("CCMP")) outPairwise.add(CipherSuite.CCMP_128)
        if (caps.contains("TKIP")) outPairwise.add(CipherSuite.TKIP)
        if (1 in securityTypes || caps.contains("[WEP]")) outPairwise.add(CipherSuite.WEP_104)

        if (outPairwise.isEmpty() && outAkms.isNotEmpty()) {
            outPairwise.add(CipherSuite.CCMP_128)
        }
    }

    private fun isOpenCapabilities(caps: String, securityTypes: List<Int>): Boolean {
        if (securityTypes.isNotEmpty() && securityTypes != listOf(0)) return false
        val stripped = caps
            .replace("[ESS]", "")
            .replace("[IBSS]", "")
            .replace("[WPS]", "")
            .replace("[UTF-8]", "")
            .replace("[P2P]", "")
            .replace("[OWE_TRANSITION]", "")
            .trim()
        return stripped.isEmpty()
    }

    private fun resolvePmfState(
        protocol: SecurityProtocol,
        rsn: BeaconIeInspector.ParsedSuiteBlock?,
        caps: String,
    ): PmfState {
        if (protocol == SecurityProtocol.OPEN ||
            protocol == SecurityProtocol.WEP ||
            protocol == SecurityProtocol.WPA1_LEGACY
        ) {
            return PmfState.NOT_APPLICABLE
        }
        if (rsn != null) {
            return when {
                rsn.mfpr -> PmfState.REQUIRED
                rsn.mfpc -> PmfState.CAPABLE_OPTIONAL
                else -> PmfState.DISABLED
            }
        }
        return when {
            caps.contains("[MFPR]") -> PmfState.REQUIRED
            caps.contains("[MFPC]") -> PmfState.CAPABLE_OPTIONAL
            protocol == SecurityProtocol.WPA3_ENTERPRISE_192 ||
                protocol == SecurityProtocol.WPA3_ENTERPRISE ||
                protocol == SecurityProtocol.WPA3_SAE ||
                protocol == SecurityProtocol.OWE_ENHANCED_OPEN -> PmfState.REQUIRED
            protocol == SecurityProtocol.WPA2_WPA3_TRANSITION -> PmfState.CAPABLE_OPTIONAL
            else -> PmfState.DISABLED
        }
    }

    private fun computeSecurityScore(
        protocol: SecurityProtocol,
        pairwiseCiphers: List<CipherSuite>,
        groupCipher: CipherSuite,
        pmfState: PmfState,
        wpsEnabled: Boolean,
        isTransitionMode: Boolean,
        saeH2eSupported: Boolean,
        saeH2eRequired: Boolean,
        beaconProtection: Boolean,
    ): Int {
        val hasTkip = CipherSuite.TKIP in pairwiseCiphers ||
            (protocol == SecurityProtocol.WPA1_WPA2_MIXED && groupCipher == CipherSuite.TKIP)

        val raw = when (protocol) {
            SecurityProtocol.WPA3_ENTERPRISE_192 -> 99 + (if (beaconProtection) 1 else 0)
            SecurityProtocol.WPA3_ENTERPRISE -> {
                var s = 95
                if (pmfState != PmfState.REQUIRED) s -= 5
                if (beaconProtection) s += 3
                s
            }
            SecurityProtocol.WPA3_SAE -> {
                var s = 95
                if (saeH2eSupported || saeH2eRequired) s += 3
                if (beaconProtection) s += 2
                if (pmfState != PmfState.REQUIRED) s -= 6
                if (wpsEnabled) s -= 25
                if (hasTkip) s -= 45
                s
            }
            SecurityProtocol.OWE_ENHANCED_OPEN -> {
                var s = 88
                if (pmfState == PmfState.REQUIRED) s += 2
                if (isTransitionMode) s -= 4
                s
            }
            SecurityProtocol.WPA2_WPA3_TRANSITION -> {
                var s = 84
                if (saeH2eSupported) s += 2
                if (beaconProtection) s += 1
                if (wpsEnabled) s -= 18
                if (hasTkip) s -= 38
                s
            }
            SecurityProtocol.WPA2_ENTERPRISE -> {
                var s = 78
                if (pmfState == PmfState.REQUIRED) s += 10
                else if (pmfState == PmfState.CAPABLE_OPTIONAL) s += 2
                if (hasTkip) s -= 34
                s
            }
            SecurityProtocol.WPA2_PSK -> {
                var s = 76
                if (pmfState == PmfState.REQUIRED) s += 12
                else if (pmfState == PmfState.CAPABLE_OPTIONAL) s += 3
                if (wpsEnabled) s -= 12
                if (hasTkip) s -= 32
                s
            }
            SecurityProtocol.WPA1_WPA2_MIXED -> {
                if (hasTkip) {
                    42 - (if (wpsEnabled) 4 else 0)
                } else {
                    62 - (if (wpsEnabled) 6 else 0)
                }
            }
            SecurityProtocol.WPA1_LEGACY -> 38 - (if (wpsEnabled) 4 else 0)
            SecurityProtocol.WEP -> 15
            SecurityProtocol.OPEN -> 0
            SecurityProtocol.UNKNOWN -> 25
        }
        return raw.coerceIn(0, 100)
    }

    private fun computeSecurityGrade(
        protocol: SecurityProtocol,
        pairwiseCiphers: List<CipherSuite>,
        score0To100: Int,
    ): SecurityGrade = when {
        protocol == SecurityProtocol.OPEN || protocol == SecurityProtocol.WEP -> SecurityGrade.F
        CipherSuite.TKIP in pairwiseCiphers || protocol == SecurityProtocol.WPA1_LEGACY -> SecurityGrade.D
        score0To100 >= 93 -> SecurityGrade.A_PLUS
        score0To100 >= 87 -> SecurityGrade.A
        score0To100 >= 80 -> SecurityGrade.A_MINUS
        score0To100 >= 70 -> SecurityGrade.B
        score0To100 >= 55 -> SecurityGrade.C
        score0To100 >= 30 -> SecurityGrade.D
        else -> SecurityGrade.F
    }

    private fun buildSecurityWarnings(
        protocol: SecurityProtocol,
        family: SecurityFamily,
        pairwiseCiphers: List<CipherSuite>,
        pmfState: PmfState,
        wpsEnabled: Boolean,
    ): List<String> = buildList {
        when (protocol) {
            SecurityProtocol.OPEN ->
                add("Unencrypted open network — wireless traffic can be read by anyone nearby.")
            SecurityProtocol.WEP ->
                add("Broken WEP encryption — network key can be recovered in minutes.")
            SecurityProtocol.WPA1_LEGACY ->
                add("Legacy WPA1 encryption enabled — vulnerable to packet decryption and downgrade attacks; switch router to WPA2/WPA3 AES-CCMP.")
            else -> if (CipherSuite.TKIP in pairwiseCiphers) {
                add("Legacy TKIP cipher enabled — vulnerable to packet decryption and downgrade attacks; switch router to WPA2/WPA3 AES-CCMP.")
            }
        }
        if (wpsEnabled) {
            add("WPS PIN setup is active — disable WPS in router settings to block offline PIN brute-force attacks.")
        }
        if (pmfState == PmfState.DISABLED &&
            (family == SecurityFamily.WPA_PERSONAL_FAMILY || family == SecurityFamily.WPA_ENTERPRISE_FAMILY)
        ) {
            add("Management frame protection (802.11w PMF) is off — vulnerable to spoofed disconnect packets.")
        }
    }
}
