package com.a55.wifiradar.core.model

import kotlinx.serialization.Serializable

/**
 * Canonical inline tap-to-explain legend dictionary (`F04`) shared across `:core:designsystem`
 * and all `:feature:*` modules. Every entry provides a crisp 2-sentence plain-English explanation.
 */
@Serializable
enum class LegendTopic(
    val topicId: String,
    val shortChipLabel: String,
    val title: String,
    val plainEnglishExplanation: String,
) {
    DBM(
        topicId = "dbm",
        shortChipLabel = "dBm",
        title = "Signal Strength (dBm)",
        plainEnglishExplanation = "Decibel-milliwatts (dBm) measure wireless signal power from -30 dBm right beside the router down to -90 dBm at the faint edge of reach. Because the scale is logarithmic, every +3 dB increase roughly doubles the real wireless signal energy reaching your phone.",
    ),
    SIGNAL_QUALITY(
        topicId = "signal_quality",
        shortChipLabel = "Quality %",
        title = "Signal Quality Percentage",
        plainEnglishExplanation = "Translates raw dBm into an intuitive 0% to 100% scale where -100 dBm equals 0% and -50 dBm or stronger equals 100%. Readings above 70% (-65 dBm) sustain full-speed 4K streaming, cloud gaming, and low-latency video calls.",
    ),
    CCI_VS_ACI(
        topicId = "cci_vs_aci",
        shortChipLabel = "CCI vs ACI",
        title = "Shared Traffic (CCI) vs Overlapping Noise (ACI)",
        plainEnglishExplanation = "Co-Channel Interference (CCI) means routers sit on the exact same channel and politely take turns speaking, slowing speed only when airtime is busy. Adjacent-Channel Interference (ACI) comes from partially overlapping channels like Ch 2–5 on 2.4 GHz, causing raw mid-packet radio collisions and lag.",
    ),
    DFS_TDWR(
        topicId = "dfs_tdwr",
        shortChipLabel = "DFS / TDWR",
        title = "DFS & Weather Radar Channels",
        plainEnglishExplanation = "Dynamic Frequency Selection (DFS) unlocks clean 5 GHz channels (52–144) provided routers yield priority to aviation or TDWR weather radar (Ch 120–128). These channels are often wide open and fast, though routers listen before broadcasting and switch channels automatically if radar pulses appear.",
    ),
    PSC_6GHZ(
        topicId = "psc_6ghz",
        shortChipLabel = "PSC",
        title = "Preferred Scanning Channels (6 GHz)",
        plainEnglishExplanation = "Because the 6 GHz band spans up to 59 channels, Wi-Fi 6E and Wi-Fi 7 devices scan every fourth 20 MHz channel (Ch 5, 21, 37, 53, and up) first. Configuring a 6 GHz router on a PSC primary channel ensures phones discover and connect to it rapidly without draining battery.",
    ),
    PMF_80211W(
        topicId = "pmf_80211w",
        shortChipLabel = "PMF",
        title = "Protected Management Frames (802.11w)",
        plainEnglishExplanation = "Protected Management Frames encrypt Wi-Fi control messages so nearby attackers cannot spoof disconnect packets to knock your devices offline. PMF protection is mandatory on all WPA3 networks and available as a recommended security upgrade on modern WPA2 routers.",
    ),
    OS_SCAN_THROTTLE(
        topicId = "os_scan_throttle",
        shortChipLabel = "OS Scan Limit",
        title = "Android 4-Scans / 2-Minute Battery Guard",
        plainEnglishExplanation = "Android limits foreground apps to 4 active full-band Wi-Fi sweeps every 2 minutes while still delivering passive system scans and 300 ms connected-link updates. Power users can disable Wi-Fi scan throttling in Android Developer Options to unlock continuous 2.5-second active sweeps.",
    ),
    KALMAN_SMOOTHING(
        topicId = "kalman_smoothing",
        shortChipLabel = "Kalman Smooth",
        title = "Motion-Adaptive Signal Smoothing",
        plainEnglishExplanation = "Indoor radio waves bounce off walls and people, causing raw signal readings to jump by several dB even when your phone is held completely still. Our motion-adaptive Kalman filter calms stationary multipath flicker while responding immediately the instant you walk closer or farther away.",
    ),
    BSS_LOAD(
        topicId = "bss_load",
        shortChipLabel = "BSS Load",
        title = "Advertised Client Count & Airtime Busy %",
        plainEnglishExplanation = "Routers with 802.11k/e QBSS Load enabled broadcast how many devices are associated to that radio and what percentage of channel airtime is busy. Even a strong -45 dBm signal can feel sluggish if channel airtime utilization climbs above 70% from heavy neighbor or client traffic.",
    ),
    FAST_ROAMING_KVR(
        topicId = "fast_roaming_kvr",
        shortChipLabel = "802.11k/v/r",
        title = "Seamless Mesh Fast Roaming",
        plainEnglishExplanation = "802.11k shares a neighbor map of nearby mesh nodes while 802.11v nudges your phone to a stronger node before signal quality drops. 802.11r accelerates encryption handshakes during handoff so voice and video calls stay uninterrupted as you walk between rooms.",
    ),
    MLO_WIFI7(
        topicId = "mlo_wifi7",
        shortChipLabel = "MLO",
        title = "Wi-Fi 7 Multi-Link Operation",
        plainEnglishExplanation = "Multi-Link Operation bonds 2.4, 5, and 6 GHz radios from the same physical Wi-Fi 7 router under a single Multi-Link Device hardware address. Compatible devices can aggregate bandwidth across bands simultaneously and dodge single-channel interference with near-zero latency.",
    ),
    LAA_RANDOM_MAC(
        topicId = "laa_random_mac",
        shortChipLabel = "Virtual MAC",
        title = "Locally Administered / Virtual Address",
        plainEnglishExplanation = "Routers and phone hotspots set a special locally administered bit in their hardware address when creating guest SSIDs or multi-network virtual radios. We automatically inspect the underlying manufacturer prefix when possible so virtual mesh and guest radios remain easy to attribute.",
    ),
    PATH_LOSS_DISTANCE(
        topicId = "path_loss_distance",
        shortChipLabel = "Est. Range",
        title = "Indoor Signal Distance Estimate",
        plainEnglishExplanation = "Distance is estimated from band frequency physics and your chosen wall environment, upgrading automatically to sub-meter Time-of-Flight on Wi-Fi RTT routers. Stand 1 meter from any router with clear sightlines and tap Calibrate 1m to tune the range model to that exact transmitter.",
    ),
    BSS_COLOR(
        topicId = "bss_color",
        shortChipLabel = "BSS Color",
        title = "Wi-Fi 6 Spatial Reuse Color (1–63)",
        plainEnglishExplanation = "Wi-Fi 6 and Wi-Fi 7 tag each network with a color number from 1 to 63 so devices can safely ignore faint co-channel traffic from distant neighbors. If two nearby routers on the same channel pick the exact same number, we flag a BSS Color Collision so you can reboot or re-channel one router.",
    );

    val pillLabel: String
        get() = shortChipLabel

    val explanation: String
        get() = plainEnglishExplanation
}
