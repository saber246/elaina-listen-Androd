package com.elainalisten.article

internal data class PageSnapshot(val url: String, val title: String, val html: String, val next: String? = null)

internal data class ReadableArticle(
    val title: String,
    val sourceUrl: String?,
    val chapters: List<Chapter>,
    val nextPageUrl: String? = null,
)

internal data class Chapter(val title: String, val paragraphs: List<String>)
