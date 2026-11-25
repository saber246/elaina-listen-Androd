package com.elainalisten.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object AppSpeech : SpeechSynthesizer {
    override suspend fun synthesize(context: Context, text: String, voice: Voice): SpeechAudio =
        synthesize(context, text, voice, SpeechText.defaultSegmentLength)

    suspend fun synthesize(context: Context, text: String, voice: Voice, segmentLength: Int): SpeechAudio =
        if (voice.isOffline) MatchaSpeech.synthesize(context, text)
        else withContext(Dispatchers.IO) {
            AzureAudioCache(context).getOrSynthesize(voice, segmentLength, text) {
                AzureSpeech.synthesize(context, text, voice)
            }
        }
}

internal object MatchaSpeech {
    private const val root = "matcha"
    private val lock = Mutex()
    private var engine: OfflineTts? = null
    const val sampleRateHz = 22_050

    suspend fun synthesize(context: Context, text: String): SpeechAudio = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        lock.withLock {
            job.ensureActive()
            val tts = engine ?: initialize(context)
            val result = try {
                tts.generate(text, sid = 0, speed = 1f)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw MatchaSentenceException(MatchaSentenceFailure.Generation, error)
            } catch (error: LinkageError) {
                throw MatchaEngineException(error)
            }
            job.ensureActive()
            if (result.samples.isEmpty() || result.sampleRate <= 0) {
                throw MatchaSentenceException(MatchaSentenceFailure.EmptyAudio)
            }
            SpeechAudio(result.samples, result.sampleRate)
        }
    }

    private fun initialize(context: Context): OfflineTts = try {
        createEngine(context).also { engine = it }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        throw MatchaEngineException(error)
    } catch (error: LinkageError) {
        throw MatchaEngineException(error)
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

internal enum class MatchaSentenceFailure { EmptyAudio, Generation }
internal class MatchaSentenceException(val reason: MatchaSentenceFailure, cause: Throwable? = null) : Exception(cause)
internal class MatchaEngineException(cause: Throwable) : Exception(cause)
