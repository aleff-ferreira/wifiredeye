package com.a55.wifiradar.core.rfengine

import com.a55.wifiradar.core.model.AkmSuite
import com.a55.wifiradar.core.model.BeaconIeDetails
import com.a55.wifiradar.core.model.BssColorInfo
import com.a55.wifiradar.core.model.BssLoadInfo
import com.a55.wifiradar.core.model.ChannelDescriptor
import com.a55.wifiradar.core.model.CipherSuite
import com.a55.wifiradar.core.model.Colocated6GhzNeighbor
import com.a55.wifiradar.core.model.CountryInfo
import com.a55.wifiradar.core.model.EhtMloInfo
import com.a55.wifiradar.core.model.PhyCapability
import com.a55.wifiradar.core.model.RawInformationElement
import com.a55.wifiradar.core.model.RoamingCapabilities
import com.a55.wifiradar.core.model.TimDtimInfo
import com.a55.wifiradar.core.model.WifiBand
import com.a55.wifiradar.core.model.WifiGeneration
import com.a55.wifiradar.core.model.WpsDeviceInfo
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.CRC32
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Fuzz-proof, AOSP-accurate binary IEEE 802.11 Beacon / Probe-Response Information Element
 * inspector (`F09`, `F10`).
 *
 * AOSP Extended IE Invariant:
 * For `id == 255`, Android's `InformationElement` already strips `Element ID` (1B), `Length` (1B),
 * AND `Element ID Extension` (`idExt`, 1B) from `bytes`, so `bytes[0]` is byte 0 of the extended
 * element payload.
 */
object BeaconIeInspector {

    data class ParsedSuiteBlock(
        val groupCipher: CipherSuite,
        val pairwiseCiphers: List<CipherSuite>,
        val akmSuites: List<AkmSuite>,
        val mfpc: Boolean,
        val mfpr: Boolean,
        val groupMgmtCipher: CipherSuite? = null,
    )

    data class IeInspectionResult(
        val beaconDetails: BeaconIeDetails,
        val wifiGeneration: WifiGeneration,
        val rsnInfo: ParsedSuiteBlock?,
        val wpa1Info: ParsedSuiteBlock?,
        val wpsPresent: Boolean,
        val wpsUuidHex: String?,
        val rsnxSaeH2e: Boolean,
        val oweTransitionIePresent: Boolean,
        val eht320MhzActive: Boolean,
    )

