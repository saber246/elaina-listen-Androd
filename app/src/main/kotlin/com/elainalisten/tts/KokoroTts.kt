package com.elainalisten.tts

import android.content.Context
import com.google.mediapipe.tasks.audio.texttospeech.SynthesisOptions
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeech
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeechOptions
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class SpeechAudio(val samples: FloatArray, val sampleRateHz: Int)

internal object KokoroTts {
    private const val modelAsset = "kokoro_int8_fp16.litertlm"
    private const val modelFile = "kokoro-int8-fp16-e7b931ee.litertlm"
    private const val modelBytes = 294_783_298L
    private val session = Mutex()

    suspend fun synthesize(
        context: Context,
        text: String,
        voice: Voice,
        onAudio: suspend (SpeechAudio, index: Int, total: Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val segments = SpeechText.split(text)
        require(segments.isNotEmpty())
        session.withLock {
            val model = prepareModel(context)
            val cache = File(context.cacheDir, "kokoro-cpu").apply { mkdirs() }
            val options = TextToSpeechOptions(
                baseOptions = BaseOptions.builder()
                    .setModelAssetPath(model.absolutePath)
                    .setDelegate(Delegate.CPU)
                    .build(),
                defaultVoiceId = voice.id,
                speakingRate = voice.rate,
                cacheDir = cache.absolutePath,
            )
            TextToSpeech.create(options).use { engine ->
                val bundledVoices = engine.listVoices().map { it.voiceId }.toSet()
                check(Voice.entries.all { it.id in bundledVoices }) {
                    "The installed Kokoro model does not contain the configured voices"
                }
                val synthesis = SynthesisOptions(voiceId = voice.id)
                segments.forEachIndexed { index, segment ->
                    ensureActive()
                    // Keep the engine open until the bounded native call returns, even on Stop.
                    val audio = engine.synthesize(segment, synthesis)
                    ensureActive()
                    check(audio.samples.isNotEmpty() && audio.sampleRateHz > 0) {
                        "Kokoro returned no audio"
                    }
                    onAudio(SpeechAudio(audio.samples, audio.sampleRateHz), index + 1, segments.size)
                }
            }
        }
    }

    private suspend fun prepareModel(context: Context): File {
        val directory = File(context.noBackupFilesDir, "kokoro").apply { mkdirs() }
        val model = File(directory, modelFile)
        if (model.length() == modelBytes) return model

        val temporary = File.createTempFile("kokoro-", ".part", directory)
        try {
            val job = currentCoroutineContext()
            context.assets.open(modelAsset).use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        job.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(temporary.length() == modelBytes) { "Incomplete Kokoro model asset" }
            Files.move(temporary.toPath(), model.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            return model
        } finally {
            temporary.delete()
        }
    }
}
