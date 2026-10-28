package com.hm.video.util

import com.hm.video.data.model.PlayLine
import okhttp3.HttpUrl

/**
 * Short, stable source labels for the line selector.
 *
 * The source site only calls these "线路1/2/3"; those numbers shift between episodes and
 * titles. A provider-derived label lets the viewer recognise the same source everywhere.
 * Providers that appear more than once in one episode are numbered in display order,
 * e.g. svipplay(1) and svipplay(2).
 */
fun lineLabels(lines: List<PlayLine>): List<String> {
    val bases = lines.map(::providerLabel)
    val totals = bases.groupingBy { it }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return bases.map { base ->
        if ((totals[base] ?: 0) > 1) {
            val next = (seen[base] ?: 0) + 1
            seen[base] = next
            "$base($next)"
        } else {
            base
        }
    }
}

private fun providerLabel(line: PlayLine): String {
    val host = HttpUrl.parse(line.url)
        ?.host()
        ?.removePrefix("www.")
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }
        ?: return line.name
    if (host.matches(Regex("^\\d+(\\.\\d+){3}$"))) return host

    val parts = host.split('.')
    if (parts.size < 2) return host
    val secondLevel = parts[parts.size - 2]
    val topLevel = parts.last()
    val commonSecondLevels = setOf("com", "net", "org", "gov", "edu", "co", "ac", "go", "or", "ne")
    val twoPartSuffixes = setOf("cn", "uk", "jp", "kr", "tw", "hk")
    return if (topLevel in twoPartSuffixes && secondLevel in commonSecondLevels && parts.size >= 3) {
        parts[parts.size - 3]
    } else {
        secondLevel
    }
}
