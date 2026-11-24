package org.fossistant.assistant.util

import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Process-wide shared [OkHttpClient] and bounded/network-safety HTTP helpers.
 * Callers customize timeouts or DNS via [client].newBuilder().
 */
object SharedHttp {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Reads at most [maxBytes] UTF-8 bytes from an HTTP response body to avoid OOM on huge replies. */
    fun readCappedText(body: ResponseBody?, maxBytes: Int): String {
        val stream = body?.byteStream() ?: return ""
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var remaining = maxBytes.coerceAtLeast(0)
        stream.use { input ->
            while (remaining > 0) {
                val read = input.read(buf, 0, minOf(buf.size, remaining))
                if (read <= 0) break
                out.write(buf, 0, read)
                remaining -= read
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** True for loopback, site-local LAN, CGNAT/Tailscale, link-local, or local `.local`/`.lan` hostnames. */
    fun isPrivateOrLanUrl(rawUrl: String): Boolean {
        val host = runCatching { URI(rawUrl.trim()).host }.getOrNull()
            ?.trim()?.lowercase()?.removeSurrounding("[", "]")
            ?.takeIf { it.isNotEmpty() } ?: return false
        if (!isPublicHostnameLabel(host) || host.endsWith(".lan") || host.endsWith(".home.arpa") || host.endsWith(".home")) {
            return true
        }
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull()
        if (addresses.isNullOrEmpty()) return false
        return addresses.all { isPrivateOrLocalAddress(it) }
    }

    fun isPublicHostnameLabel(cleanHost: String): Boolean {
        if (cleanHost.isBlank()) return false
        return cleanHost != "localhost" &&
            !cleanHost.endsWith(".localhost") &&
            !cleanHost.endsWith(".local") &&
            !cleanHost.endsWith(".internal")
    }

    fun isPrivateOrLocalAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || addr.isMulticastAddress
        ) {
            return true
        }
        val bytes = addr.address ?: return true
        return when (bytes.size) {
            4 -> {
                val b0 = bytes[0].toInt() and 0xff
                val b1 = bytes[1].toInt() and 0xff
                // 100.64.0.0/10 (Carrier-Grade NAT / Tailscale) or 0.0.0.0/8
                b0 == 0 || (b0 == 100 && (b1 and 0xc0) == 64)
            }
            16 -> {
                val b0 = bytes[0].toInt() and 0xff
                // Unique Local IPv6 (fc00::/7)
                (b0 and 0xfe) == 0xfc
            }
            else -> true
        }
    }
}