    fun inspect(
        informationElements: List<RawInformationElement>,
        sourceBssid: String,
        sourceSsidDisplayName: String,
        sourceSsidBytes: ByteArray?,
        wifiStandardCode: Int,
        channelDescriptor: ChannelDescriptor,
        entryApMldMacAddress: String? = null,
        entryApMloLinkId: Int = -1,
    ): IeInspectionResult {
        var malformedCount = 0

        var sawLegacyRatesIe = false
        var maxLegacyRateMbps = 0.0
        var hasErpIe = false
        var hasSaeH2eBssMembershipSelector = false

        var timDtim: TimDtimInfo? = null
        var country: CountryInfo? = null
        var bssLoad: BssLoadInfo? = null
        var tpcTxPowerDbm: Int? = null
        var tpcLinkMarginDb: Int? = null
        var maxBssidIndicator: Int? = null

        var htSupported = false
        var htStreams = 0
        var sgi20 = true
        var sgi40 = true

        var vhtSupported = false
        var vhtStreams = 0

        var heSupported = false
        var heStreams = 0
        var bssColor: BssColorInfo? = null

        var ehtSupported = false
        var ehtStreams = 0
        var supports160Mhz = false
        var supports320Mhz = false
        var eht320MhzActive = false
        var supports4096Qam = false
        var suBeamformer = false
        var muMimoSupported = false
        var twtResponder = false

        var mldMacFromIe: String? = null
        var mloLinkIdFromIe: Int? = null
        var disabledSubchannelBitmap: Int? = null
        var isPreamblePunctured = false
        var puncturedSubchannelCount = 0

        var supports80211k = false
        var supports80211v = false
        var supports80211r = false
        var mobilityDomainIdHex: String? = null
        var overTheDsFt = false
        var supportsMbo = false
        var ftmResponder = false
        var beaconProtection = false

        var rsnInfo: ParsedSuiteBlock? = null
        var wpa1Info: ParsedSuiteBlock? = null
        var rsnxSaeH2e = false
        var oweTransitionIePresent = false

        var wpsPresent = false
        var wpsConfiguredState: Int? = null
        var wpsApSetupLocked = false
        var wpsManufacturer: String? = null
        var wpsModelName: String? = null
        var wpsModelNumber: String? = null
        var wpsDeviceName: String? = null
        var wpsUuidHex: String? = null
        var advertisedApHostname: String? = null

        val colocated6GhzNeighbors = ArrayList<Colocated6GhzNeighbor>(2)

        for (ie in informationElements) {
            val b = ie.bytes
            when (ie.id) {
                1, 50 -> {
                    if (b.isEmpty()) {
                        malformedCount++
                    } else {
                        for (idx in b.indices) {
                            val val7 = u8(b, idx) and 0x7F
                            if (val7 in 120..127) {
                                if (val7 == 123) {
                                    hasSaeH2eBssMembershipSelector = true
                                }
                            } else if (val7 in 1..119) {
                                sawLegacyRatesIe = true
                                val rateMbps = val7 * 0.5
                                if (rateMbps > maxLegacyRateMbps) {
                                    maxLegacyRateMbps = rateMbps
                                }
                            }
                        }
                    }
                }

                5 -> {
                    if (b.size < 3) {
                        malformedCount++
                    } else {
                        val dtimCount = u8(b, 0)
                        val dtimPeriod = u8(b, 1)
                        if (dtimPeriod == 0) {
                            malformedCount++
                        } else {
                            val hasMulticast = (u8(b, 2) and 0x01) != 0
                            timDtim = TimDtimInfo(
                                dtimCount = dtimCount,
                                dtimPeriod = dtimPeriod,
                                hasBufferedMulticast = hasMulticast,
                            )
                        }
                    }
                }

                7 -> {
                    if (b.size < 3) {
                        malformedCount++
                    } else {
                        val c0 = u8(b, 0)
                        val c1 = u8(b, 1)
                        if (c0 !in 0x21..0x7E || c1 !in 0x21..0x7E) {
                            malformedCount++
                        } else {
                            val code = String(byteArrayOf(b[0], b[1]), StandardCharsets.US_ASCII)
                                .uppercase(Locale.US)
                            val envByte = u8(b, 2)
                            val envStr = when (envByte) {
                                'I'.code -> "Indoor"
                                'O'.code -> "Outdoor"
                                ' '.code -> "All Environments"
                                'X'.code -> "Non-Country"
                                in 0x01..0x1F -> "Operating Table $envByte"
                                in 0x21..0x7E -> "Environment (${envByte.toChar()})"
                                else -> "All Environments"
                            }
                            country = CountryInfo(countryCode = code, regulatoryEnvironment = envStr)
                        }
                    }
                }

                11 -> {
                    if (b.size != 5) {
                        malformedCount++
                    } else {
                        val stationCount = u16LE(b, 0)
                        val utilRaw = u8(b, 2)
                        val utilPct = ((utilRaw * 100.0) / 255.0).roundToInt().coerceIn(0, 100)
                        val admissionCap = u16LE(b, 3)
                        bssLoad = BssLoadInfo(
                            stationCount = stationCount,
                            channelUtilizationRaw = utilRaw,
                            channelUtilizationPct = utilPct,
                            availableAdmissionCapacity32Us = admissionCap,
                        )
                    }
                }

                35 -> {
                    if (b.size < 2) {
                        malformedCount++
                    } else {
                        tpcTxPowerDbm = s8(b, 0)
                        tpcLinkMarginDb = s8(b, 1)
                    }
                }

                42 -> {
                    if (b.isEmpty()) {
                        malformedCount++
                    } else {
                        hasErpIe = true
                    }
                }

                45 -> {
                    if (b.size < 26) {
                        malformedCount++
                    } else {
                        htSupported = true
                        val htCapInfo = u16LE(b, 0)
                        sgi20 = (htCapInfo and 0x0020) != 0
                        sgi40 = (htCapInfo and 0x0040) != 0
                        val streams = (4 downTo 1).firstOrNull { u8(b, 2 + it) != 0 } ?: 1
                        htStreams = max(htStreams, streams)
                    }
                }

                48 -> {
                    val parsed = parseRsnIe(b)
                    if (parsed == null) {
                        malformedCount++
                    } else {
                        rsnInfo = parsed
                        if (parsed.akmSuites.any {
                                it == AkmSuite.FT_OVER_8021X ||
                                    it == AkmSuite.FT_OVER_PSK ||
                                    it == AkmSuite.FT_OVER_SAE
                            }
                        ) {
                            supports80211r = true
                        }
                    }
                }

                54 -> {
                    if (b.size < 3) {
                        malformedCount++
                    } else {
                        supports80211r = true
                        mobilityDomainIdHex = String.format(Locale.US, "%02X%02X", u8(b, 0), u8(b, 1))
                        overTheDsFt = (u8(b, 2) and 0x01) != 0
                    }
                }

                70 -> {
                    if (b.size < 5) {
                        malformedCount++
                    } else {
                        if ((0 until 5).any { u8(b, it) != 0 }) {
                            supports80211k = true
                        }
                    }
                }

                71 -> {
                    if (b.isEmpty()) {
                        malformedCount++
                    } else {
                        maxBssidIndicator = u8(b, 0)
                    }
                }

                127 -> {
                    if (b.isEmpty()) {
                        malformedCount++
                    } else {
                        if (bitInByteArray(b, 19)) supports80211v = true
                        if (bitInByteArray(b, 70)) ftmResponder = true
                        if (bitInByteArray(b, 78)) twtResponder = true
                        if (bitInByteArray(b, 84)) beaconProtection = true
                    }
                }

                133 -> {
                    if (b.isEmpty()) {
                        malformedCount++
                    } else if (b.size >= 11) {
                        val end = minOf(b.size, 26)
                        val host = cleanPrintableAscii(b, 10, end - 10)
                        if (host != null && advertisedApHostname == null) {
                            advertisedApHostname = host
                        }
                    }
                }

                191 -> {
                    if (b.size < 12) {
                        malformedCount++
                    } else {
                        vhtSupported = true
                        val vhtCap = u32LE(b, 0)
                        val chWidthSet = (vhtCap ushr 2) and 0x03
                        if (chWidthSet in 1..2 || (chWidthSet == 0 && ((vhtCap ushr 30) and 0x03) >= 1)) {
                            supports160Mhz = true
                        }
                        if ((vhtCap and (1 shl 11)) != 0) suBeamformer = true
                        if ((vhtCap and (1 shl 19)) != 0) muMimoSupported = true
                        val rxMcsMap = u16LE(b, 4)
                        vhtStreams = max(vhtStreams, maxMcsMapStreams2Bit(rxMcsMap))
                    }
                }

                201 -> {
                    if (b.size < 4) {
                        malformedCount++
                    } else {
                        val rnrMalformed = parseRnrIe(
                            b = b,
                            sourceBssid = sourceBssid,
                            sourceSsidDisplayName = sourceSsidDisplayName,
                            sourceSsidBytes = sourceSsidBytes,
                            outNeighbors = colocated6GhzNeighbors,
                        )
                        if (rnrMalformed) malformedCount++
                    }
                }

                221 -> {
                    if (b.size < 4) {
                        malformedCount++
                    } else {
                        val oui = (u8(b, 0) shl 16) or (u8(b, 1) shl 8) or u8(b, 2)
                        val type = u8(b, 3)
                        when {
                            oui == 0x0050F2 && type == 0x01 -> {
                                val parsedWpa1 = parseWpa1VendorIe(b)
                                if (parsedWpa1 == null) {
                                    malformedCount++
                                } else {
                                    wpa1Info = parsedWpa1
                                }
                            }
                            oui == 0x0050F2 && type == 0x04 -> {
                                wpsPresent = true
                                var pos = 4
                                while (pos < b.size) {
                                    if (pos + 4 > b.size) {
                                        malformedCount++
                                        break
                                    }
                                    val attrType = u16BE(b, pos)
                                    val attrLen = u16BE(b, pos + 2)
                                    val vStart = pos + 4
                                    if (vStart + attrLen > b.size) {
                                        malformedCount++
                                        break
                                    }
                                    when (attrType) {
                                        0x1044 -> if (attrLen >= 1) wpsConfiguredState = u8(b, vStart)
                                        0x1057 -> if (attrLen >= 1) wpsApSetupLocked = u8(b, vStart) == 0x01
                                        0x1021 -> cleanUtf8OrAscii(b, vStart, attrLen)?.let { wpsManufacturer = it }
                                        0x1023 -> cleanUtf8OrAscii(b, vStart, attrLen)?.let { wpsModelName = it }
                                        0x1024 -> cleanUtf8OrAscii(b, vStart, attrLen)?.let { wpsModelNumber = it }
                                        0x1011 -> cleanUtf8OrAscii(b, vStart, attrLen)?.let { wpsDeviceName = it }
                                        0x1047 -> if (attrLen == 16) {
                                            val sb = StringBuilder(32)
                                            for (k in 0 until 16) {
                                                sb.append(String.format(Locale.US, "%02X", u8(b, vStart + k)))
                                            }
                                            wpsUuidHex = sb.toString()
                                        }
                                    }
                                    pos = vStart + attrLen
                                }
                            }
                            oui == 0x506F9A -> {
                                when (type) {
                                    0x16 -> supportsMbo = true
                                    0x1C -> oweTransitionIePresent = true
                                }
                            }
                            oui == 0x004096 -> {
                                val host = extractCiscoApHostname(b, type)
                                if (host != null && advertisedApHostname == null) {
                                    advertisedApHostname = host
                                }
                            }
                            oui == 0x000B86 -> {
                                val host = extractEnterpriseAsciiHostname(b, type, isAruba = true)
                                if (host != null && advertisedApHostname == null) {
                                    advertisedApHostname = host
                                }
                            }
                            oui == 0x001392 -> {
                                val host = extractEnterpriseAsciiHostname(b, type, isAruba = false)
                                if (host != null && advertisedApHostname == null) {
                                    advertisedApHostname = host
                                }
                            }
                        }
                    }
                }

                244 -> {
                    if (b.isEmpty()) {
                        malformedCount++
                    } else {
                        if ((u8(b, 0) and 0x20) != 0) {
                            rsnxSaeH2e = true
                        }
                    }
                }

                255 -> {
                    when (ie.idExt) {
                        35 -> {
                            if (b.size < 21) {
                                malformedCount++
                            } else {
                                heSupported = true
                                if ((u8(b, 0) and 0x04) != 0) twtResponder = true
                                val chWidthSet = u8(b, 6)
                                if ((chWidthSet and 0x18) != 0) supports160Mhz = true
                                if ((u8(b, 9) and 0x80) != 0) suBeamformer = true
                                if ((u8(b, 10) and 0x02) != 0) muMimoSupported = true

                                val s80 = maxMcsMapStreams2Bit(u16LE(b, 17))
                                var nextOffset = 21
                                var s160 = 0
                                if ((chWidthSet and 0x08) != 0) {
                                    if (b.size >= nextOffset + 4) {
                                        s160 = maxMcsMapStreams2Bit(u16LE(b, nextOffset))
                                        nextOffset += 4
                                    } else {
                                        malformedCount++
                                    }
                                }
                                var s80p80 = 0
                                if ((chWidthSet and 0x10) != 0) {
                                    if (b.size >= nextOffset + 4) {
                                        s80p80 = maxMcsMapStreams2Bit(u16LE(b, nextOffset))
                                    } else {
                                        malformedCount++
                                    }
                                }
                                heStreams = max(heStreams, maxOf(s80, s160, s80p80))
                            }
                        }

                        36 -> {
                            if (b.size < 6) {
                                malformedCount++
                            } else {
                                heSupported = true
                                val colorByte = u8(b, 3)
                                val colorCode = colorByte and 0x3F
                                val isDisabled = (colorByte and 0x80) != 0 || colorCode == 0
                                bssColor = BssColorInfo(
                                    colorCode = colorCode,
                                    isDisabled = isDisabled,
                                )
                            }
                        }

                        106 -> {
                            if (b.size < 5) {
                                malformedCount++
                            } else {
                                ehtSupported = true
                                val params = u8(b, 0)
                                val opInfoPresent = (params and 0x01) != 0
                                val bitmapPresent = (params and 0x02) != 0
                                if (opInfoPresent) {
                                    if (b.size < 8) {
                                        malformedCount++
                                    } else {
                                        val ctrlWidth = u8(b, 5) and 0x07
                                        if (ctrlWidth == 4) {
                                            supports320Mhz = true
                                            eht320MhzActive = true
                                        }
                                        if (bitmapPresent) {
                                            if (b.size < 10) {
                                                malformedCount++
                                            } else {
                                                val bitmap = u16LE(b, 8)
                                                disabledSubchannelBitmap = bitmap
                                                puncturedSubchannelCount = Integer.bitCount(bitmap)
                                                isPreamblePunctured = bitmap != 0
                                            }
                                        }
                                    }
                                } else if (bitmapPresent) {
                                    if (b.size < 7) {
                                        malformedCount++
                                    } else {
                                        val bitmap = u16LE(b, 5)
                                        disabledSubchannelBitmap = bitmap
                                        puncturedSubchannelCount = Integer.bitCount(bitmap)
                                        isPreamblePunctured = bitmap != 0
                                    }
                                }
                            }
                        }

                        107 -> {
                            if (b.size < 3) {
                                malformedCount++
                            } else {
                                val mlControl = u16LE(b, 0)
                                val mlType = mlControl and 0x0007
                                if (mlType == 0) {
                                    val commonLen = u8(b, 2)
                                    if (b.size < 9 || commonLen < 6 || (commonLen >= 7 && 2 + commonLen > b.size)) {
                                        malformedCount++
                                    } else {
                                        ehtSupported = true
                                        mldMacFromIe = formatMac(b, 3)
                                        if ((mlControl and 0x0010) != 0 && b.size >= 10) {
                                            mloLinkIdFromIe = u8(b, 9) and 0x0F
                                        }
                                    }
                                }
                            }
                        }

                        108 -> {
                            if (b.size < 14) {
                                malformedCount++
                            } else {
                                ehtSupported = true
                                if ((u8(b, 2) and 0x02) != 0) supports320Mhz = true
                                if ((u8(b, 2) and 0x20) != 0) suBeamformer = true
                                if ((u8(b, 6) and 0xC0) != 0 || (u8(b, 7) and 0x01) != 0) {
                                    muMimoSupported = true
                                }
                                val mcsEnd = minOf(b.size, 20)
                                var maxNibble = 0
                                for (idx in 11 until mcsEnd) {
                                    val v = u8(b, idx)
                                    val lo = v and 0x0F
                                    val hi = (v ushr 4) and 0x0F
                                    maxNibble = maxOf(maxNibble, lo, hi)
                                }
                                ehtStreams = max(ehtStreams, maxNibble.coerceIn(1, 8))
                                val mcs1213 = when {
                                    b.size == 15 -> u8(b, 14) != 0
                                    b.size >= 20 && supports320Mhz ->
                                        u8(b, 13) != 0 || u8(b, 16) != 0 || u8(b, 19) != 0
                                    b.size >= 17 -> u8(b, 13) != 0 || u8(b, 16) != 0
                                    else -> u8(b, 13) != 0 || (u8(b, 12) == 0 && u8(b, 13) == 0)
                                }
                                if (mcs1213) supports4096Qam = true
                            }
                        }

                        else -> {
                            if (b.isEmpty()) {
                                malformedCount++
                            }
                        }
                    }
                }
            }
        }

        val resolvedGeneration = resolveWifiGeneration(
            wifiStandardCode = wifiStandardCode,
            band = channelDescriptor.band,
            primaryFreqMhz = channelDescriptor.primaryFreqMhz,
            ehtSupported = ehtSupported,
            heSupported = heSupported,
            vhtSupported = vhtSupported,
            htSupported = htSupported,
            hasErpIe = hasErpIe,
            sawLegacyRatesIe = sawLegacyRatesIe,
            maxLegacyRateMbps = maxLegacyRateMbps,
        )

        val maxStreams = maxOf(ehtStreams, heStreams, vhtStreams, htStreams, 1).coerceIn(1, 8)

        val effectiveWidthForPhy = if (eht320MhzActive && channelDescriptor.band == WifiBand.BAND_6_GHZ) {
            com.a55.wifiradar.core.model.ChannelWidth.WIDTH_320_MHZ
        } else {
            channelDescriptor.width
        }
        if (effectiveWidthForPhy.widthMhz >= 160) supports160Mhz = true
        if (effectiveWidthForPhy.widthMhz >= 320) supports320Mhz = true
        if (resolvedGeneration == WifiGeneration.WIFI_7 && !ehtSupported) {
            supports4096Qam = true
        }

        val peakPhyMbps = PhyRateCalculator.computePeakPhyRateMbps(
            generation = resolvedGeneration,
            width = effectiveWidthForPhy,
            streams = maxStreams,
            sgi20 = sgi20,
            sgi40 = sgi40,
            supports4096Qam = if (resolvedGeneration == WifiGeneration.WIFI_7) supports4096Qam else false,
        )

        val phyCapability = PhyCapability(
            wifiGeneration = resolvedGeneration,
            maxSpatialStreams = maxStreams,
            mimoLabel = PhyRateCalculator.formatMimoLabel(maxStreams),
            estimatedMaxPhyMbps = peakPhyMbps,
            htSupported = htSupported || (resolvedGeneration.rankOrder >= WifiGeneration.WIFI_4.rankOrder && channelDescriptor.band != WifiBand.BAND_6_GHZ && channelDescriptor.band != WifiBand.BAND_60_GHZ),
            vhtSupported = vhtSupported,
            heSupported = heSupported || resolvedGeneration == WifiGeneration.WIFI_6 || resolvedGeneration == WifiGeneration.WIFI_6E,
            ehtSupported = ehtSupported || resolvedGeneration == WifiGeneration.WIFI_7,
            suBeamformer = suBeamformer,
            muMimoSupported = muMimoSupported,
            twtResponder = twtResponder,
            supports160Mhz = supports160Mhz,
            supports320Mhz = supports320Mhz,
            supports4096Qam = supports4096Qam,
        )

        val effectiveMldMac = mldMacFromIe ?: entryApMldMacAddress?.takeIf { it.isNotBlank() }
        val effectiveLinkId = mloLinkIdFromIe ?: entryApMloLinkId.takeIf { it >= 0 }
        val ehtMlo = if (effectiveMldMac != null || effectiveLinkId != null || disabledSubchannelBitmap != null) {
            EhtMloInfo(
                apMldMacAddress = effectiveMldMac,
                mloLinkId = effectiveLinkId,
                disabledSubchannelBitmap = disabledSubchannelBitmap,
                isPreamblePunctured = isPreamblePunctured,
                puncturedSubchannelCount = puncturedSubchannelCount,
            )
        } else {
            null
        }

        val wpsDevice = if (wpsPresent || advertisedApHostname != null) {
            WpsDeviceInfo(
                wpsConfiguredState = wpsConfiguredState,
                isApSetupLocked = wpsApSetupLocked,
                manufacturer = wpsManufacturer,
                modelName = wpsModelName,
                modelNumber = wpsModelNumber,
                deviceName = wpsDeviceName,
                advertisedApHostname = advertisedApHostname,
            )
        } else {
            null
        }

        val roaming = RoamingCapabilities(
            supports80211k = supports80211k,
            supports80211v = supports80211v,
            supports80211r = supports80211r,
            mobilityDomainIdHex = mobilityDomainIdHex,
            overTheDsFt = overTheDsFt,
            supportsMbo = supportsMbo,
            ftmResponder = ftmResponder,
            beaconProtection = beaconProtection,
        )

        val details = BeaconIeDetails(
            bssLoad = bssLoad,
            phyCapability = phyCapability,
            bssColor = bssColor,
            ehtMlo = ehtMlo,
            colocated6GhzNeighbors = colocated6GhzNeighbors,
            wpsDevice = wpsDevice,
            roaming = roaming,
            timDtim = timDtim,
            country = country,
            tpcApTxPowerDbm = tpcTxPowerDbm,
            tpcLinkMarginDb = tpcLinkMarginDb,
            maxBssidIndicator = maxBssidIndicator,
            hasSaeH2eBssMembershipSelector = hasSaeH2eBssMembershipSelector,
            rawIeCount = informationElements.size,
            malformedIeCount = malformedCount,
        )

        return IeInspectionResult(
            beaconDetails = details,
            wifiGeneration = resolvedGeneration,
            rsnInfo = rsnInfo,
            wpa1Info = wpa1Info,
            wpsPresent = wpsPresent,
            wpsUuidHex = wpsUuidHex,
            rsnxSaeH2e = rsnxSaeH2e,
            oweTransitionIePresent = oweTransitionIePresent,
            eht320MhzActive = eht320MhzActive,
        )
    }

