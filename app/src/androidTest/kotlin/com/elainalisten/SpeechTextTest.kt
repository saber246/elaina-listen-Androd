package com.elainalisten

import com.elainalisten.tts.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    @Test fun textSplittingPreservesCodePointsAndConfiguredLimits() {
        val text = "字".repeat(79) + "𠀀" + "字".repeat(121)
        for (limit in listOf(80, 200)) {
            val parts = SpeechText.split(text, limit)
            assertEquals(text, parts.joinToString(""))
            assertTrue(parts.all { it.codePointCount(0, it.length) <= limit })
        }
        assertEquals("𠀀", SpeechText.split(text).first().takeLast(2))
    }
}
