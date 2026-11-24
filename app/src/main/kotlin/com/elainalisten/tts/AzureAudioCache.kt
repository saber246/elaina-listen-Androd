package com.elainalisten.tts

import android.content.Context
import android.util.AtomicFile
import androidx.core.content.edit
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AzureAudioCache(context: Context) {
    private val directory = File(context.cacheDir, "azure-audio")
    private val settings = context.getSharedPreferences("audio_cache", Context.MODE_PRIVATE)

    val limitMb: Int get() = settings.getInt("limit_mb", 200)

    fun setLimitMb(megabytes: Int) = synchronized(disk) {
        val limit = megabytes.coerceIn(50, 500)
        settings.edit { putInt("limit_mb", limit) }
        prune(limit.toLong() * megabyte)
    }

    suspend fun getOrSynthesize(
        voice: Voice,
        segmentLength: Int,
        text: String,
        synthesize: suspend () -> SpeechAudio,
    ): SpeechAudio = requests.withLock {
        val file = File(directory, key(voice, segmentLength, text))
        val generation = synchronized(disk) {
            read(file)?.let { return@withLock it }
            clearGeneration
        }
        val audio = synthesize()
        synchronized(disk) {
            if (generation == clearGeneration) {
                write(file, audio)
                prune(limitMb.toLong() * megabyte)
            }
        }
        audio
    }

    fun sizeBytes(): Long = synchronized(disk) { directory.listFiles()?.sumOf(File::length) ?: 0L }

    fun clear(): Long = synchronized(disk) {
        clearGeneration++
        directory.listFiles().orEmpty().filter(File::isFile).sumOf { file ->
            val size = file.length()
            if (file.delete()) size else 0
        }
    }

    private fun read(file: File): SpeechAudio? {
        if (!file.isFile) return null
        return try {
            val byteCount = file.length()
            if (byteCount !in 2..maxAudioBytes || byteCount % 2 != 0L) throw IOException("Invalid PCM cache")
            DataInputStream(file.inputStream().buffered()).use { data ->
                SpeechAudio(FloatArray((byteCount / 2).toInt()) { data.readShort() / 32768f }, sampleRate)
            }.also { file.setLastModified(System.currentTimeMillis()) }
        } catch (_: IOException) {
            file.delete()
            null
        }
    }

    private fun write(file: File, audio: SpeechAudio) {
        if (audio.sampleRateHz != sampleRate || audio.samples.isEmpty()) return
        directory.mkdirs()
        val atomic = AtomicFile(file)
        try {
            val stream = atomic.startWrite()
            try {
                val data = DataOutputStream(stream.buffered())
                for (sample in audio.samples) data.writeShort((sample * 32768).toInt().coerceIn(-32768, 32767))
                data.flush()
                atomic.finishWrite(stream)
            } catch (_: IOException) {
                atomic.failWrite(stream)
            }
        } catch (_: IOException) {
            // Playback can still use the freshly synthesized audio when disk space is low.
        }
    }

    private fun prune(limit: Long) {
        val files = directory.listFiles().orEmpty().filter(File::isFile)
        var bytes = files.sumOf(File::length)
        if (bytes <= limit) return
        for (file in files.sortedBy(File::lastModified)) {
            val size = file.length()
            if (file.delete()) bytes -= size
            if (bytes <= limit) return
        }
    }

    private fun key(voice: Voice, segmentLength: Int, text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("2\u0000${voice.id}\u0000$segmentLength\u0000$text".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) } + ".pcm"

    private companion object {
        val disk = Any()
        val requests = Mutex()
        var clearGeneration = 0L
        const val megabyte = 1_000_000
        const val sampleRate = 24_000
        const val maxAudioBytes = 8_388_608L
    }
}
