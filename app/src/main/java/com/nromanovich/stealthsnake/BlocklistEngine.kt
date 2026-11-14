package com.nromanovich.stealthsnake

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale

/**
 * Parses `assets/blocklist.txt` and matches sub-resource requests with the exact same
 * rule semantics as the `isBlocked()` helper in `snake_core.js`:
 *  - `||domain.tld` matches `domain.tld` and any subdomain (`*.domain.tld`).
 *  - `path:/prefix` matches `/prefix` or `/prefix/...` on any origin.
 *  - Main-frame navigations are never blocked.
 */
class BlocklistEngine private constructor(
    val rules: List<String>,
    private val hostRules: Set<String>,
    private val pathRules: List<String>
) {

    fun isBlocked(url: String?, isForMainFrame: Boolean): Boolean {
        if (isForMainFrame || url.isNullOrBlank()) return false
        val sanitized = url.trim()
        val parsed = runCatching { Uri.parse(sanitized) }.getOrNull() ?: return false
        return isBlocked(parsed, isForMainFrame = false)
    }

    fun isBlocked(uri: Uri?, isForMainFrame: Boolean): Boolean {
        if (isForMainFrame || uri == null) return false

        val rawHost = uri.encodedAuthority
            ?.takeIf { '\\' in it }
            ?.substringBefore('\\')
            ?.substringAfterLast('@')
            ?.substringBefore(':')
            ?: uri.host
        val host = rawHost
            ?.substringAfterLast('@')
            ?.lowercase(Locale.ROOT)
            ?.trimEnd('.')
        if (!host.isNullOrEmpty() && hostMatches(host)) {
            return true
        }

        val path = uri.path?.lowercase(Locale.ROOT) ?: "/"
        for (prefix in pathRules) {
            if (path == prefix || path.startsWith("$prefix/")) {
                return true
            }
        }
        return false
    }

    private fun hostMatches(host: String): Boolean {
        var candidate = host
        while (candidate.isNotEmpty()) {
            if (candidate in hostRules) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) break
            candidate = candidate.substring(dot + 1)
        }
        return false
    }

    /** Returns an inert HTTP 200 OK response so blocked trackers do not throw. */
    fun createEmptyBlockedResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            200,
            "OK",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0))
        )

    companion object {
        private const val ASSET_BLOCKLIST = "blocklist.txt"

        fun fromAssets(context: Context, assetName: String = ASSET_BLOCKLIST): BlocklistEngine =
            context.assets.open(assetName).use { fromStream(it) }

        fun fromStream(input: InputStream): BlocklistEngine {
            val lines = input.bufferedReader(Charsets.UTF_8).readLines()
            return fromRules(lines)
        }

        fun fromRules(rawLines: Iterable<String>): BlocklistEngine {
            val rules = LinkedHashSet<String>()
            val hosts = LinkedHashSet<String>()
            val paths = LinkedHashSet<String>()

            for (raw in rawLines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                when {
                    line.startsWith("||") -> {
                        val domain = line.substring(2).lowercase(Locale.ROOT).trimEnd('.')
                        if (domain.isNotEmpty() && hosts.add(domain)) {
                            rules.add("||$domain")
                        }
                    }
                    line.startsWith("path:") -> {
                        val prefix = line.substring(5).lowercase(Locale.ROOT)
                        if (prefix.startsWith("/") && paths.add(prefix)) {
                            rules.add("path:$prefix")
                        }
                    }
                }
            }
            return BlocklistEngine(
                rules = rules.toList(),
                hostRules = hosts.toSet(),
                pathRules = paths.toList()
            )
        }
    }
}
