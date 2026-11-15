package com.a55.wifiradar.e2e.fixtures

import com.a55.wifiradar.e2e.harness.CliScenarioBuilder.IeSpec

/**
 * Python-verified IEEE 802.11-2020 / 802.11ax / 802.11be / AOSP-stripped binary Information
 * Element (IE) byte vectors (`survey_2` ground truth).
 *
 * CRITICAL AOSP CONTRACT:
 * For `id = 255` (`EID_EXTENSION`), Android `ScanResult.InformationElement` strips the leading
 * `idExt` byte out of `bytes` into `idExt` (`bytes[0]` is immediately the payload after `idExt`).
 */
object BeaconIeVectors {

    // --- IE 1 & IE 50: Supported Rates & BSS Membership Selectors (0x80 | code) ---
    /** 6, 12, 24 Mbps + BSS Membership Selector 123 (0xFB = 0x80|123 = SAE_H2E_ONLY). Max rate = 24 Mbps! */
    val RATES_OFDM_WITH_SAE_H2E_ONLY = IeSpec(id = 1, hex = "8c1830fb")

    /** 36, 48, 54 Mbps + HT(127->0xFF), VHT(126->0xFE), HE(122->0xFA). Max legacy rate = 54 Mbps! */
    val EXT_RATES_54M_WITH_HT_VHT_HE_SELECTORS = IeSpec(id = 50, hex = "48606cfffefa")

    // --- IE 5: TIM (DTIM Count, DTIM Period, Bitmap Control, Partial Virtual Bitmap) ---
    val TIM_DTIM_3_MULTICAST = IeSpec(id = 5, hex = "01030100")
    val TIM_DTIM_1_UNICAST = IeSpec(id = 5, hex = "00010000")

    // --- IE 7: Country ("DE" + 'I' indoor = 0x49, firstCh=36, numCh=4, maxTx=23dBm) ---
    val COUNTRY_DE_INDOOR = IeSpec(id = 7, hex = "444549240417")
    val COUNTRY_US_OUTDOOR = IeSpec(id = 7, hex = "55534f24041e")

    // --- IE 11: QBSS Load (5 bytes: uint16 LE stationCount, uint8 util/255, uint16 LE cap) ---
    val BSS_LOAD_12_CLIENTS_25_PCT = IeSpec(id = 11, hex = "0c00401027")
    val BSS_LOAD_45_CLIENTS_78_PCT = IeSpec(id = 11, hex = "2d00c7e803")
    val BSS_LOAD_0_CLIENTS_0_PCT = IeSpec(id = 11, hex = "000000127a")
    val BSS_LOAD_MAX_65535_100_PCT = IeSpec(id = 11, hex = "ffffff0000")

    // --- IE 35: TPC Report (int8 txPowerDbm, int8 linkMarginDb) ---
    val TPC_REPORT_20_DBM = IeSpec(id = 35, hex = "1400")
    val TPC_REPORT_14_DBM = IeSpec(id = 35, hex = "0e03")
    val TPC_REPORT_11_DBM = IeSpec(id = 35, hex = "0b02")
    val TPC_REPORT_NEG_5_DBM = IeSpec(id = 35, hex = "fb00") // -5 dBm signed!

    // --- IE 45: HT Capabilities (26 bytes: 40MHz + SGI20/40 + 2SS Rx MCS ff,ff,00...) ---
    val HT_CAP_2X2_40MHZ = IeSpec(
        id = 45,
        hex = "6e001bffff000000000000000000000100000000000000000000",
    )

