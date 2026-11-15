package com.a55.wifiradar.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class DecodedSsid(
    val displayName: String,
    val rawUtf8OrNull: String? = null,
    val rawHex: String? = null,
    val isHidden: Boolean = false,
    val isNullBytePadded: Boolean = false,
    val isUtf8: Boolean = true,
    val byteLength: Int = 0,
    val isRevealedFromConnection: Boolean = false,
) {
    companion object {
        const val HIDDEN_NETWORK_LABEL: String = "Hidden Network"
    }
}

@Immutable
@Serializable
data class VendorInfo(
    val ouiPrefix: String? = null,
    val vendorName: String? = null,
    val displayLabel: String = "Unknown Vendor",
    val isLocallyAdministered: Boolean = false,
    val inferredParentVendor: String? = null,
    val isRedactedPlaceholder: Boolean = false,
)

@Immutable
@Serializable
data class BssLoadInfo(
    val stationCount: Int,
    val channelUtilizationRaw: Int,
    val channelUtilizationPct: Int,
    val availableAdmissionCapacity32Us: Int,
)

@Immutable
@Serializable
data class PhyCapability(
    val wifiGeneration: WifiGeneration,
    val maxSpatialStreams: Int = 1,
    val mimoLabel: String = "1×1 SISO",
    val estimatedMaxPhyMbps: Int = 54,
    val htSupported: Boolean = false,
    val vhtSupported: Boolean = false,
    val heSupported: Boolean = false,
    val ehtSupported: Boolean = false,
    val suBeamformer: Boolean = false,
    val muMimoSupported: Boolean = false,
    val twtResponder: Boolean = false,
    val supports160Mhz: Boolean = false,
    val supports320Mhz: Boolean = false,
    val supports4096Qam: Boolean = false,
)

@Immutable
@Serializable
data class BssColorInfo(
    val colorCode: Int,
    val isDisabled: Boolean = false,
    val hasCollisionOnChannel: Boolean = false,
    val collidingBssids: List<String> = emptyList(),
)

@Immutable
@Serializable
data class EhtMloInfo(
    val apMldMacAddress: String? = null,
    val mloLinkId: Int? = null,
    val disabledSubchannelBitmap: Int? = null,
    val isPreamblePunctured: Boolean = false,
    val puncturedSubchannelCount: Int = 0,
)

@Immutable
@Serializable
data class Colocated6GhzNeighbor(
    val sourceBssid: String,
    val sourceSsid: String,
    val operatingClass: Int,
    val channelNumber: Int,
    val centerFreqMhz: Int,
    val widthMhz: Int,
    val neighborBssid: String? = null,
    val isSameSsid: Boolean = false,
    val isColocatedAp: Boolean = true,
    val isPscChannel: Boolean = false,
)

@Immutable
@Serializable
data class WpsDeviceInfo(
    val wpsConfiguredState: Int? = null,
    val isApSetupLocked: Boolean = false,
    val manufacturer: String? = null,
    val modelName: String? = null,
    val modelNumber: String? = null,
    val deviceName: String? = null,
    val advertisedApHostname: String? = null,
)

@Immutable
@Serializable
data class RoamingCapabilities(
    val supports80211k: Boolean = false,
    val supports80211v: Boolean = false,
    val supports80211r: Boolean = false,
    val mobilityDomainIdHex: String? = null,
    val overTheDsFt: Boolean = false,
    val supportsMbo: Boolean = false,
    val ftmResponder: Boolean = false,
    val beaconProtection: Boolean = false,
) {
    val hasAnyFastRoaming: Boolean
        get() = supports80211k || supports80211v || supports80211r
}

@Immutable
@Serializable
data class TimDtimInfo(
    val dtimCount: Int,
    val dtimPeriod: Int,
    val hasBufferedMulticast: Boolean = false,
)

@Immutable
@Serializable
data class CountryInfo(
    val countryCode: String,
    val regulatoryEnvironment: String,
)

@Immutable
@Serializable
data class BeaconIeDetails(
    val bssLoad: BssLoadInfo? = null,
    val phyCapability: PhyCapability,
    val bssColor: BssColorInfo? = null,
    val ehtMlo: EhtMloInfo? = null,
    val colocated6GhzNeighbors: List<Colocated6GhzNeighbor> = emptyList(),
    val wpsDevice: WpsDeviceInfo? = null,
    val roaming: RoamingCapabilities = RoamingCapabilities(),
    val timDtim: TimDtimInfo? = null,
    val country: CountryInfo? = null,
    val tpcApTxPowerDbm: Int? = null,
    val tpcLinkMarginDb: Int? = null,
    val maxBssidIndicator: Int? = null,
    val hasSaeH2eBssMembershipSelector: Boolean = false,
    val rawIeCount: Int = 0,
    val malformedIeCount: Int = 0,
)

