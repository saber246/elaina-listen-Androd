package com.elainalisten.playback

import android.app.Application
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.elainalisten.article.ReadableArticle
import com.elainalisten.tts.KokoroTts
import com.elainalisten.tts.SpeechText
import com.elainalisten.tts.Voice
import com.elainalisten.tts.VoicePreferences
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ReadingProblem { Synthesis, Playback }

internal data class ReadingState(
    val article: ReadableArticle? = null,
    val chapterIndex: Int = 0,
    val voice: Voice = Voice.default,
    val speed: Float = 1f,
    val playRequested: Boolean = false,
    val playing: Boolean = false,
    val generating: Boolean = false,
    val readyParts: Int = 0,
    val totalParts: Int = 0,
    val paragraphIndex: Int = 0,
    val positionMs: Long = 0,
    val readyDurationMs: Long = 0,
    val problem: ReadingProblem? = null,
)

@OptIn(UnstableApi::class)
internal class ReadingViewModel(private val application: Application) : AndroidViewModel(application) {
    private val voicePreferences = VoicePreferences(application)
    private val mutableState = MutableStateFlow(ReadingState())
    val state = mutableState.asStateFlow()
    private val mutablePreview = MutableStateFlow<ReadableArticle?>(null)
    val preview = mutablePreview.asStateFlow()

    private val player = ExoPlayer.Builder(application)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
    private var active: Reading? = null
    private var writer: Job? = null
    private val packets = mutableListOf<Packet>()

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = refresh()