    private fun resolveWifiGeneration(
        wifiStandardCode: Int,
        band: WifiBand,
        primaryFreqMhz: Int,
        ehtSupported: Boolean,
        heSupported: Boolean,
        vhtSupported: Boolean,
        htSupported: Boolean,
        hasErpIe: Boolean,
        sawLegacyRatesIe: Boolean,
        maxLegacyRateMbps: Double,
    ): WifiGeneration = when {
        band == WifiBand.BAND_60_GHZ || wifiStandardCode == 7 -> WifiGeneration.WIGIG_11AD
        ehtSupported || wifiStandardCode == 8 -> WifiGeneration.WIFI_7
        heSupported || wifiStandardCode == 6 -> if (band == WifiBand.BAND_6_GHZ) {
            WifiGeneration.WIFI_6E
        } else {
            WifiGeneration.WIFI_6
        }
        vhtSupported || wifiStandardCode == 5 -> WifiGeneration.WIFI_5
        htSupported || wifiStandardCode == 4 -> WifiGeneration.WIFI_4
        band == WifiBand.BAND_6_GHZ -> WifiGeneration.WIFI_6E
        wifiStandardCode == 1 || sawLegacyRatesIe || hasErpIe -> when (band) {
            WifiBand.BAND_5_GHZ -> WifiGeneration.LEGACY_11A
            WifiBand.BAND_2_4_GHZ -> when {
                primaryFreqMhz == 2484 -> WifiGeneration.LEGACY_11B
                hasErpIe || maxLegacyRateMbps > 11.0 -> WifiGeneration.LEGACY_11G
                sawLegacyRatesIe && maxLegacyRateMbps <= 11.0 -> WifiGeneration.LEGACY_11B
                else -> WifiGeneration.LEGACY_11G
            }
            else -> if (primaryFreqMhz >= 5000) WifiGeneration.LEGACY_11A else WifiGeneration.UNKNOWN
        }
        else -> WifiGeneration.UNKNOWN
    }

