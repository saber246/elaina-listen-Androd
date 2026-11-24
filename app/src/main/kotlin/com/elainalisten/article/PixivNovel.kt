package com.elainalisten.article

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

internal object PixivNovel {
    private val idPattern = Regex("[1-9][0-9]{0,15}")
    private val hosts = setOf("www.pixiv.net", "pixiv.net")
    private val novelPath = Regex("/(?:(?:en|ja|zh|zh-cn|zh-tw|ko)/)?novel/show\\.php", RegexOption.IGNORE_CASE)
    private val seriesPath = Regex("/(?:(?:en|ja|zh|zh-cn|zh-tw|ko)/)?novel/series/([1-9][0-9]{0,15})/?",
        RegexOption.IGNORE_CASE)
    private val headings = Regex("\\[chapter:([^]\\r\\n]+)]", RegexOption.IGNORE_CASE)
    private val imagesAndJumps = Regex(
        "\\[(?:uploadedimage|pixivimage|jump):[^]\\r\\n]*]",
        RegexOption.IGNORE_CASE,
    )
    private val richText = Regex("\\[\\[(?:rb|jumpuri):([^]\\r\\n]*?)\\s*>[^]\\r\\n]*]]", RegexOption.IGNORE_CASE)
    private val styles = Regex("\\[/?(?:b|i|em|strong)]", RegexOption.IGNORE_CASE)
    private val pageBreak = Regex("\\[newpage]", RegexOption.IGNORE_CASE)
    const val maxContentLength = 300_000

    fun validId(id: String?): String? = id?.takeIf(idPattern::matches)

    fun isOrigin(address: String): Boolean = pixivUri(address) != null

    fun novelId(address: String?): String? {
        val page = address?.let(::pixivUri) ?: return null
        if (!novelPath.matches(page.path)) return null
        val ids = page.rawQuery.orEmpty().split('&').filter { it.substringBefore('=') == "id" }
        return validId(ids.singleOrNull()?.substringAfter('=', ""))
    }

    fun seriesId(address: String): String? =
        pixivUri(address)?.path?.let(seriesPath::matchEntire)?.groupValues?.get(1)

    fun url(id: String): String = "https://www.pixiv.net/novel/show.php?id=$id"

    fun parse(id: String, record: JSONObject): ReadableArticle? {
        if (validId(record.optString("id")) != id ||
            (record.has("available") && !record.optBoolean("available"))
        ) return null
        val title = record.optString("title").trim().take(500)
        val text = record.opt("content") as? String ?: return null
        if (title.isBlank() || text.isBlank() || text.length > maxContentLength) return null
        val chapters = chapters(title, text)
        if (chapters.isEmpty()) return null
        val nav = record.optJSONObject("seriesNavData")
        val seriesId = validId(nav?.optString("seriesId")) ?: validId(record.optString("seriesId"))
        val series = seriesId?.let {
            PixivSeries(it, nav?.optString("title").orEmpty().ifBlank { record.optString("seriesTitle") }.take(500),
                validId(nav?.optJSONObject("next")?.optString("id"))?.takeUnless { next -> next == id })
        }
        return ReadableArticle(title, url(id), chapters, pixivSeries = series, pixivFull = true)
    }

    fun catalog(body: JSONArray): List<PixivEntry> {
        return List(body.length()) { body.optJSONObject(it) }.mapNotNull { item ->
            if (item == null || !item.optBoolean("available", true)) return@mapNotNull null
            val id = validId(item.optString("id")) ?: return@mapNotNull null
            val title = item.optString("title").trim().take(500)
            PixivEntry(id, title.ifBlank { url(id) })
        }.distinctBy(PixivEntry::id)
    }

    private fun chapters(title: String, text: String): List<Chapter> = buildList {
        var start = 0
        var heading = title
        for (match in headings.findAll(text)) {
            val paragraphs = paragraphs(text.substring(start, match.range.first))
            if (paragraphs.isNotEmpty()) add(Chapter(heading, paragraphs))
            heading = match.groupValues[1].trim().take(500).ifBlank { title }
            start = match.range.last + 1
        }
        val paragraphs = paragraphs(text.substring(start))
        if (paragraphs.isNotEmpty()) add(Chapter(heading, paragraphs))
    }

    private fun paragraphs(raw: String): List<String> {
        val clean = raw.replace(pageBreak, "\n").replace(imagesAndJumps, "")
            .replace(richText) { it.groupValues[1].trim() }.replace(styles, "")
        return clean.lineSequence().map(String::trim).filter(String::isNotEmpty)
            .flatMap { line ->
                sequence {
                    var from = 0
                    while (from < line.length) {
                        var end = (from + 2_000).coerceAtMost(line.length)
                        if (end < line.length && Character.isLowSurrogate(line[end])) end--
                        yield(line.substring(from, end))
                        from = end
                    }
                }
            }.toList()
    }

    private fun pixivUri(address: String): URI? = runCatching { URI(address) }.getOrNull()?.takeIf {
        it.scheme.equals("https", true) && it.host?.lowercase() in hosts && it.userInfo == null &&
            (it.port == -1 || it.port == 443)
    }
}
