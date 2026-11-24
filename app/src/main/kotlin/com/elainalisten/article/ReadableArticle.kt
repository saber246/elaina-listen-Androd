package com.elainalisten.article

internal data class PageSnapshot(
    val url: String,
    val title: String,
    val html: String,
    val next: String? = null,
    val pagination: Pagination? = null,
)

internal data class ReadableArticle(
    val title: String,
    val sourceUrl: String?,
    val chapters: List<Chapter>,
    val nextPageUrl: String? = null,
    val pagination: Pagination? = null,
    val pixivSeries: PixivSeries? = null,
    val incomplete: Boolean = false,
    val pixivFull: Boolean = false,
    val paginationSnapshotHash: String? = null,
) {
    val characterCount: Int get() = chapters.sumOf { chapter ->
        chapter.paragraphs.sumOf { it.codePointCount(0, it.length) }
    }
}

internal data class Chapter(val title: String, val paragraphs: List<String>)
internal data class Pagination(val current: Int?, val total: Int?, val hasControl: Boolean) {
    val atEnd: Boolean get() = current != null && current == total
}

internal data class PixivSeries(val id: String, val title: String, val nextNovelId: String?)
internal data class PixivEntry(val id: String, val title: String)
internal data class PageExtraction(val article: ReadableArticle?, val pixivFailed: Boolean = false)
