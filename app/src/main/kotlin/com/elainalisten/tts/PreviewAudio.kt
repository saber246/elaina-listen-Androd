package com.elainalisten.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal object PreviewAudio {
    suspend fun play(audio: SpeechAudio) {
        val channel = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_FLOAT
        val minimumBuffer = AudioTrack.getMinBufferSize(audio.sampleRateHz, channel, encoding)
        check(minimumBuffer > 0) { "Unsupported speech audio format" }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(audio.sampleRateHz)
                    .setChannelMask(channel)
                    .setEncoding(encoding)
                    .build(),
            )
            .setBufferSizeInBytes(minimumBuffer)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED) { "Cannot initialize audio output" }
            val durationMillis = audio.samples.size * 1_000L / audio.sampleRateHz
            val drained = withTimeoutOrNull(durationMillis + 10_000L) {
                track.play()
                var offset = 0
                while (offset < audio.samples.size) {
                    val written = track.write(
                        audio.samples,
                        offset,
                        audio.samples.size - offset,
                        AudioTrack.WRITE_NON_BLOCKING,
                    )
                    check(written >= 0) { "Audio output failed: $written" }
                    offset += written
                    if (offset < audio.samples.size) delay(10)
                }
                while (track.playbackHeadPosition.toUInt().toLong() < offset) delay(10)
                true
            }
            check(drained == true) { "Timed out while playing speech" }
        } finally {
            track.release()
        }
    }
}
