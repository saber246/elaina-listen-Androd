package com.elainalisten.tts

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Xml
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

internal class AzureCredentials(val region: String, val key: String)

internal class AzureSpeechException(val reason: Reason, val httpStatus: Int? = null) : Exception() {
    enum class Reason { Credentials, Quota, Network }
}

internal data class AzureConnectionResult(
    val checkedAt: Long,
    val reason: AzureSpeechException.Reason? = null,
    val httpStatus: Int? = null,
)

internal class AzureCredentialsStore(context: Context) {
    private val prefs = context.getSharedPreferences("azure_speech", Context.MODE_PRIVATE)
    val region: String? get() = prefs.getString("region", null)
    val lastTest: AzureConnectionResult?
        get() {
            val checkedAt = prefs.getLong("test_time", 0)
            if (checkedAt == 0L) return null
            val reason = prefs.getString("test_reason", null)?.let { name ->
                AzureSpeechException.Reason.entries.firstOrNull { it.name == name } ?: return null
            }
            val httpStatus = prefs.getInt("test_http_status", 0).takeIf { it in 100..599 }
            return AzureConnectionResult(checkedAt, reason, httpStatus)
        }

    fun save(region: String, key: String) {
        require(regionPattern.matches(region) && keyPattern.matches(key))
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getKey(create = true))
        val payload = JSONObject().put("region", region).put("key", key)
        val encrypted = cipher.iv + cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("region", region)
            .putString("credential", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .remove("test_time").remove("test_reason").remove("test_http_status").commit())
    }

    fun load(): AzureCredentials {
        try {
            val encoded = prefs.getString("credential", null) ?: error("Missing credential")
            val encrypted = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(transformation)
            cipher.init(Cipher.DECRYPT_MODE, getKey(create = false), GCMParameterSpec(128, encrypted, 0, 12))
            val payload = JSONObject(cipher.doFinal(encrypted, 12, encrypted.size - 12).toString(Charsets.UTF_8))
            val region = payload.getString("region")
            check(regionPattern.matches(region))
            return AzureCredentials(region, payload.getString("key"))
        } catch (_: Exception) {
            throw AzureSpeechException(AzureSpeechException.Reason.Credentials)
        }
    }

    fun maskedKeySuffix(): String? = try {
        load().key.let { if (it.length > 4) it.takeLast(4) else "" }
    } catch (_: AzureSpeechException) {
        null
    }

    fun clear() {
        check(prefs.edit().remove("region").remove("credential")
            .remove("test_time").remove("test_reason").remove("test_http_status").commit())
    }

    fun rememberTest(result: AzureConnectionResult) {
        prefs.edit().putLong("test_time", result.checkedAt).putString("test_reason", result.reason?.name)
            .putInt("test_http_status", result.httpStatus ?: 0).apply()
    }

    private fun getKey(create: Boolean): SecretKey = synchronized(AzureCredentialsStore::class.java) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null) return@synchronized existing
        check(create)
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    companion object {
        val regionPattern = Regex("[a-z][a-z0-9]{1,31}")
        val keyPattern = Regex("[A-Za-z0-9._~+/=-]{1,512}")
        private const val alias = "elaina-azure-speech"
        private const val transformation = "AES/GCM/NoPadding"
    }
}

internal data class SpeechAudio(val samples: FloatArray, val sampleRateHz: Int)

internal fun interface SpeechSynthesizer {
    suspend fun synthesize(context: Context, text: String, voice: Voice): SpeechAudio
}

internal object SpeechSynthesis {
    suspend fun synthesizeSegments(
        context: Context,
        segments: List<String>,
        voice: Voice,
        synthesizer: SpeechSynthesizer,
        segmentLength: Int = SpeechText.defaultSegmentLength,
        beforeSegment: suspend (Int) -> Unit = {},
        onAudio: suspend (SpeechAudio, index: Int, total: Int) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            segments.forEachIndexed { index, segment ->
                ensureActive()
                beforeSegment(index)
                val audio = synthesize(context, segment, voice, synthesizer, segmentLength)
                ensureActive()
                onAudio(audio, index + 1, segments.size)
            }
        }
    }

    suspend fun synthesize(
        context: Context,
        text: String,
        voice: Voice,
        synthesizer: SpeechSynthesizer,
        segmentLength: Int,
    ): SpeechAudio = if (synthesizer === AppSpeech) AppSpeech.synthesize(context, text, voice, segmentLength)
        else synthesizer.synthesize(context, text, voice)
}

internal object AzureSpeech : SpeechSynthesizer {
    override suspend fun synthesize(context: Context, text: String, voice: Voice): SpeechAudio =
        synthesize(AzureCredentialsStore(context).load(), voice, text)

    private const val sampleRate = 24_000
    private const val maxAudioBytes = 8 * 1024 * 1024

    suspend fun testConnection(credentials: AzureCredentials, voice: Voice) {
        withContext(Dispatchers.IO) { synthesize(credentials, voice, "语音连接测试。") }
    }

    suspend fun synthesize(
        credentials: AzureCredentials,
        voice: Voice,
        text: String,
        openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    ): SpeechAudio {
        val region = credentials.region
        require(AzureCredentialsStore.regionPattern.matches(region))
        val url = URL("https://${region}.tts.speech.microsoft.com/cognitiveservices/v1")
        val connection = try {
            openConnection(url)
        } catch (_: IOException) {
            throw AzureSpeechException(AzureSpeechException.Reason.Network)
        }
        try {
            val body = ssml(voice, text).toByteArray(Charsets.UTF_8)
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Ocp-Apim-Subscription-Key", credentials.key)
            connection.setRequestProperty("Content-Type", "application/ssml+xml")
            connection.setRequestProperty("X-Microsoft-OutputFormat", "raw-24khz-16bit-mono-pcm")
            connection.setRequestProperty("User-Agent", "ElainaListen")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                val reason = when (status) {
                    401, 403 -> AzureSpeechException.Reason.Credentials
                    402, 429 -> AzureSpeechException.Reason.Quota
                    else -> AzureSpeechException.Reason.Network
                }
                throw AzureSpeechException(reason, status.takeIf { it in 100..599 })
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= maxAudioBytes)
                    output.write(buffer, 0, count)
                }
            }
            return decodePcm(output.toByteArray())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: AzureSpeechException) {
            throw error
        } catch (_: Exception) {
            throw AzureSpeechException(AzureSpeechException.Reason.Network)
        } finally {
            connection.disconnect()
        }
    }

    fun ssml(voice: Voice, text: String): String {
        val writer = StringWriter()
        val xml = Xml.newSerializer()
        val namespace = "http://www.w3.org/2001/10/synthesis"
        xml.setOutput(writer)
        xml.setPrefix("", namespace)
        xml.startTag(namespace, "speak")
        xml.attribute(null, "version", "1.0")
        xml.attribute("http://www.w3.org/XML/1998/namespace", "lang", "zh-CN")
        xml.startTag(namespace, "voice")
        xml.attribute(null, "name", voice.id)
        xml.text(text.filter { it >= ' ' || it in "\t\r\n" }.replace("\uFFFE", "").replace("\uFFFF", ""))
        xml.endTag(namespace, "voice")
        xml.endTag(namespace, "speak")
        xml.flush()
        return writer.toString()
    }

    fun decodePcm(bytes: ByteArray): SpeechAudio {
        require(bytes.isNotEmpty() && bytes.size % 2 == 0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return SpeechAudio(FloatArray(bytes.size / 2) { buffer.short / 32768f }, sampleRate)
    }
}