    // --- IE 48: RSN (WPA2 / WPA3 / OWE / Enterprise / PMF) ---
    val RSN_WPA3_SAE_MFPR = IeSpec(
        id = 48,
        hex = "0100000fac040100000fac040100000fac08c000000fac06",
    )
    val RSN_WPA2_WPA3_TRANSITION_MFPC = IeSpec(
        id = 48,
        hex = "0100000fac040100000fac040200000fac02000fac088000000fac06",
    )
    val RSN_WPA2_PSK_CCMP_NO_PMF = IeSpec(
        id = 48,
        hex = "0100000fac040100000fac040100000fac020000",
    )
    val RSN_WPA2_TKIP_LEGACY = IeSpec(
        id = 48,
        hex = "0100000fac020100000fac020100000fac020000",
    )
    val RSN_OWE_ENHANCED_OPEN_MFPR = IeSpec(
        id = 48,
        hex = "0100000fac040100000fac040100000fac12c000000fac06",
    )
    val RSN_SUITE_B_192_GCMP256_MFPR = IeSpec(
        id = 48,
        hex = "0100000fac090100000fac090100000fac0cc000000fac0c",
    )

    // --- IE 54: Mobility Domain (802.11r Fast BSS Transition) ---
    val MOBILITY_DOMAIN_80211R = IeSpec(id = 54, hex = "a1b201")

    // --- IE 70: RM Enabled Capabilities (802.11k Neighbor Report bit 1 = 0x02) ---
    val RM_ENABLED_80211K = IeSpec(id = 70, hex = "0200000000")

    // --- IE 71: Multiple BSSID (maxBSSIDIndicator = 3 -> 2^3 = 8 virtual BSSIDs) ---
    val MULTIPLE_BSSID_IND_3 = IeSpec(id = 71, hex = "03")

    // --- IE 127: Extended Capabilities (11 bytes) ---
    val EXT_CAP_11V_UTF8_FTM_TWT_BEACONPROT = IeSpec(
        id = 127,
        hex = "0000080000000100404010",
    )
    val EXT_CAP_SHORT_2B = IeSpec(id = 127, hex = "0000")

    // --- IE 191: VHT Capabilities (12 bytes: 160MHz + SGI80/160 + SU/MU Beamformer + 4SS MCS-9) ---
    val VHT_CAP_4X4_160MHZ_MUMIMO = IeSpec(
        id = 191,
        hex = "64008803aaff0000aaff0000",
    )

    // --- IE 201: Reduced Neighbor Report (RNR — Out-of-Band 6 GHz Discovery on Dual-Band A55 5G!) ---
    /** opClass=133 (6GHz 80MHz), ch=37 (PSC), bssid=aa:bb:cc:6e:00:01, sameSsid+colocated (0x50). */
    val RNR_6GHZ_PSC37_80MHZ_13B = IeSpec(
        id = 201,
        hex = "000d852500aabbcc6e00011122334450fe",
    )

    /** opClass=134 (6GHz 160MHz), ch=37 (PSC), bssid=04:d4:c4:6e:37:01, sameSsid+colocated. */
    val RNR_6GHZ_ASUS_PSC37_160MHZ = IeSpec(
        id = 201,
        hex = "000d86250004d4c46e37011122334450fe",
    )

    /** opClass=131 (6GHz 20MHz), ch=5 (PSC), 7-byte TBTT entry, bssid=aa:bb:cc:6e:00:02. */
    val RNR_6GHZ_PSC5_20MHZ_7B = IeSpec(
        id = 201,
        hex = "0007830500aabbcc6e0002",
    )

    /** opClass=137 (6GHz 320MHz Wi-Fi 7), ch=69 (PSC), 13-byte TBTT entry, bssid=aa:bb:cc:6e:00:03. */
    val RNR_6GHZ_PSC69_320MHZ_13B = IeSpec(
        id = 201,
        hex = "000d894500aabbcc6e0003aabbccdd50fe",
    )

    /** Non-6GHz opClass=115 (5GHz Ch 36) — MUST NOT be misclassified as a 6 GHz neighbor! */
    val RNR_5GHZ_NON6GHZ_OP115 = IeSpec(
        id = 201,
        hex = "0007732400aabbcc500001",
    )

