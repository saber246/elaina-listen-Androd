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
    // Reuse one native engine for the process. The runtime's native destroy can
    // stall after WebView and LiteRT have both run in the Android process.
    private var engine: TextToSpeech? = null

    suspend fun synthesize(
        context: Context,
        text: String,
        voice: Voice,
        onAudio: suspend (SpeechAudio, index: Int, total: Int) -> Unit,
    ) = synthesizeSegments(context, SpeechText.split(text), voice, onAudio)

    suspend fun synthesizeSegments(
        context: Context,
        segments: List<String>,
        voice: Voice,
        onAudio: suspend (SpeechAudio, index: Int, total: Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(segments.isNotEmpty())
        session.withLock {
            val initialized = engine ?: createEngine(context).also { engine = it }
            val synthesis = SynthesisOptions(voiceId = voice.id)
            segments.forEachIndexed { index, segment ->
                ensureActive()
                val audio = initialized.synthesize(segment, synthesis)
                ensureActive()
                check(audio.samples.isNotEmpty() && audio.sampleRateHz > 0) {
                    "Kokoro returned no audio"
                }
                val speech = SpeechAudio(audio.samples, audio.sampleRateHz).withSpeechRate(voice.rate)
                onAudio(speech, index + 1, segments.size)
            }
        }
    }

    private suspend fun createEngine(context: Context): TextToSpeech {
        val model = prepareModel(context)
        val cache = File(context.cacheDir, "kokoro-cpu").apply { mkdirs() }
        val options = TextToSpeechOptions(
            baseOptions = BaseOptions.builder()
                .setModelAssetPath(model.absolutePath)
                .setDelegate(Delegate.CPU)
                .build(),
            defaultVoiceId = Voice.default.id,
            cacheDir = cache.absolutePath,
        )
        return TextToSpeech.create(options).also { initialized ->
            val available = initialized.listVoices().map { it.voiceId }.toSet()
            check(Voice.local.all { it.id in available }) {
                "The installed Kokoro model does not contain the configured voices"
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
