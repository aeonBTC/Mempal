package com.example.mempal.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Central cleartext policy. HTTPS is always allowed. Plain HTTP is only allowed
 * for Tor onion services, localhost, and private/link-local networks.
 *
 * The platform network-security-config permits cleartext broadly so custom LAN
 * servers keep working; this policy is what blocks public HTTP endpoints.
 */
object NetworkPolicy {
    fun isCleartextAllowed(url: HttpUrl): Boolean {
        if (url.isHttps) return true

        val host = url.host.lowercase()
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" ||
            host.endsWith(".onion") || host.endsWith(".local")
        ) {
            return true
        }

        val octets = host.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4) {
            if (host.contains(':')) {
                // IPv6 loopback/ULA/link-local
                return host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80")
            }
            return false
        }

        val first = octets[0]
        val second = octets[1]
        return first == 127 ||
            first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            (first == 169 && second == 254)
    }

    fun isCleartextAllowed(url: String): Boolean {
        val httpUrl = url.trim().toHttpUrlOrNull() ?: return false
        return isCleartextAllowed(httpUrl)
    }
}
