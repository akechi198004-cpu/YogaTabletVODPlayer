package com.hm.video.remoteinput

import org.junit.Assert.assertEquals
import org.junit.Test

class LanAddressesTest {
    @Test
    fun prefersWifiThenEthernetThenOtherInterfaces() {
        val sorted = LanAddresses.sortCandidates(listOf(
            LanAddress("10.0.0.30", "usb0"),
            LanAddress("192.168.1.20", "eth0"),
            LanAddress("192.168.0.54", "wlan0")
        ))
        assertEquals(listOf("192.168.0.54", "192.168.1.20", "10.0.0.30"), sorted.map { it.host })
    }
}
