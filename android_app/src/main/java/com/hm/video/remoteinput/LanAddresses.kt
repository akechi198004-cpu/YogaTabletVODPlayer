package com.hm.video.remoteinput

import java.net.Inet4Address
import java.net.NetworkInterface

/** A LAN IPv4 endpoint that a phone on the same router can try. */
data class LanAddress(val host: String, val interfaceName: String)

object LanAddresses {

    fun candidates(): List<LanAddress> {
        val found = mutableListOf<LanAddress>()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        for (network in interfaces) {
            val up = try { network.isUp } catch (_: Exception) { false }
            val loopback = try { network.isLoopback } catch (_: Exception) { true }
            if (!up || loopback) continue
            for (address in network.inetAddresses) {
                if (address !is Inet4Address) continue
                if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isAnyLocalAddress) continue
                val host = address.hostAddress?.substringBefore('%') ?: continue
                if (host.isBlank() || host == "0.0.0.0") continue
                if (found.none { it.host == host }) {
                    found.add(LanAddress(host, network.name.orEmpty()))
                }
            }
        }
        return sortCandidates(found)
    }

    /** Wi-Fi/Ethernet first, then other usable interfaces; stable within each group. */
    internal fun sortCandidates(items: List<LanAddress>): List<LanAddress> {
        return items.sortedWith(
            compareBy<LanAddress> { priority(it.interfaceName) }
                .thenBy { it.host }
        )
    }

    private fun priority(name: String): Int {
        val lower = name.lowercase()
        return when {
            lower.startsWith("wlan") -> 0
            lower.startsWith("eth") -> 1
            else -> 2
        }
    }
}
