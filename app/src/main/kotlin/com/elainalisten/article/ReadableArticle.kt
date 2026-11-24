package com.elainalisten.article

internal data class PageSnapshot(val url: String, val title: String, val html: String)

internal data class ReadableArticle(
    val title: String,
    val sourceUrl: String?,
    val chapters: List<Chapter>,
)

internal data class Chapter(val title: String, val paragraphs: List<String>)
