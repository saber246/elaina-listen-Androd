package com.elainalisten.browser

import java.net.URI

internal object WebAddress {
    fun fromInput(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        val address = if ("://" in trimmed) trimmed else "https://$trimmed"
        return address.takeIf(::isAllowed)
    }

    fun isAllowed(address: String): Boolean = runCatching {
        val uri = URI(address)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrEmpty() &&
            uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port in 1..65_535)
    }.getOrDefault(false)
}