            override fun onPlayerError(error: PlaybackException) {
                player.pause()
                writer?.cancel()
                mutableState.update { it.copy(problem = ReadingProblem.Playback, generating = false) }
                refresh()
            }
        })
        viewModelScope.launch {
            while (true) {
                delay(250)
                if (player.isPlaying) refresh()
            }
        }
    }

    fun showArticle(article: ReadableArticle) {
        mutablePreview.value = article
    }

    fun playArticle(article: ReadableArticle, chapter: Int = 0, paragraph: Int = 0) {
        showArticle(article)
        startChapter(article, chapter, paragraph, voicePreferences.defaultVoice)
    }

    fun selectChapter(index: Int) {
        val current = state.value
        val article = current.article ?: return
        if (index in article.chapters.indices) startChapter(article, index, 0, current.voice)
    }

    fun selectVoice(voice: Voice) {
        val reading = active ?: return
        if (state.value.voice == voice) return
        val offset = player.currentMediaItemIndex.coerceIn(0, packets.lastIndex.coerceAtLeast(0))
        val fraction = packets.getOrNull(offset)?.let { player.currentPosition.toDouble() / it.durationMs } ?: 0.0
        val next = reading.copy(
            first = (reading.first + offset).coerceAtMost(reading.parts.lastIndex),
            voice = voice,
            file = cacheFile(),
            initialFraction = fraction.coerceIn(0.0, 1.0),
        )
        begin(next, state.value.playRequested)
    }

    fun setSpeed(speed: Float) {
        val adjusted = speed.coerceIn(0.8f, 2.5f)
        player.setPlaybackSpeed(adjusted)
        mutableState.update { it.copy(speed = adjusted) }
    }

    fun togglePlayback() {
        val reading = active ?: return
        val current = state.value
        if (current.playRequested) {
            pause()
            return
        }
        if (current.problem != null) {
            begin(reading.copy(file = cacheFile()), autoplay = true)
            return
        }
        if (player.playbackState == Player.STATE_ENDED && current.readyParts == current.totalParts) {
            player.seekTo(0, 0)
        }
        player.play()
        if (!current.generating && current.readyParts < current.totalParts) launchWriter(reading)
        refresh()
    }

    fun pause() {
        player.pause()
        refresh()
    }

    suspend fun pauseForPreview() {
        pause()
        val currentWriter = writer
        mutableState.update { it.copy(generating = false) }
        currentWriter?.cancelAndJoin()
        refresh()
    }

    fun seekBy(deltaMs: Long) {
        refresh()
        seekTo(state.value.positionMs + deltaMs)
    }

    fun seekTo(positionMs: Long) {
        val duration = state.value.readyDurationMs
        if (packets.isEmpty() || duration <= 0) return
        val target = positionMs.coerceIn(0, duration - 1)
        val index = packets.indexOfLast { it.startMs <= target }.coerceAtLeast(0)
        player.seekTo(index, target - packets[index].startMs)
        refresh()
    }

    private fun startChapter(article: ReadableArticle, chapter: Int, paragraph: Int, voice: Voice) {
        val parts = article.chapters.getOrNull(chapter)?.paragraphs?.flatMapIndexed { index, text ->
            SpeechText.split(text).map { Part(index, it) }
        }.orEmpty()
        if (parts.isEmpty()) return
        val first = parts.indexOfFirst { it.paragraph >= paragraph }.coerceAtLeast(0)
        begin(Reading(article, chapter, parts, first, voice, cacheFile()), autoplay = true)
    }

    private fun begin(reading: Reading, autoplay: Boolean) {
        player.stop()
        player.clearMediaItems()
        retire()
        packets.clear()
        active = reading
        mutableState.value = ReadingState(
            article = reading.article,
            chapterIndex = reading.chapter,
            voice = reading.voice,
            speed = state.value.speed,
            totalParts = reading.parts.size - reading.first,
            paragraphIndex = reading.parts[reading.first].paragraph,
        )
        player.playWhenReady = autoplay
        launchWriter(reading)
        refresh()
    }

    private fun launchWriter(reading: Reading) {
        val previous = writer
        val startPart = reading.first + packets.size
        val startByte = packets.lastOrNull()?.endByte ?: 0
        val texts = reading.parts.drop(startPart).map(Part::text)
        if (texts.isEmpty()) return
        mutableState.update { it.copy(generating = true) }
        writer = viewModelScope.launch {
            try {
                previous?.join()
                withContext(Dispatchers.IO) {
                    RandomAccessFile(reading.file, "rw").use { output ->
                        output.setLength(startByte)
                        output.seek(startByte)
                        KokoroTts.synthesizeSegments(application, texts, reading.voice) { audio, _, _ ->
                            val bytes = audio.toWavBytes()
                            val offset = output.filePointer
                            output.write(bytes)
                            val duration = (audio.samples.size * 1_000L / audio.sampleRateHz).coerceAtLeast(1)
                            withContext(Dispatchers.Main.immediate) {
                                if (active === reading) append(reading, offset, bytes.size.toLong(), duration)
                            }
                        }
                    }
                }
                if (active === reading) {
                    mutableState.update { it.copy(generating = false) }
                    refresh()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (active === reading) failSynthesis()
            } catch (error: LinkageError) {
                if (active === reading) failSynthesis()
            }
        }
    }

    private fun append(reading: Reading, offset: Long, bytes: Long, duration: Long) {
        val index = packets.size
        val wasWaiting = index > 0 && player.playbackState == Player.STATE_ENDED
        val start = state.value.readyDurationMs
        packets += Packet(start, duration, offset + bytes)
        mutableState.update { it.copy(readyParts = index + 1, readyDurationMs = start + duration) }
        player.addMediaSource(wavMediaSource(reading.file, offset, bytes))
        if (index == 0) {
            if (reading.initialFraction > 0) {
                player.seekTo(0, (duration * reading.initialFraction).toLong().coerceAtMost(duration - 1))
            }
            player.prepare()
        } else if (wasWaiting) {
            player.seekTo(index, 0)
        }
        refresh()
    }

    private fun refresh() {
        val reading = active ?: return
        val index = player.currentMediaItemIndex.coerceIn(0, packets.lastIndex.coerceAtLeast(0))
        val position = packets.getOrNull(index)?.let {
            it.startMs + player.currentPosition.coerceIn(0, it.durationMs)
        } ?: 0L
        mutableState.update { current ->
            current.copy(
                positionMs = position,
                paragraphIndex = reading.parts[reading.first + index].paragraph,
                playing = player.isPlaying,
                playRequested = player.playWhenReady &&
                    (player.playbackState != Player.STATE_ENDED || current.generating),
            )
        }
    }

    private fun failSynthesis() {
        player.pause()
        mutableState.update { it.copy(generating = false, problem = ReadingProblem.Synthesis) }
        refresh()
    }

    private fun retire() {
        val file = active?.file
        val job = writer
        active = null
        writer = null
        job?.cancel()
        if (file != null) {
            if (job == null) file.delete()
            else job.invokeOnCompletion { file.delete() }
        }
    }

    private fun cacheFile() = File(application.cacheDir, "reading-${UUID.randomUUID()}.wav")

    override fun onCleared() {
        player.release()
        retire()
    }

    private data class Part(val paragraph: Int, val text: String)
    private data class Packet(val startMs: Long, val durationMs: Long, val endByte: Long)
    private data class Reading(
        val article: ReadableArticle,
        val chapter: Int,
        val parts: List<Part>,
        val first: Int,
        val voice: Voice,
        val file: File,
        val initialFraction: Double = 0.0,
    )
}