    private fun parseRsnIe(b: ByteArray): ParsedSuiteBlock? {
        if (b.size < 2) return null
        val version = u16LE(b, 0)
        if (version != 1) return null
        var pos = 2

        if (pos == b.size) {
            return ParsedSuiteBlock(
                groupCipher = CipherSuite.CCMP_128,
                pairwiseCiphers = listOf(CipherSuite.CCMP_128),
                akmSuites = listOf(AkmSuite.EAP_SHA1),
                mfpc = false,
                mfpr = false,
            )
        }
        if (b.size - pos < 4) return null
        val groupCipher = parseCipherSuite(b, pos)
        pos += 4

        if (pos == b.size) {
            return ParsedSuiteBlock(
                groupCipher = groupCipher,
                pairwiseCiphers = listOf(CipherSuite.CCMP_128),
                akmSuites = listOf(AkmSuite.EAP_SHA1),
                mfpc = false,
                mfpr = false,
            )
        }
        if (b.size - pos < 2) return null
        val pCount = u16LE(b, pos)
        pos += 2
        if (pCount > (b.size - pos) / 4) return null
        val pairwise = ArrayList<CipherSuite>(pCount.coerceAtMost(8))
        for (i in 0 until pCount) {
            pairwise.add(parseCipherSuite(b, pos))
            pos += 4
        }

        if (pos == b.size) {
            return ParsedSuiteBlock(
                groupCipher = groupCipher,
                pairwiseCiphers = pairwise,
                akmSuites = listOf(AkmSuite.EAP_SHA1),
                mfpc = false,
                mfpr = false,
            )
        }
        if (b.size - pos < 2) return null
        val aCount = u16LE(b, pos)
        pos += 2
        if (aCount > (b.size - pos) / 4) return null
        val akms = ArrayList<AkmSuite>(aCount.coerceAtMost(8))
        for (i in 0 until aCount) {
            akms.add(parseAkmSuite(b, pos))
            pos += 4
        }

        var mfpc = false
        var mfpr = false
        var groupMgmt: CipherSuite? = null

        if (pos < b.size) {
            if (b.size - pos < 2) return null
            val rsnCap = u16LE(b, pos)
            pos += 2
            mfpr = (rsnCap and 0x0040) != 0
            mfpc = (rsnCap and 0x0080) != 0
        }

        if (pos < b.size) {
            if (b.size - pos < 2) return null
            val pmkidCount = u16LE(b, pos)
            pos += 2
            if (pmkidCount > (b.size - pos) / 16) return null
            pos += pmkidCount * 16
        }

        if (pos < b.size) {
            if (b.size - pos >= 4) {
                groupMgmt = parseCipherSuite(b, pos)
            } else {
                return null
            }
        }

        return ParsedSuiteBlock(
            groupCipher = groupCipher,
            pairwiseCiphers = pairwise,
            akmSuites = akms,
            mfpc = mfpc,
            mfpr = mfpr,
            groupMgmtCipher = groupMgmt,
        )
    }

