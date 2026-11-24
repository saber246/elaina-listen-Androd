package com.elainalisten

import android.util.Xml
import androidx.test.platform.app.InstrumentationRegistry
import com.elainalisten.tts.AzureCredentialsStore
import com.elainalisten.tts.AzureSpeech
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
