package com.elainalisten.browser

import java.net.URI

internal object WebAddress {
    fun fromInput(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val address = if ("://" in trimmed) trimmed else "https://$trimmed"
        return address.takeIf(::isAllowed)
    }

    fun nextPage(source: String, link: String): String? = runCatching {
        val base = URI(source)
        val href = link.trim()
        val resolved = if (href.startsWith('?')) URI(source.substringBefore('#').substringBefore('?') + href)
            else base.resolve(href)
        val target = resolved.normalize()
        val result = target.toString().substringBefore('#')
        result.takeIf {
            link.isNotBlank() && isAllowed(source) && isAllowed(result) &&
                base.host.equals(target.host, ignoreCase = true) &&
                port(base) == port(target) && pageId(source) != pageId(result)
        }
    }.getOrNull()

    fun pageId(address: String): String = URI(address).normalize().let {
        val path = it.rawPath.ifEmpty { "/" }
        val query = it.rawQuery?.let { query -> "?$query" }.orEmpty()
        "${it.host.lowercase(java.util.Locale.ROOT)}:${port(it)}$path$query"
    }

    fun sameOrigin(first: String, second: String): Boolean {
        if (!isAllowed(first) || !isAllowed(second)) return false
        val source = URI(first)
        val target = URI(second)
        return source.host.equals(target.host, ignoreCase = true) && port(source) == port(target)
    }

    private fun port(uri: URI) = if (uri.port == -1) 443 else uri.port

    fun isAllowed(address: String): Boolean = runCatching {
        val uri = URI(address)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrEmpty() &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port in 1..65_535)
    }.getOrDefault(false)
}
