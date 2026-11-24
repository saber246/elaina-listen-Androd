package com.elainalisten.article

import java.security.MessageDigest

internal object PageContent {
    fun hash(article: ReadableArticle): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (chapter in article.chapters) {
            for (paragraph in chapter.paragraphs) {
                digest.update(normalize(paragraph).toByteArray(Charsets.UTF_8))
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun removeLeadingOverlap(previous: ReadableArticle, next: ReadableArticle): ReadableArticle? {
        val spoken = previous.chapters.flatMap(Chapter::paragraphs).map(::normalize)
        val upcoming = next.chapters.flatMap(Chapter::paragraphs).map(::normalize)
        var discard = if (upcoming.size >= spoken.size && upcoming.subList(0, spoken.size) == spoken) spoken.size
        else (minOf(spoken.size, upcoming.size, 64) downTo 1).firstOrNull { size ->
            spoken.subList(spoken.size - size, spoken.size) == upcoming.subList(0, size)
        } ?: 0
        if (discard == 0) return next
        val chapters = next.chapters.mapNotNull { chapter ->
            val count = minOf(discard, chapter.paragraphs.size)
            discard -= count
            chapter.copy(paragraphs = chapter.paragraphs.drop(count)).takeIf { it.paragraphs.isNotEmpty() }
        }
        return next.copy(chapters = chapters, paginationSnapshotHash = hash(next)).takeIf { chapters.isNotEmpty() }
    }

    private fun normalize(text: String): String = text.filterNot(Char::isWhitespace)
}
