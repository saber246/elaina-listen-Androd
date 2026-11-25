package com.elainalisten.browser

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.google.common.net.InternetDomainName
import java.io.ByteArrayInputStream
import java.util.Locale

internal class BrowserAdBlocker(loadHosts: () -> Set<String>) {
    private val hosts by lazy(loadHosts)
    @Volatile var enabled = true

    fun intercept(pageUrl: String, request: WebResourceRequest): WebResourceResponse? {
        if (!enabled || request.isForMainFrame || (request.url.scheme != "http" && request.url.scheme != "https")) return null
        val page = Uri.parse(pageUrl).host?.normalizedHost() ?: return null
        val requestHost = request.url.host?.normalizedHost() ?: return null
        if (!matches(requestHost)) return null
        if (page == requestHost || page.endsWith(".$requestHost") || requestHost.endsWith(".$page")) return null
        val site = registrableDomain(page) ?: return null
        val requestSite = registrableDomain(requestHost) ?: return null
        if (site == requestSite) return null
        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    }

    private fun matches(host: String): Boolean {
        var suffix = host
        while (suffix.isNotEmpty()) {
            if (suffix in hosts) return true
            suffix = suffix.substringAfter('.', "")
        }
        return false
    }

    private fun registrableDomain(host: String): String? =
        runCatching { InternetDomainName.from(host).topPrivateDomain().toString() }.getOrNull()

    companion object {
        fun parseHosts(lines: Sequence<String>): Set<String> = lines.mapNotNull { line ->
            val columns = line.substringBefore('#').trim().split(Regex("\\s+"), limit = 3)
            if (columns.firstOrNull() != "127.0.0.1" && columns.firstOrNull() != "0.0.0.0") null
            else columns.getOrNull(1)?.normalizedHost()?.takeIf { '.' in it }
        }.toHashSet()

        private fun String.normalizedHost() = trimEnd('.').lowercase(Locale.ROOT)
    }
}