    // --- IE 221: Vendor-Specific (WPS, WPA1, MBO, Cisco CCX, Aruba) ---
    val VENDOR_WPS_ASUS_AX86U = IeSpec(
        id = 221,
        hex = "0050f2041044000102102100074153555354654b1023000c52542d41583836552050726f" +
            "10240004332e30301011000c4c6976696e67526f6f6d4158",
    )
    val VENDOR_WPS_TPLINK_AX73 = IeSpec(
        id = 221,
        hex = "0050f20410440001021021000754502d4c696e6b1023000b4172636865722041583733",
    )
    val VENDOR_WPA1_LEGACY_TKIP = IeSpec(
        id = 221,
        hex = "0050f20101000050f20201000050f20201000050f202",
    )
    val VENDOR_MBO_OCE = IeSpec(id = 221, hex = "506f9a16010100")
    val VENDOR_CISCO_CCX_AP_NAME = IeSpec(
        id = 221,
        hex = "0040960000000000000048512d33462d415031320000000000000c",
    )
    val VENDOR_ARUBA_AP_NAME = IeSpec(
        id = 221,
        hex = "000b8601030041525542412d3633352d4531",
    )

    // --- IE 244: RSNX (SAE H2E bit 5 = 0x20) ---
    val RSNX_SAE_H2E = IeSpec(id = 244, hex = "20")

    // --- IE 255 + Extension IEs (AOSP idExt-STRIPPED PAYLOADS!) ---
    val HE_CAP_2X2_80MHZ_TWT = IeSpec(
        id = 255,
        idExt = 35,
        hex = "0400000000000200000800000000000000fa3ffa3f",
    )
    val HE_CAP_4X4_160MHZ_TWT = IeSpec(
        id = 255,
        idExt = 35,
        hex = "0400000000000600000800000000000000aa3faa3faa3faa3f",
    )
    val HE_CAP_8X8_80MHZ = IeSpec(
        id = 255,
        idExt = 35,
        hex = "0400000000000200000800000000000000aaaaaaaa",
    )
    val HE_CAP_ALL_UNSUPPORTED_MCS = IeSpec(
        id = 255,
        idExt = 35,
        hex = "0000000000000200000000000000000000ffffffff",
    )

    val HE_OP_BSS_COLOR_42 = IeSpec(id = 255, idExt = 36, hex = "0000002afcff")
    val HE_OP_BSS_COLOR_9 = IeSpec(id = 255, idExt = 36, hex = "00000009fcff")
    val HE_OP_BSS_COLOR_DISABLED = IeSpec(id = 255, idExt = 36, hex = "000000aafcff")

    val EHT_CAP_4X4_320MHZ = IeSpec(
        id = 255,
        idExt = 108,
        hex = "00000200000000000000444444444444444444",
    )
    val EHT_OP_320MHZ_PUNCTURED_SUB2 = IeSpec(
        id = 255,
        idExt = 106,
        hex = "0300000000041f0400",
    )
    val MLO_BASIC_MLD_MAC = IeSpec(
        id = 255,
        idExt = 107,
        hex = "10000baabbcc77889901",
    )

    // --- Adversarial / Malformed / Truncated Fuzz IEs (Tier 2 & F34) ---
    val EMPTY_0B_BSS_LOAD = IeSpec(id = 11, hex = "")
    val TRUNCATED_4B_BSS_LOAD = IeSpec(id = 11, hex = "05008000")
    val TRUNCATED_10B_HT_CAP = IeSpec(id = 45, hex = "6e001bffff0000000000")
    val RSN_TRUNCATED_MID_CIPHER = IeSpec(id = 48, hex = "0100000fac")
    val RSN_OVERFLOW_COUNT_500 = IeSpec(id = 48, hex = "0100000fac04f401000fac04")
    val RNR_TRUNCATED_TBTT_ENTRY = IeSpec(id = 201, hex = "000d852500aabbcc")
    val WPS_OVERFLOW_TLV_LEN = IeSpec(id = 221, hex = "0050f204102300ff52542d4158")
    val TRUNCATED_20B_HE_CAP = IeSpec(id = 255, idExt = 35, hex = "0400000000000200000800000000000000fa3ffa")
    val MLO_TRUNCATED_5B = IeSpec(id = 255, idExt = 107, hex = "100005aabb")
    val EHT_OP_MISSING_BITMAP_BYTES = IeSpec(id = 255, idExt = 106, hex = "0300000000041f")
}