    private fun parseWpa1VendorIe(b: ByteArray): ParsedSuiteBlock? {
        if (b.size < 6) return null
        var pos = 4
        val version = u16LE(b, pos)
        if (version != 1) return null
        pos += 2

        if (pos == b.size) {
            return ParsedSuiteBlock(
                groupCipher = CipherSuite.TKIP,
                pairwiseCiphers = listOf(CipherSuite.TKIP),
                akmSuites = listOf(AkmSuite.PSK),
                mfpc = false,
                mfpr = false,
            )
        }
        if (b.size - pos < 4) return null
        val groupCipher = parseCipherSuite(b, pos)
        pos += 4

        if (pos == b.size) {
            return ParsedSuiteBlock(
                groupCipher = groupCipher,
                pairwiseCiphers = listOf(CipherSuite.TKIP),
                akmSuites = listOf(AkmSuite.PSK),
                mfpc = false,
                mfpr = false,
            )
        }
        if (b.size - pos < 2) return null
        val pCount = u16LE(b, pos)
        pos += 2
        if (pCount > (b.size - pos) / 4) return null
        val pairwise = ArrayList<CipherSuite>(pCount.coerceAtMost(8))
        for (i in 0 until pCount) {
            pairwise.add(parseCipherSuite(b, pos))
            pos += 4
        }

        val akms = ArrayList<AkmSuite>(2)
        if (pos < b.size) {
            if (b.size - pos < 2) return null
            val aCount = u16LE(b, pos)
            pos += 2
            if (aCount > (b.size - pos) / 4) return null
            for (i in 0 until aCount) {
                akms.add(parseAkmSuite(b, pos))
                pos += 4
            }
        } else {
            akms.add(AkmSuite.PSK)
        }

        return ParsedSuiteBlock(
            groupCipher = groupCipher,
            pairwiseCiphers = pairwise,
            akmSuites = akms,
            mfpc = false,
            mfpr = false,
        )
    }

