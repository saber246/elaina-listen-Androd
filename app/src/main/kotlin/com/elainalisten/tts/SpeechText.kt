package com.elainalisten.tts

import android.icu.text.BreakIterator
import java.util.Locale

internal object SpeechText {
    const val maxInputLength = 5_000
    const val defaultSegmentLength = 80
    val segmentLengths = 80..200
    private const val sentenceEndings = "。！？；!?;\n\r"
    private const val clauseEndings = "，、：,:… "

    fun sentences(text: String): List<IntRange> = buildList {
        val boundaries = BreakIterator.getSentenceInstance(Locale.SIMPLIFIED_CHINESE)
        boundaries.setText(text)
        var start = boundaries.first()
        var end = boundaries.next()
        while (end != BreakIterator.DONE) {
            val first = (start until end).firstOrNull { !text[it].isWhitespace() }
            if (first != null) {
                val last = (end - 1 downTo first).first { !text[it].isWhitespace() }
                add(first..last)
            }
            start = end
            end = boundaries.next()
        }
    }

    fun estimateMs(text: String): Long = text.codePoints().toArray().sumOf { point ->
        when {
            Character.isWhitespace(point) -> 0L
            point <= Char.MAX_VALUE.code && point.toChar() in sentenceEndings -> 300L
            point <= Char.MAX_VALUE.code && point.toChar() in clauseEndings -> 160L
            Character.isIdeographic(point) -> 290L
            else -> 80L
        }
    }

    fun split(text: String, maxLength: Int = defaultSegmentLength): List<String> = buildList {
        val limit = maxLength.coerceIn(segmentLengths)
        var start = 0
        while (start < text.length) {
            var end = start
            var sentenceEnd = start
            var clauseEnd = start
            var count = 0
            while (end < text.length && count < limit) {
                val point = text.codePointAt(end)
                end += Character.charCount(point)
                count++
                if (Character.isBmpCodePoint(point)) {
                    val character = point.toChar()
                    if (character in sentenceEndings) sentenceEnd = end
                    if (character in clauseEndings) clauseEnd = end
                }
            }
            val splitAt = when {
                end == text.length -> end
                sentenceEnd > start -> sentenceEnd
                clauseEnd > start -> clauseEnd
                else -> end
            }
            val segment = text.substring(start, splitAt).trim()
            if (segment.isNotEmpty()) add(segment)
            start = splitAt
        }
    }
}
