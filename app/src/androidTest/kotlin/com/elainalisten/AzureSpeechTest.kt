package com.elainalisten

import android.util.Xml
import androidx.test.platform.app.InstrumentationRegistry
import com.elainalisten.tts.AzureConnectionResult
import com.elainalisten.tts.AzureCredentials
import com.elainalisten.tts.AzureCredentialsStore
import com.elainalisten.tts.AzureSpeech
import com.elainalisten.tts.AppSpeech
import com.elainalisten.tts.AzureAudioCache
import com.elainalisten.tts.AzureRequestLimiter
import com.elainalisten.tts.SpeechAudio
import com.elainalisten.tts.retryAzureSynthesis
import kotlinx.coroutines.runBlocking
import com.elainalisten.tts.AzureSpeechException
import com.elainalisten.tts.Voice
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AzureSpeechTest {
    @Test
    fun fifteenthRequestFillsTheWindowAndLaterRequestsWaitForItToSlide() = runBlocking {
        var now = 0L
        val waits = mutableListOf<Long>()
        val limiter = AzureRequestLimiter(nowMs = { now }, wait = { waits += it; now += it })
        repeat(15) {
            limiter.acquire()
            now += 100
        }
        assertTrue(waits.isEmpty())
        limiter.acquire()
        limiter.acquire()
        assertEquals(listOf(58_500L, 100L), waits)
    }

    @Test
    fun retryRespectsServerDelayAndStopsOnPermanentErrorsOrAfterFourRetries() = runBlocking {
        val waits = mutableListOf<Long>()
        var attempts = 0
        val terminal = runCatching {
            retryAzureSynthesis(wait = { waits += it }) {
                attempts++
                throw AzureSpeechException(AzureSpeechException.Reason.Quota, 429,
                    retryAfterMs = if (attempts == 2) 65_000 else null)
            }
        }.exceptionOrNull() as AzureSpeechException
        assertEquals(429, terminal.httpStatus)
        assertEquals(5, attempts)
        assertEquals(listOf(2_000L, 65_000L, 8_000L, 16_000L), waits)

        waits.clear()
        attempts = 0
        val credentials = runCatching {
            retryAzureSynthesis(wait = { waits += it }) {
                attempts++
                throw AzureSpeechException(AzureSpeechException.Reason.Credentials, 401)
            }
        }.exceptionOrNull() as AzureSpeechException
        assertEquals(1, attempts)
        assertTrue(waits.isEmpty())
        assertEquals(AzureSpeechException.Reason.Credentials, credentials.reason)
    }

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
            assertEquals("-key", reopened.maskedKeySuffix())
            val preferences = File(context.dataDir, "shared_prefs/azure_speech.xml").readText()
            assertFalse(preferences.contains(key))
            val result = AzureConnectionResult(1000, AzureSpeechException.Reason.Quota, 429)
            reopened.rememberTest(result)
            assertEquals(result, AzureCredentialsStore(context).lastTest)
            reopened.save("westus", key)
            assertNull(reopened.lastTest)
            reopened.rememberTest(AzureConnectionResult(2000))
            reopened.clear()
            assertNull(first.lastTest)
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

    @Test
    fun synthesisRetainsTheActualHttpStatus() = runBlocking {
        val cases = mapOf(401 to AzureSpeechException.Reason.Credentials,
            429 to AzureSpeechException.Reason.Quota, 503 to AzureSpeechException.Reason.Network)
        for ((code, reason) in cases) {
            val failure = runCatching {
                AzureSpeech.synthesize(AzureCredentials("eastasia", "test-key"), Voice.AzureXiaoxiao, "测试") { url ->
                    assertEquals("eastasia.tts.speech.microsoft.com", url.host)
                    ErrorConnection(url, code)
                }
            }.exceptionOrNull() as AzureSpeechException
            assertEquals(code, failure.httpStatus)
            assertEquals(reason, failure.reason)
            assertEquals(if (code == 429) 5_000L else null, failure.retryAfterMs)
        }
    }

    private class ErrorConnection(url: URL, private val status: Int) : HttpsURLConnection(url) {
        override fun getResponseCode() = status
        override fun getHeaderField(name: String?) = if (status == 429 && name == "Retry-After") "5" else null
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = ""
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate>? = null
    }
}
