package com.elainalisten.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object AppSpeech : SpeechSynthesizer {
    override suspend fun synthesize(context: Context, text: String, voice: Voice): SpeechAudio =
        if (voice.isOffline) MatchaSpeech.synthesize(context, text) else AzureSpeech.synthesize(context, text, voice)
}

internal object MatchaSpeech {
    private const val root = "matcha"
    private val lock = Mutex()
    private var engine: OfflineTts? = null

    suspend fun synthesize(context: Context, text: String): SpeechAudio = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        lock.withLock {
            job.ensureActive()
            val tts = engine ?: createEngine(context).also { engine = it }
            val result = tts.generate(text, sid = 0, speed = 1f)
            job.ensureActive()
            check(result.samples.isNotEmpty() && result.sampleRate > 0) { "Matcha did not produce audio" }
            SpeechAudio(result.samples, result.sampleRate)
        }
    }

    private fun createEngine(context: Context): OfflineTts = OfflineTts(
        assetManager = context.applicationContext.assets,
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                matcha = OfflineTtsMatchaModelConfig(
                    acousticModel = "$root/model-steps-3.onnx",
                    vocoder = "$root/vocos-22khz-univ.onnx",
                    lexicon = "$root/lexicon.txt",
                    tokens = "$root/tokens.txt",
                ),
                numThreads = 2,
                provider = "cpu",
            ),
            ruleFsts = listOf("phone.fst", "date.fst", "number.fst").joinToString(",") { "$root/$it" },
            maxNumSentences = 1,
        ),
    )
}