    private fun parseCipherSuite(b: ByteArray, pos: Int): CipherSuite {
        val oui = (u8(b, pos) shl 16) or (u8(b, pos + 1) shl 8) or u8(b, pos + 2)
        val type = u8(b, pos + 3)
        return when (oui) {
            0x000FAC -> when (type) {
                1 -> CipherSuite.WEP_40
                2 -> CipherSuite.TKIP
                4 -> CipherSuite.CCMP_128
                5 -> CipherSuite.WEP_104
                6 -> CipherSuite.BIP_CMAC_128
                8 -> CipherSuite.GCMP_128
                9 -> CipherSuite.GCMP_256
                10 -> CipherSuite.CCMP_256
                11, 12 -> CipherSuite.BIP_GMAC_256
                else -> CipherSuite.UNKNOWN
            }
            0x0050F2 -> when (type) {
                1 -> CipherSuite.WEP_40
                2 -> CipherSuite.TKIP
                4 -> CipherSuite.CCMP_128
                5 -> CipherSuite.WEP_104
                else -> CipherSuite.UNKNOWN
            }
            else -> CipherSuite.UNKNOWN
        }
    }

    private fun parseAkmSuite(b: ByteArray, pos: Int): AkmSuite {
        val oui = (u8(b, pos) shl 16) or (u8(b, pos + 1) shl 8) or u8(b, pos + 2)
        val type = u8(b, pos + 3)
        return when (oui) {
            0x000FAC -> when (type) {
                1 -> AkmSuite.EAP_SHA1
                2 -> AkmSuite.PSK
                3 -> AkmSuite.FT_OVER_8021X
                4 -> AkmSuite.FT_OVER_PSK
                5, 11 -> AkmSuite.EAP_SHA256
                6 -> AkmSuite.PSK_SHA256
                8, 24 -> AkmSuite.SAE
                9, 25 -> AkmSuite.FT_OVER_SAE
                12 -> AkmSuite.SUITE_B_EAP_SHA384
                18 -> AkmSuite.OWE
                else -> AkmSuite.UNKNOWN
            }
            0x0050F2 -> when (type) {
                1 -> AkmSuite.EAP_SHA1
                2 -> AkmSuite.PSK
                else -> AkmSuite.UNKNOWN
            }
            0x506F9A -> when (type) {
                1 -> AkmSuite.EAP_SHA256
                2 -> AkmSuite.SAE
                else -> AkmSuite.UNKNOWN
            }
            else -> AkmSuite.UNKNOWN
        }
    }