@Serializable
enum class SecurityGrade(
    val badgeText: String,
    val plainEnglishTier: String,
    val isWeakOrInsecure: Boolean,
) {
    A_PLUS("A+", "Maximum Modern Security", false),
    A("A", "Strong Modern Security", false),
    A_MINUS("A-", "Strong Transitional Security", false),
    B("B", "Good Standard Security", false),
    C("C", "Acceptable · Minor Hardening Advised", false),
    D("D", "Weak Legacy Encryption", true),
    F("F", "Insecure / Unprotected", true),
}

@Serializable
enum class SecurityProtocol(val displayLabel: String, val plainEnglishSummary: String) {
    WPA3_ENTERPRISE_192("WPA3-Enterprise 192-bit", "Top-Tier Enterprise Encryption"),
    WPA3_ENTERPRISE("WPA3-Enterprise", "Modern Enterprise 802.1X Security"),
    WPA3_SAE("WPA3-Personal", "Strong Modern Password Protection"),
    WPA2_WPA3_TRANSITION("WPA2 / WPA3 Transitional", "Modern Security + Older Device Support"),
    OWE_ENHANCED_OPEN("Enhanced Open (OWE)", "Encrypted Public Wi-Fi (No Password)"),
    WPA2_ENTERPRISE("WPA2-Enterprise", "Corporate 802.1X Login Security"),
    WPA2_PSK("WPA2-Personal", "Standard Home Wi-Fi Security"),
    WPA1_WPA2_MIXED("WPA / WPA2 Mixed", "Legacy Compatibility Mode — Upgrade to WPA2/WPA3"),
    WPA1_LEGACY("WPA1 (Legacy)", "Outdated Encryption — Vulnerable"),
    WEP("WEP (Broken)", "Obsolete Encryption — Easily Cracked"),
    OPEN("Open Network", "Unencrypted — Traffic Visible Nearby"),
    UNKNOWN("Unknown Security", "Unrecognized Security Configuration"),
}

@Serializable
enum class SecurityFamily {
    OPEN_FAMILY,
    OWE_FAMILY,
    WEP_FAMILY,
    WPA_PERSONAL_FAMILY,
    WPA_ENTERPRISE_FAMILY,
    UNKNOWN_FAMILY,
}

@Serializable
enum class PmfState(val displayLabel: String, val plainEnglishNote: String) {
    REQUIRED("PMF Required (802.11w)", "Protected against disconnect/spoofing attacks"),
    CAPABLE_OPTIONAL("PMF Optional (802.11w)", "Supports management frame protection"),
    DISABLED("PMF Off", "Management frames unprotected"),
    NOT_APPLICABLE("N/A", "Not supported by open/legacy protocol"),
}

@Serializable
enum class CipherSuite(val displayLabel: String) {
    GCMP_256("GCMP-256"),
    GCMP_128("GCMP-128"),
    CCMP_256("CCMP-256"),
    CCMP_128("AES-CCMP"),
    TKIP("TKIP (Weak)"),
    WEP_104("WEP-104"),
    WEP_40("WEP-40"),
    BIP_CMAC_128("BIP-CMAC-128"),
    BIP_GMAC_256("BIP-GMAC-256"),
    NONE("None"),
    UNKNOWN("Unknown Cipher"),
}

@Serializable
enum class AkmSuite(val displayLabel: String) {
    SAE("WPA3-SAE"),
    FT_OVER_SAE("FT-SAE (802.11r)"),
    OWE("Enhanced Open (OWE)"),
    SUITE_B_EAP_SHA384("Suite-B 192-bit EAP"),
    EAP_SHA256("802.1X EAP-SHA256"),
    EAP_SHA1("802.1X EAP"),
    FT_OVER_8021X("FT-802.1X (802.11r)"),
    PSK_SHA256("WPA2-PSK-SHA256"),
    PSK("WPA2-PSK"),
    FT_OVER_PSK("FT-PSK (802.11r)"),
    NONE("Open"),
    UNKNOWN("Unknown AKM"),
}

@Immutable
@Serializable
data class SecurityAssessment(
    val grade: SecurityGrade,
    val score0To100: Int,
    val protocol: SecurityProtocol,
    val securityFamily: SecurityFamily,
    val groupCipher: CipherSuite = CipherSuite.UNKNOWN,
    val pairwiseCiphers: List<CipherSuite> = emptyList(),
    val akmSuites: List<AkmSuite> = emptyList(),
    val pmfState: PmfState = PmfState.NOT_APPLICABLE,
    val wpsEnabled: Boolean = false,
    val isTransitionMode: Boolean = false,
    val saeH2eSupported: Boolean = false,
    val saeH2eRequired: Boolean = false,
    val beaconProtection: Boolean = false,
    val isEvilTwinSuspect: Boolean = false,
    val plainEnglishLabel: String,
    val warnings: List<String> = emptyList(),
    val rawCapabilities: String = "",
)
