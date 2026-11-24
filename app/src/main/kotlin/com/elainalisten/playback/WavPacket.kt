package com.elainalisten.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.elainalisten.tts.SpeechAudio
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun SpeechAudio.toWavBytes(): ByteArray {
    val payload = samples.size * 2
    val bytes = ByteBuffer.allocate(44 + payload).order(ByteOrder.LITTLE_ENDIAN)
    bytes.put("RIFF".toByteArray(Charsets.US_ASCII))
    bytes.putInt(36 + payload)
    bytes.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
    bytes.putInt(16)
    bytes.putShort(1)
    bytes.putShort(1)
    bytes.putInt(sampleRateHz)
    bytes.putInt(sampleRateHz * 2)
    bytes.putShort(2)
    bytes.putShort(16)
    bytes.put("data".toByteArray(Charsets.US_ASCII))
    bytes.putInt(payload)
    samples.forEach { bytes.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
    return bytes.array()
}

@OptIn(UnstableApi::class)
internal fun wavMediaSource(file: File, offset: Long, length: Long): MediaSource {
    val media = MediaItem.Builder().setUri(Uri.fromFile(file)).setMimeType(MimeTypes.AUDIO_WAV).build()
    return ProgressiveMediaSource.Factory { WavPacketDataSource(file, offset, length) }
        .createMediaSource(media)
}

// Each Media3 item sees one bounded WAV packet in a single private, append-only session file.
@OptIn(UnstableApi::class)
private class WavPacketDataSource(
    private val file: File,
    private val offset: Long,
    private val length: Long,
) : BaseDataSource(false) {
    private var reader: RandomAccessFile? = null
    private var currentUri: Uri? = null
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        if (dataSpec.position > length) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        val fileReader = RandomAccessFile(file, "r")
        try {
            fileReader.seek(offset + dataSpec.position)
        } catch (error: Exception) {
            fileReader.close()
            throw error
        }
        val available = length - dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(available, dataSpec.length)
        reader = fileReader
        currentUri = dataSpec.uri
        transferStarted(dataSpec)
        return remaining
    }

    override fun getUri(): Uri? = currentUri

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = checkNotNull(reader).read(buffer, offset, minOf(remaining, length.toLong()).toInt())
        if (count < 0) throw EOFException("Speech packet ended prematurely")
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun close() {
        currentUri = null
        val opened = reader ?: return
        reader = null
        try {
            opened.close()
        } finally {
            transferEnded()
        }
    }
}
