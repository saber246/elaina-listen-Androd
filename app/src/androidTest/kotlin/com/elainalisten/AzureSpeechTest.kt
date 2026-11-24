package com.elainalisten

import android.util.Xml
import androidx.test.platform.app.InstrumentationRegistry
import com.elainalisten.tts.AzureCredentialsStore
import com.elainalisten.tts.AzureSpeech
import com.elainalisten.tts.AppSpeech
import com.elainalisten.tts.AzureAudioCache
import com.elainalisten.tts.SpeechAudio
import kotlinx.coroutines.runBlocking
import com.elainalisten.tts.AzureSpeechException
import com.elainalisten.tts.Voice
import java.io.File
import java.io.StringReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AzureSpeechTest {
    @Test
    fun cachedAzureAudioReopensWithoutCredentialsAndKeysByVoiceLengthAndText() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = AzureAudioCache(context)
        val line = "离线读取缓存 ${java.util.UUID.randomUUID()}"
        val audio = SpeechAudio(floatArrayOf(-0.5f, 0.0f, 0.5f), 24_000)
        var requests = 0
        val synthesize: suspend () -> SpeechAudio = { requests++; audio }
        try {
            cache.getOrSynthesize(Voice.AzureXiaoxiao, 80, line, synthesize)
            cache.getOrSynthesize(Voice.AzureXiaoxiao, 80, line, synthesize)
            assertEquals(1, requests)
            val offline = AppSpeech.synthesize(context, line, Voice.AzureXiaoxiao, 80)
            assertTrue(audio.samples.contentEquals(offline.samples))
            assertEquals(24_000, offline.sampleRateHz)
            cache.getOrSynthesize(Voice.AzureYunxi, 80, line, synthesize)
            cache.getOrSynthesize(Voice.AzureXiaoxiao, 81, line, synthesize)
            cache.getOrSynthesize(Voice.AzureXiaoxiao, 80, "$line。", synthesize)
            assertEquals(4, requests)
        } finally {
            cache.clear()
        }
    }

    @Test
    fun credentialIsEncryptedAndSurvivesStoreReopening() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
            .createDeviceProtectedStorageContext()
        val key = "sample-device-test-key"
        val first = AzureCredentialsStore(context)
        try {
            first.save("eastasia", key)
            val reopened = AzureCredentialsStore(context)
            assertEquals("eastasia", reopened.region)
            assertEquals(key, reopened.load().key)
            val preferences = File(context.dataDir, "shared_prefs/azure_speech.xml").readText()
            assertFalse(preferences.contains(key))
            reopened.clear()
            assertTrue(runCatching { first.load() }.exceptionOrNull() is AzureSpeechException)
        } finally {
            first.clear()
        }
    }

    @Test
    fun requestEscapesTextAndResponseDecodesLittleEndianPcm() {
        val ssml = AzureSpeech.ssml(Voice.AzureXiaoxiao, "甲<&>乙")
        assertTrue(ssml.contains("zh-CN-XiaoxiaoNeural"))
        val parser = Xml.newPullParser().apply { setInput(StringReader(ssml)) }
        parser.nextTag()
        parser.nextTag()
        assertEquals("甲<&>乙", parser.nextText())
        assertTrue(AzureSpeech.ssml(Voice.AzureYunxi, "试听").contains("zh-CN-YunxiNeural"))
        val audio = AzureSpeech.decodePcm(byteArrayOf(0, 0, 0, -128, 0, 64))
        assertEquals(24_000, audio.sampleRateHz)
        assertArrayEquals(floatArrayOf(0f, -1f, 0.5f), audio.samples, 0.001f)
    }
}