    private fun parseRnrIe(
        b: ByteArray,
        sourceBssid: String,
        sourceSsidDisplayName: String,
        sourceSsidBytes: ByteArray?,
        outNeighbors: MutableList<Colocated6GhzNeighbor>,
    ): Boolean {
        var pos = 0
        val sourceShortSsid = if (sourceSsidBytes != null && sourceSsidBytes.isNotEmpty()) {
            crc32Ieee(sourceSsidBytes)
        } else {
            null
        }

        while (pos < b.size) {
            if (b.size - pos < 4) return true
            val header0 = u8(b, pos)
            val tbttInfoFieldType = header0 and 0x03
            val tbttInfoCount = ((header0 ushr 4) and 0x0F) + 1
            val tbttLen = u8(b, pos + 1)
            val opClass = u8(b, pos + 2)
            val channelNum = u8(b, pos + 3)

            if (tbttLen < 1 || tbttInfoCount > (b.size - pos - 4) / tbttLen) {
                return true
            }
            val blockBytes = tbttInfoCount * tbttLen

            if (opClass in 131..137 && tbttInfoFieldType == 0) {
                val widthMhz = when (opClass) {
                    131, 136 -> 20
                    132 -> 40
                    133 -> 80
                    134, 135 -> 160
                    137 -> 320
                    else -> 20
                }
                val centerFreqMhz = when {
                    channelNum == 2 -> 5935
                    channelNum in 1..233 -> 5950 + 5 * channelNum
                    else -> 0
                }
                val isPsc = channelNum in 5..229 && (channelNum - 5) % 16 == 0

                for (i in 0 until tbttInfoCount) {
                    val e = pos + 4 + i * tbttLen
                    var neighborBssid: String? = null
                    var shortSsid: Int? = null
                    var bssParams: Int? = null

                    when (tbttLen) {
                        1 -> Unit
                        2 -> bssParams = u8(b, e + 1)
                        5 -> shortSsid = u32LE(b, e + 1)
                        6 -> {
                            shortSsid = u32LE(b, e + 1)
                            bssParams = u8(b, e + 5)
                        }
                        7 -> neighborBssid = formatMac(b, e + 1)
                        8, 9 -> {
                            neighborBssid = formatMac(b, e + 1)
                            bssParams = u8(b, e + 7)
                        }
                        10 -> neighborBssid = formatMac(b, e + 1)
                        11 -> {
                            neighborBssid = formatMac(b, e + 1)
                            shortSsid = u32LE(b, e + 7)
                        }
                        12 -> {
                            neighborBssid = formatMac(b, e + 1)
                            shortSsid = u32LE(b, e + 7)
                            bssParams = u8(b, e + 11)
                        }
                        else -> if (tbttLen >= 13) {
                            neighborBssid = formatMac(b, e + 1)
                            shortSsid = u32LE(b, e + 7)
                            bssParams = u8(b, e + 11)
                        }
                    }

                    val isSameSsid = when {
                        bssParams != null -> (bssParams and 0x02) != 0
                        shortSsid != null && sourceShortSsid != null -> shortSsid == sourceShortSsid
                        else -> false
                    }
                    val isColocated = if (bssParams != null) {
                        (bssParams and 0x40) != 0
                    } else {
                        true
                    }

                    outNeighbors.add(
                        Colocated6GhzNeighbor(
                            sourceBssid = sourceBssid,
                            sourceSsid = sourceSsidDisplayName,
                            operatingClass = opClass,
                            channelNumber = channelNum,
                            centerFreqMhz = centerFreqMhz,
                            widthMhz = widthMhz,
                            neighborBssid = neighborBssid,
                            isSameSsid = isSameSsid,
                            isColocatedAp = isColocated,
                            isPscChannel = isPsc,
                        )
                    )
                }
            }

            pos += 4 + blockBytes
        }
        return false
    }

