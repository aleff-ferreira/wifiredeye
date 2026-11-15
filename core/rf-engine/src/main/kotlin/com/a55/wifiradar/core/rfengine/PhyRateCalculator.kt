package com.a55.wifiradar.core.rfengine

import com.a55.wifiradar.core.model.ChannelWidth
import com.a55.wifiradar.core.model.WifiGeneration
import kotlin.math.roundToInt

/**
 * Exact IEEE 802.11 theoretical peak PHY rate calculator (`Mbps`) across
 * Legacy `11b/a/g`, `Wi-Fi 4 (11n)`, `Wi-Fi 5 (11ac)`, `Wi-Fi 6/6E (11ax)`,
 * `WiGig (11ad)`, and `Wi-Fi 7 (11be)` (`F09`).
 */
object PhyRateCalculator {

    fun computePeakPhyRateMbps(
        generation: WifiGeneration,
        width: ChannelWidth,
        streams: Int,
        sgi20: Boolean = true,
        sgi40: Boolean = true,
        supports4096Qam: Boolean = true,
    ): Int {
        val nss = streams.coerceIn(1, 8)
        return when (generation) {
            WifiGeneration.LEGACY_11B -> 11
            WifiGeneration.LEGACY_11A, WifiGeneration.LEGACY_11G, WifiGeneration.UNKNOWN -> 54
            WifiGeneration.WIGIG_11AD -> 4620
            WifiGeneration.WIFI_4 -> {
                val s = nss.coerceAtMost(4)
                val use40 = width.widthMhz >= 40
                val perStream = if (use40) {
                    if (sgi40) 150.0 else 135.0
                } else {
                    if (sgi20) 72.2 else 65.0
                }
                (perStream * s).roundToInt()
            }
            WifiGeneration.WIFI_5 -> {
                val perStream = when {
                    width.widthMhz >= 160 -> 866.6667
                    width.widthMhz >= 80 -> 433.3333
                    width.widthMhz >= 40 -> 200.0
                    nss == 3 || nss == 6 -> 96.3
                    else -> 86.7
                }
                (perStream * nss).roundToInt()
            }
            WifiGeneration.WIFI_6, WifiGeneration.WIFI_6E -> {
                val subcarriers = when {
                    width.widthMhz >= 160 -> 1960
                    width.widthMhz >= 80 -> 980
                    width.widthMhz >= 40 -> 468
                    else -> 234
                }
                ((nss * subcarriers * 50.0) / (6.0 * 13.6)).roundToInt()
            }
            WifiGeneration.WIFI_7 -> {
                val subcarriers = when {
                    width.widthMhz >= 320 -> 3920
                    width.widthMhz >= 160 -> 1960
                    width.widthMhz >= 80 -> 980
                    width.widthMhz >= 40 -> 468
                    else -> 234
                }
                val bitsPerSubcarrier = if (supports4096Qam) 10.0 else (50.0 / 6.0)
                ((nss * subcarriers * bitsPerSubcarrier) / 13.6).roundToInt()
            }
        }
    }

    fun formatMimoLabel(streams: Int): String {
        val clamped = streams.coerceIn(1, 8)
        return if (clamped == 1) "1×1 SISO" else "${clamped}×${clamped} MIMO"
    }
}
