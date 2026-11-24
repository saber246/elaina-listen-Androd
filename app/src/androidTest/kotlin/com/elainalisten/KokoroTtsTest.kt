package com.elainalisten

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.elainalisten.tts.KokoroTts
import com.elainalisten.tts.PreviewAudio
import com.elainalisten.tts.SpeechAudio
import com.elainalisten.tts.SpeechText
import com.elainalisten.tts.Voice
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class KokoroTtsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun bothMandarinVoicesGenerateAndPlayDistinctAudio() = runBlocking<Unit> {
        val sentence = "你好，我是轻语听书。今天，我们一起听一段中文故事。"
        val signatures = Voice.entries.map { voice ->
            val started = SystemClock.elapsedRealtime()
            var signature = 0
            KokoroTts.synthesize(context, sentence, voice) { speech, index, total ->
                assertEquals(1, index)
                assertEquals(1, total)
                assertSpeech(speech)
                signature = speech.samples.contentHashCode()
                Log.i(
                    "KokoroTest",
                    "${voice.id} synthesisMs=${SystemClock.elapsedRealtime() - started} " +
                        "frames=${speech.samples.size} rate=${speech.sampleRateHz} " +
                        "pssKiB=${Debug.getPss()} ${residentHighWaterMark()}",
                )
                if (InstrumentationRegistry.getArguments().getString("exportKokoroWavs") == "true") {
                    writeWave(voice, speech)
                }
                PreviewAudio.play(speech)
            }
            signature
        }
        assertNotEquals(signatures[0], signatures[1])
    }

    @Test
    fun longMandarinTextProducesOneChunkPerSegment() = runBlocking<Unit> {
        val paragraph = "清晨的微风拂过窗台，远处传来鸟鸣。她翻开书，听完了一个关于海洋和星星的故事。"
        val text = paragraph.repeat(6)
        val segments = SpeechText.split(text)
        assertTrue(segments.size > 1)
        assertEquals(text, segments.joinToString(""))
        var received = 0
        var samples = 0L
        val started = SystemClock.elapsedRealtime()
        KokoroTts.synthesize(context, text, Voice.default) { audio, index, total ->
            assertEquals(++received, index)
            assertEquals(segments.size, total)
            assertSpeech(audio)
            samples += audio.samples.size
        }
        assertEquals(segments.size, received)
        Log.i(
            "KokoroTest",
            "longText chars=${text.length} segments=$received frames=$samples " +
                "synthesisMs=${SystemClock.elapsedRealtime() - started} ${residentHighWaterMark()}",
        )
    }

    @Test
    fun splittingDoesNotCutSupplementaryCharacters() {
        val supplementary = "𠀀"
        val text = "字".repeat(79) + supplementary + "字".repeat(81)
        val segments = SpeechText.split(text)
        assertEquals(text, segments.joinToString(""))
        assertEquals(supplementary, segments.first().takeLast(2))
        assertTrue(segments.all { it.codePointCount(0, it.length) <= 80 })
        assertTrue(SpeechText.split(" \n\t ").isEmpty())
    }

    private fun assertSpeech(speech: SpeechAudio) {
        assertEquals(24_000, speech.sampleRateHz)
        assertTrue(speech.samples.size > speech.sampleRateHz / 2)
        assertTrue(speech.samples.all { it.isFinite() })
        assertTrue(speech.samples.any { abs(it) > 0.05f })
    }

    private fun residentHighWaterMark(): String =
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmHWM:") }.orEmpty()
        }

    private fun writeWave(voice: Voice, audio: SpeechAudio) {
        val payloadSize = audio.samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + payloadSize)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(audio.sampleRateHz)
            putInt(audio.sampleRateHz * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(payloadSize)
        }
        val samples = ByteBuffer.allocate(payloadSize).order(ByteOrder.LITTLE_ENDIAN)
        audio.samples.forEach { samples.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        File(context.filesDir, "${voice.id}.wav").outputStream().use { output ->
            output.write(header.array())
            output.write(samples.array())
        }
    }
}