    private fun extractCiscoApHostname(b: ByteArray, type: Int): String? {
        if (b.size >= 12 && (type == 0x85 || type == 0x00 || b.size >= 26)) {
            val end = minOf(b.size, 26)
            val fromOffset10 = cleanPrintableAscii(b, 10, end - 10)
            if (fromOffset10 != null && fromOffset10.length >= 2) {
                return fromOffset10
            }
        }
        if ((type == 0x85 || type == 0x00) && b.size in 6..38) {
            val fallback = cleanPrintableAscii(b, 4, b.size - 4)
            if (fallback != null && fallback.length >= 2) {
                return fallback
            }
        }
        return null
    }

    private fun extractEnterpriseAsciiHostname(b: ByteArray, type: Int, isAruba: Boolean): String? {
        if (b.size < 6) return null
        val startOffset = when {
            isAruba && type == 0x01 && b.size >= 7 && u8(b, 4) == 0x03 -> 6
            !isAruba && type == 0x03 && b.size >= 6 -> 5
            b.size >= 7 && u8(b, 4) in 0..16 && u8(b, 5) in 0x21..0x7E -> 5
            else -> 4
        }
        if (startOffset >= b.size) return null
        val extracted = cleanPrintableAscii(b, startOffset, b.size - startOffset)
        return extracted?.takeIf { it.length >= 2 }
    }

    private fun maxMcsMapStreams2Bit(mcsMap16: Int): Int =
        (8 downTo 1).firstOrNull { nss ->
            ((mcsMap16 ushr ((nss - 1) * 2)) and 0x03) != 3
        } ?: 1

    private fun crc32Ieee(bytes: ByteArray): Int {
        val crc = CRC32()
        crc.update(bytes)
        return crc.value.toInt()
    }

    private fun formatMac(b: ByteArray, offset: Int): String = String.format(
        Locale.US,
        "%02X:%02X:%02X:%02X:%02X:%02X",
        u8(b, offset),
        u8(b, offset + 1),
        u8(b, offset + 2),
        u8(b, offset + 3),
        u8(b, offset + 4),
        u8(b, offset + 5),
    )

    private fun cleanUtf8OrAscii(b: ByteArray, offset: Int, len: Int): String? {
        if (len <= 0 || offset < 0 || offset + len > b.size) return null
        var actualLen = len
        while (actualLen > 0 && b[offset + actualLen - 1] == 0.toByte()) {
            actualLen--
        }
        if (actualLen <= 0) return null
        val raw = String(b, offset, actualLen, StandardCharsets.UTF_8)
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            if (ch.code >= 0x20 && ch != '\uFFFD') {
                sb.append(ch)
            }
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun cleanPrintableAscii(b: ByteArray, offset: Int, len: Int): String? {
        if (len <= 0 || offset < 0 || offset + len > b.size) return null
        val sb = StringBuilder(len)
        for (i in offset until (offset + len)) {
            val v = u8(b, i)
            if (v == 0) break
            if (v in 0x20..0x7E) {
                sb.append(v.toChar())
            } else if (sb.isNotEmpty()) {
                break
            }
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun u8(b: ByteArray, idx: Int): Int = b[idx].toInt() and 0xFF

    private fun s8(b: ByteArray, idx: Int): Int = b[idx].toInt()

    private fun u16LE(b: ByteArray, idx: Int): Int =
        (b[idx].toInt() and 0xFF) or ((b[idx + 1].toInt() and 0xFF) shl 8)

    private fun u16BE(b: ByteArray, idx: Int): Int =
        ((b[idx].toInt() and 0xFF) shl 8) or (b[idx + 1].toInt() and 0xFF)

    private fun u32LE(b: ByteArray, idx: Int): Int =
        (b[idx].toInt() and 0xFF) or
            ((b[idx + 1].toInt() and 0xFF) shl 8) or
            ((b[idx + 2].toInt() and 0xFF) shl 16) or
            ((b[idx + 3].toInt() and 0xFF) shl 24)

    private fun bitInByteArray(b: ByteArray, bitIndex: Int): Boolean {
        val byteIdx = bitIndex ushr 3
        return byteIdx < b.size && (b[byteIdx].toInt() and (1 shl (bitIndex and 7))) != 0
    }
}
