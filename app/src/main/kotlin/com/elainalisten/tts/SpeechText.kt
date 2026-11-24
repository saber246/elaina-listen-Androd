package com.elainalisten.tts

internal object SpeechText {
    const val maxInputLength = 5_000
    private const val maxSegmentCodePoints = 80
    private const val sentenceEndings = "。！？；!?;\n\r"
    private const val clauseEndings = "，、：,:… "

    fun split(text: String): List<String> = buildList {
        var start = 0
        while (start < text.length) {
            var end = start
            var sentenceEnd = start
            var clauseEnd = start
            var count = 0
            while (end < text.length && count < maxSegmentCodePoints) {
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
