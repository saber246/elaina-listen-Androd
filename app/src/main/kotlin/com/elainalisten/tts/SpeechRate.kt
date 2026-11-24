package com.elainalisten.tts

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(UnstableApi::class)
internal fun SpeechAudio.withSpeechRate(speed: Float): SpeechAudio {
    if (speed == 1f) return this
    val sonic = SonicAudioProcessor()
    sonic.setSpeed(speed)
    sonic.configure(AudioProcessor.AudioFormat(sampleRateHz, 1, C.ENCODING_PCM_FLOAT))
    sonic.flush(AudioProcessor.StreamMetadata.DEFAULT)
    val input = ByteBuffer.allocateDirect(samples.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    input.asFloatBuffer().put(samples)
    sonic.queueInput(input)
    sonic.queueEndOfStream()
    val output = sonic.output.order(ByteOrder.nativeOrder()).asFloatBuffer()
    val adjusted = FloatArray(output.remaining()).also { output.get(it) }
    return SpeechAudio(adjusted, sampleRateHz)
}
