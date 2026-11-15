package com.a55.wifiradar.core.wifiplatform

import android.net.MacAddress
import android.net.wifi.MloLink
import android.net.wifi.ScanResult
import android.net.wifi.WifiSsid
import com.a55.wifiradar.core.model.WifiBand
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowScanResult
import org.robolectric.shadows.WifiScanResultBuilder
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanResultExtractorAndCacheTest {

    @Test
    fun extractsFullApi34ScanResultIncludingReadOnlyIeBuffersMloAndHiddenBytes() {
        val bssLoadPayload = byteArrayOf(0x0E, 0x00, 0x48, 0x00, 0x00)
        val heCapPayload = ByteArray(21) { (it + 1).toByte() }
        val ie11 = ScanResult.InformationElement(11, 0, bssLoadPayload)
        val ie255Ext35 = ScanResult.InformationElement(255, 35, heCapPayload)

        val nonUtf8Bytes = byteArrayOf(0x80.toByte(), 0xFE.toByte(), 0x1A)
        val sr = WifiScanResultBuilder()
            .setWifiSsid(WifiSsid.fromBytes(nonUtf8Bytes))
            .setSsid("Fallback_5G")
            .setBssid("aa-bb-cc-dd-ee-01")
            .setCapabilities("[WPA2-PSK-CCMP][RSN-SAE-CCMP][ESS]")
            .setRssi(-53)
            .setFrequency(5180)
            .setChannelWidth(ScanResult.CHANNEL_WIDTH_80MHZ)
            .setCenterFreq0(5210)
            .setIs80211McRttResponder(true)
            .setInformationElements(listOf(ie11, ie255Ext35))
            .build()
        sr.timestamp = 25_000_000L
        @Suppress("DEPRECATION")
        sr.venueName = "Conference Hall"
        @Suppress("DEPRECATION")
        sr.operatorFriendlyName = "Enterprise OpenRoaming"

        val mloLink = MloLink().apply {
            ReflectionHelpers.callInstanceMethod<Unit>(
                this,
                "setLinkId",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 2),
            )
            ReflectionHelpers.callInstanceMethod<Unit>(
                this,
                "setBand",
                ReflectionHelpers.ClassParameter.from(
                    Int::class.javaPrimitiveType,
                    ScanResult.WIFI_BAND_6_GHZ,
                ),
            )
            ReflectionHelpers.callInstanceMethod<Unit>(
                this,
                "setChannel",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 37),
            )
            ReflectionHelpers.callInstanceMethod<Unit>(
                this,
                "setApMacAddress",
                ReflectionHelpers.ClassParameter.from(
                    MacAddress::class.java,
                    MacAddress.fromString("aa:bb:cc:dd:ee:06"),
                ),
            )
            ReflectionHelpers.callInstanceMethod<Unit>(
                this,
                "setState",
                ReflectionHelpers.ClassParameter.from(
                    Int::class.javaPrimitiveType,
                    MloLink.MLO_LINK_STATE_ACTIVE,
                ),
            )
            ReflectionHelpers.callInstanceMethod<Unit>(
                this,
                "setRssi",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, -49),
            )
            ReflectionHelpers.callInstanceMethod<Unit>(
                this,
                "setTxLinkSpeedMbps",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 2402),
            )
            ReflectionHelpers.callInstanceMethod<Unit>(
                this,
                "setRxLinkSpeedMbps",
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 1921),
            )
        }
        ReflectionHelpers.setField(sr, "mApMldMacAddress", MacAddress.fromString("aa:bb:cc:dd:ee:00"))
        ReflectionHelpers.setField(sr, "mApMloLinkId", 1)
        ReflectionHelpers.setField(sr, "mAffiliatedMloLinks", listOf(mloLink))

        val entry = ScanResultExtractor.extract(sr, fallbackTimestampMicros = 1_000_000L, sdkInt = 34)
        assertNotNull(entry)
        entry!!

        assertEquals("AA:BB:CC:DD:EE:01", entry.bssid)
        assertArrayEquals(nonUtf8Bytes, entry.rawSsidBytes)
        assertEquals("Fallback_5G", entry.legacySsid)
        assertEquals(-53, entry.rssiDbm)
        assertEquals(5180, entry.frequencyMhz)
        assertEquals(5210, entry.centerFreq0Mhz)
        assertEquals(ScanResult.CHANNEL_WIDTH_80MHZ, entry.channelWidthCode)
        assertEquals(25_000_000L, entry.timestampMicros)
        assertTrue(entry.is80211mcResponder)
        assertEquals("AA:BB:CC:DD:EE:00", entry.apMldMacAddress)
        assertEquals(1, entry.apMloLinkId)
        assertEquals("Conference Hall", entry.venueName)
        assertEquals("Enterprise OpenRoaming", entry.operatorFriendlyName)

        // Verify ReadOnlyBuffer position was NOT corrupted and repeated extraction produces identical bytes
        assertEquals(5, ie11.bytes.remaining())
        assertEquals(2, entry.informationElements.size)
        assertEquals(11, entry.informationElements[0].id)
        assertEquals(0, entry.informationElements[0].idExt)
        assertArrayEquals(bssLoadPayload, entry.informationElements[0].bytes)
        assertEquals(255, entry.informationElements[1].id)
        assertEquals(35, entry.informationElements[1].idExt)
        assertArrayEquals(heCapPayload, entry.informationElements[1].bytes)

        // Verify MLO link API 33 + API 34 telemetry
        assertEquals(1, entry.affiliatedMloLinks.size)
        val link = entry.affiliatedMloLinks.single()
        assertEquals(2, link.linkId)
        assertEquals("AA:BB:CC:DD:EE:06", link.bssid)
        assertEquals(WifiBand.BAND_6_GHZ, link.band)
        assertEquals(37, link.channel)
        assertEquals(6135, link.frequencyMhz)
        assertEquals(-49, link.rssiDbm)
        assertEquals(2402, link.txLinkSpeedMbps)
        assertEquals(1921, link.rxLinkSpeedMbps)
        assertTrue(link.isActive)

        // Verify API 33 branch skips API 34 MloLink rssi/tx/rx getters cleanly
        val api33Entry = ScanResultExtractor.extract(sr, fallbackTimestampMicros = 1_000_000L, sdkInt = 33)!!
        assertNull(api33Entry.affiliatedMloLinks.single().rssiDbm)
        assertNull(api33Entry.affiliatedMloLinks.single().txLinkSpeedMbps)

        // Verify null-byte hidden SSID extraction
        val nullByteSsidSr = WifiScanResultBuilder()
            .setWifiSsid(WifiSsid.fromBytes(byteArrayOf(0, 0, 0, 0)))
            .setBssid("10:20:30:40:50:60")
            .setRssi(-70)
            .setFrequency(2437)
            .build()
        val hiddenEntry = ScanResultExtractor.extract(nullByteSsidSr, 5_000_000L, sdkInt = 34)!!
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), hiddenEntry.rawSsidBytes)
    }

    @Test
    fun survivesBareNewScanResultShadowScanResultAndInvalidOrRedactedBssids() {
        // Bare new ScanResult() in SDK 34 has null informationElements backing array & null capabilities
        val bare = ScanResult().apply {
            @Suppress("DEPRECATION")
            SSID = "Bare_2G"
            BSSID = "11:22:33:44:55:66"
            level = Int.MIN_VALUE
            frequency = 2412
        }
        val bareEntry = ScanResultExtractor.extract(bare, fallbackTimestampMicros = 9_000_000L, sdkInt = 34)
        assertNotNull(bareEntry)
        bareEntry!!
        assertEquals("11:22:33:44:55:66", bareEntry.bssid)
        assertNull(bareEntry.rawSsidBytes)
        assertEquals("Bare_2G", bareEntry.legacySsid)
        assertEquals(-127, bareEntry.rssiDbm) // Clamped safely from Int.MIN_VALUE!
        assertEquals("", bareEntry.capabilities)
        assertTrue(bareEntry.informationElements.isEmpty())
        assertEquals(9_000_000L, bareEntry.timestampMicros)

        // ShadowScanResult.newInstance sets SSID while leaving wifiSsid == null
        val shadowSr = ShadowScanResult.newInstance("LegacyShadow_AP", "aa:11:22:33:44:55", "[ESS]", -61, 2462)
        val shadowEntry = ScanResultExtractor.extract(shadowSr, fallbackTimestampMicros = 12_000_000L, sdkInt = 34)!!
        assertNull(shadowEntry.rawSsidBytes)
        assertEquals("LegacyShadow_AP", shadowEntry.legacySsid)
        assertEquals(-61, shadowEntry.rssiDbm)

        // Reject null, redacted 02:00:00:00:00:00, all-zero, and malformed MACs
        assertNull(ScanResultExtractor.extract(null, 1_000L))
        val redacted = ShadowScanResult.newInstance("Redacted", "02:00:00:00:00:00", "[ESS]", -50, 2412)
        assertNull(ScanResultExtractor.extract(redacted, 1_000L))
        val zeroMac = ShadowScanResult.newInstance("Zero", "00:00:00:00:00:00", "[ESS]", -50, 2412)
        assertNull(ScanResultExtractor.extract(zeroMac, 1_000L))
        val malformed = ShadowScanResult.newInstance("Bad", "not-a-mac", "[ESS]", -50, 2412)
        assertNull(ScanResultExtractor.extract(malformed, 1_000L))
    }

    @Test
    fun antiFlickerCacheRetainsMissedUpTo45sEvictsStaleOver90sAndCapsAt512Bssids() {
        val cache = AntiFlickerScanCache()

        val ap1 = ShadowScanResult.newInstance("Mesh_Node_1", "aa:bb:cc:00:00:01", "[WPA2]", -55, 5180)
        val ap2 = ShadowScanResult.newInstance("Mesh_Node_2", "aa:bb:cc:00:00:02", "[WPA2]", -65, 5220)

        // Sweep 1 at t = 10_000 ms: both APs present
        val batch1 = cache.ingestSweep(
            rawResults = listOf(ap1, ap2),
            nowElapsedMs = 10_000L,
            nowMicros = 10_000_000L,
        )
        assertEquals(2, batch1.entries.size)
        assertEquals(10_000_000L, batch1.entries.first { it.bssid == "AA:BB:CC:00:00:01" }.timestampMicros)

        // Sweep 2 at t = 25_000 ms (+15s): ap1 missed, ap2 present -> ap1 retained with older timestampMicros!
        val batch2 = cache.ingestSweep(
            rawResults = listOf(ap2),
            nowElapsedMs = 25_000L,
            nowMicros = 25_000_000L,
        )
        assertEquals(2, batch2.entries.size)
        val retainedAp1At15s = batch2.entries.first { it.bssid == "AA:BB:CC:00:00:01" }
        assertEquals(10_000_000L, retainedAp1At15s.timestampMicros)
        assertTrue(retainedAp1At15s.timestampMicros < batch2.receivedElapsedMs * 1_000L)

        // Sweep 3 at t = 55_000 ms (+45.000s exact boundary): ap1 still retained!
        val batch3 = cache.ingestSweep(
            rawResults = listOf(ap2),
            nowElapsedMs = 55_000L,
            nowMicros = 55_000_000L,
        )
        assertEquals(2, batch3.entries.size)

        // Sweep 4 at t = 55_001 ms (+45.001s): ap1 evicted!
        val batch4 = cache.ingestSweep(
            rawResults = listOf(ap2),
            nowElapsedMs = 55_001L,
            nowMicros = 55_001_000L,
        )
        assertEquals(1, batch4.entries.size)
        assertEquals("AA:BB:CC:00:00:02", batch4.entries.single().bssid)

        // Stale OS ScanResult (> 90_000 ms age) is rejected immediately, while 89s and synthetic
        // timestamp == 0L entries at t = 250_000 ms are accepted!
        val stale91s = ShadowScanResult.newInstance("Stale_91s", "aa:bb:cc:00:00:91", "[ESS]", -50, 2412).apply {
            timestamp = 159_000_000L // age = 91_000 ms at t = 250_000 ms
        }
        val fresh89s = ShadowScanResult.newInstance("Fresh_89s", "aa:bb:cc:00:00:89", "[ESS]", -52, 2412).apply {
            timestamp = 161_000_000L // age = 89_000 ms at t = 250_000 ms
        }
        val syntheticZeroTs = ShadowScanResult.newInstance("Synthetic_0", "aa:bb:cc:00:00:77", "[ESS]", -48, 5180)
        assertEquals(0L, syntheticZeroTs.timestamp)

        val batch5 = cache.ingestSweep(
            rawResults = listOf(stale91s, fresh89s, syntheticZeroTs),
            nowElapsedMs = 250_000L,
            nowMicros = 250_000_000L,
        )
        val bssids5 = batch5.entries.map { it.bssid }.toSet()
        assertFalse("AA:BB:CC:00:00:91" in bssids5)
        assertTrue("AA:BB:CC:00:00:89" in bssids5)
        assertTrue("AA:BB:CC:00:00:77" in bssids5)
        assertNotNull(cache.getLatestRawScanResult("aa-bb-cc-00-00-77"))

        // Enforce 512-BSSID LRU cap when fed 700 rotating BSSIDs
        val manyAps = (0 until 700).map { idx ->
            val b4 = (idx shr 8) and 0xFF
            val b5 = idx and 0xFF
            val mac = String.format("10:20:30:40:%02X:%02X", b4, b5)
            ShadowScanResult.newInstance("AP_$idx", mac, "[WPA2]", -60, 2437)
        }
        val cappedBatch = cache.ingestSweep(
            rawResults = manyAps,
            nowElapsedMs = 260_000L,
            nowMicros = 260_000_000L,
        )
        assertEquals(512, cappedBatch.entries.size)
        assertEquals(512, cache.size())
    }
}
