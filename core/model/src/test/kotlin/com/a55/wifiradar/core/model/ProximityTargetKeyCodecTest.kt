package com.a55.wifiradar.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProximityTargetKeyCodecTest {

    @Test
    fun bssidLock_roundTripsWithHintAndColonsAndNullHint() {
        val withHint = ProximityTargetKey.BssidLock(
            bssid = "aa-bb-cc-dd-ee-ff",
            ssidHint = "My:Router:5G",
        )
        val decodedWithHint = ProximityTargetKey.fromSavedStateString(withHint.toSavedStateString())
        assertEquals(
            ProximityTargetKey.BssidLock(bssid = "AA:BB:CC:DD:EE:FF", ssidHint = "My:Router:5G"),
            decodedWithHint,
        )

        val withoutHint = ProximityTargetKey.BssidLock(bssid = "AA:BB:CC:DD:EE:FF", ssidHint = null)
        assertEquals(
            withoutHint,
            ProximityTargetKey.fromSavedStateString(withoutHint.toSavedStateString()),
        )

        assertEquals(
            ProximityTargetKey.BssidLock(bssid = "11:22:33:44:55:66", ssidHint = null),
            ProximityTargetKey.fromSavedStateString("11:22:33:44:55:66"),
        )
    }

    @Test
    fun followMeshSsid_roundTripsWithAndWithoutPreferredBssid() {
        val withPreferred = ProximityTargetKey.FollowMeshSsid(
            ssid = "Mesh:Home:6E",
            preferredBssid = "aa:bb:cc:00:00:01",
        )
        assertEquals(
            ProximityTargetKey.FollowMeshSsid(ssid = "Mesh:Home:6E", preferredBssid = "AA:BB:CC:00:00:01"),
            ProximityTargetKey.fromSavedStateString(withPreferred.toSavedStateString()),
        )

        val withoutPreferred = ProximityTargetKey.FollowMeshSsid(
            ssid = "Mesh:Home:6E",
            preferredBssid = null,
        )
        assertEquals(
            withoutPreferred,
            ProximityTargetKey.fromSavedStateString(withoutPreferred.toSavedStateString()),
        )
    }

    @Test
    fun fromSavedStateString_returnsNullOnCorruptedInputs() {
        assertNull(ProximityTargetKey.fromSavedStateString(null))
        assertNull(ProximityTargetKey.fromSavedStateString(""))
        assertNull(ProximityTargetKey.fromSavedStateString("   "))
        assertNull(ProximityTargetKey.fromSavedStateString("bssid:short"))
        assertNull(ProximityTargetKey.fromSavedStateString("bssid:ZZ:ZZ:ZZ:ZZ:ZZ:ZZ:Home"))
        assertNull(ProximityTargetKey.fromSavedStateString("ssid::"))
        assertNull(ProximityTargetKey.fromSavedStateString("unknown_prefix:value"))
    }
}
