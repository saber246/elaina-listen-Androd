package com.elainalisten.playback

import android.app.Application
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.annotation.OptIn
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.withTransaction
import com.elainalisten.ElainaApplication
import com.elainalisten.R
import com.elainalisten.article.ArticleExtractor
import com.elainalisten.article.PageContent
import com.elainalisten.article.PixivNovel
import com.elainalisten.article.PixivSeries
import com.elainalisten.article.ReadableArticle
import com.elainalisten.browser.WebAddress
import com.elainalisten.browser.BrowserSession
import com.elainalisten.data.Bookmark
import com.elainalisten.data.BookmarkDatabase
import com.elainalisten.data.PixivCatalog
import com.elainalisten.data.BookmarkFolder
import com.elainalisten.data.QueueEntry
import com.elainalisten.data.ReadingResume
import com.elainalisten.tts.AppSpeech
import com.elainalisten.tts.AzureSpeechException
import com.elainalisten.tts.MatchaEngineException
import com.elainalisten.tts.MatchaSentenceException
import com.elainalisten.tts.MatchaSentenceFailure
import com.elainalisten.tts.MatchaSpeech
import com.elainalisten.tts.SpeechAudio
import com.elainalisten.tts.SpeechSynthesis
import com.elainalisten.tts.SpeechText
import com.elainalisten.tts.Voice
import com.elainalisten.tts.VoicePreferences
import com.elainalisten.tts.retryAzureSynthesis
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class ReadingProblem { Synthesis, Playback, NextPage, Queue, PixivSeries }

internal data class SeriesImportProgress(val saved: Int, val total: Int, val added: Int)
internal data class MatchaNotice(val paragraph: Int, val sentence: Int, val reason: MatchaSentenceFailure)

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
    val sentenceRange: IntRange? = null,
    val positionMs: Long = 0,
    val readyDurationMs: Long = 0,
    val estimatedTotalMs: Long = 0,
    val problem: ReadingProblem? = null,
    val turningPage: Boolean = false,
    val navigationCancelled: Boolean = false,
    val bookmarkId: String? = null,
    val queueEntryId: String? = null,
    val azureProblem: AzureSpeechException.Reason? = null,
    val azureHttpStatus: Int? = null,
    val azureFailureDetail: AzureSpeechException.Detail? = null,
    val matchaNotice: MatchaNotice? = null,
    val matchaEngineFailed: Boolean = false,
)

@OptIn(UnstableApi::class)
internal class ReadingViewModel(private val application: Application) : AndroidViewModel(application) {
    private val browser get() = (application as ElainaApplication).browser
    private val database = BookmarkDatabase.open(application)
    private val bookmarkDao = database.bookmarks()
    private val queueDao = database.queue()
    private val catalogDao = database.pixivCatalogs()
    private val bookmarkWrite = Mutex()
    val bookmarks = bookmarkDao.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val settings = application.getSharedPreferences("reading", android.content.Context.MODE_PRIVATE)
    val folders = bookmarkDao.observeFolders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val resume = bookmarkDao.observeResume().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val queue = queueDao.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val catalogs = catalogDao.observe().map { rows -> rows.associate { it.id to it.decode() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    private val mutableSeriesImports = MutableStateFlow<Map<String, SeriesImportProgress>>(emptyMap())
    val seriesImports = mutableSeriesImports.asStateFlow()
    val queueCursor = queueDao.observeCursor().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private var queueRequest: Job? = null
    private val mutableMix = MutableStateFlow(settings.getBoolean("mix_audio", false))
    val mixAudio = mutableMix.asStateFlow()
    private val mutableMuted = MutableStateFlow(settings.getBoolean("muted", false))
    val muted = mutableMuted.asStateFlow()
    private val mutableSegmentLength = MutableStateFlow(
        settings.getInt("segment_length", SpeechText.defaultSegmentLength).coerceIn(SpeechText.segmentLengths),
    )
    val segmentLength = mutableSegmentLength.asStateFlow()
    private val mutableAutomatic = MutableStateFlow(settings.getBoolean("automatic", true))
    val automatic = mutableAutomatic.asStateFlow()
    private val mutableSeriesAutomatic = MutableStateFlow(settings.getBoolean("pixiv_series_automatic", false))
    val seriesAutomatic = mutableSeriesAutomatic.asStateFlow()
    private val voicePreferences = VoicePreferences(application)
    private val mutableState = MutableStateFlow(ReadingState())
    val state = mutableState.asStateFlow()
    private val mutableSleepTimer = MutableStateFlow<SleepTimer?>(null)
    val sleepTimer = mutableSleepTimer.asStateFlow()
    private var sleepDeadlineMs = 0L
    private var sleepGain = 1f
    private var sleepAtChapterEnd = false
    private val mutablePreview = MutableStateFlow<ReadableArticle?>(null)
    val preview = mutablePreview.asStateFlow()

    private val audioManager = application.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build()
    private val player = ExoPlayer.Builder(application)
        .setAudioAttributes(attributes, !mixAudio.value)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(30_000)
        .build()
    private val removeCallListener: (() -> Unit)? =
        if (Build.VERSION.SDK_INT >= 31) registerCallListener() else null
    internal val mediaPlayer: Player get() = player
    private var pageTurn: Job? = null
    private var completed: Reading? = null
    private var readingTab: BrowserSession? = null
    private var followAllowed = true
    private val visitedPages = mutableSetOf<String>()
    private val spokenContent = mutableSetOf<String>()
    private var pagesRead = 0
    private var active: Reading? = null
    private var writer: Job? = null
    private val packets = mutableListOf<Packet>()

    init {
        viewModelScope.launch(Dispatchers.IO) { backfillCharacterCounts() }
        player.volume = if (muted.value) 0f else 1f
        (application as ElainaApplication).tabs.onManualNavigation = navigation@{ changed ->
            if (changed !== readingTab) return@navigation
            followAllowed = false
            if (pageTurn?.isActive == true) {
                cancelPageTurn()
                player.pause()
                mutableState.update { it.copy(navigationCancelled = true) }
                refresh()
            }
        }
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (!player.playWhenReady) cancelPageTurn()
                if (player.playWhenReady && player.playbackState == Player.STATE_ENDED &&
                    state.value.problem == ReadingProblem.Synthesis
                ) player.pause()
                refresh()
                if (events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                        Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED)) checkpoint()
            }

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
                updateSleepTimer()
                if (Build.VERSION.SDK_INT < 31 && player.playWhenReady && isCallMode(audioManager.mode)) pause()
                if (player.isPlaying) refresh()
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(2_000)
                if (player.isPlaying) checkpoint()
            }
        }
    }

    fun showArticle(article: ReadableArticle) {
        mutablePreview.value = article
    }

    fun playArticle(
        article: ReadableArticle,
        chapter: Int = 0,
        paragraph: Int = 0,
        voice: Voice = voicePreferences.defaultVoice,
    ) {
        queueRequest?.cancel()
        checkpoint()
        readingTab = browser.takeIf { article.sourceUrl != null }
        resetPageChain(article)
        showArticle(article)
        startChapter(article, chapter, paragraph, voice, Bookmark.idFor(article))
        startService()
    }

    fun selectChapter(index: Int) {
        val current = state.value
        val article = current.article ?: return
        if (index in article.chapters.indices) {
            checkpoint()
            cancelPageTurn()
            startChapter(article, index, 0, current.voice, current.bookmarkId ?: Bookmark.idFor(article),
                segmentLength = active?.segmentLength ?: segmentLength.value, queueId = active?.queueId)
            startService()
        }
    }

    fun selectVoice(voice: Voice) {
        val reading = active ?: return
        if (state.value.voice == voice) return
        cancelPageTurn()
        val offset = player.currentMediaItemIndex.coerceIn(0, packets.lastIndex.coerceAtLeast(0))
        val fraction = packets.getOrNull(offset)?.let { player.currentPosition.toDouble() / it.durationMs } ?: 0.0
        val next = reading.copy(
            first = (reading.first + offset).coerceAtMost(reading.parts.lastIndex),
            voice = voice,
            file = cacheFile(),
            initialFraction = fraction.coerceIn(0.0, 1.0),
            initialOffsetMs = 0,
            positionBase = reading.positionBase + (packets.getOrNull(offset)?.startMs ?: 0L),
        )
        begin(next, state.value.playRequested)
        if (state.value.playRequested) startService()
        checkpoint()
    }

    fun clearAzurePlayback() {
        if (active?.voice?.isOffline != false) return
        pause()
        writer?.cancel()
        mutableState.update { it.copy(generating = false) }
    }

    fun setSpeed(speed: Float) {
        val adjusted = speed.coerceIn(0.8f, 2.5f)
        player.setPlaybackSpeed(adjusted)
        mutableState.update { it.copy(speed = adjusted) }
        checkpoint()
    }

    fun togglePlayback() {
        val reading = active ?: return
        val current = state.value
        if (current.playRequested) {
            pause()
            return
        }
        if (current.problem == ReadingProblem.Queue) {
            playQueued(null)
            return
        }
        if (current.problem == ReadingProblem.NextPage || current.problem == ReadingProblem.PixivSeries) return
        if (current.problem == ReadingProblem.Synthesis && current.azureProblem != null) {
            mutableState.update { it.copy(problem = null, azureProblem = null, azureHttpStatus = null, azureFailureDetail = null) }
            launchWriter(reading)
            player.play()
            refresh()
            startService()
            return
        }
        if (current.problem != null) {
            val index = player.currentMediaItemIndex.coerceIn(0, packets.lastIndex.coerceAtLeast(0))
            begin(reading.copy(file = cacheFile(), first = reading.first + index,
                initialFraction = 0.0, initialOffsetMs = player.currentPosition,
                positionBase = reading.positionBase + (packets.getOrNull(index)?.startMs ?: 0L)), autoplay = true)
            startService()
            return
        }
        if (sleepAtChapterEnd && player.playbackState == Player.STATE_ENDED && canAdvance(reading)) {
            sleepAtChapterEnd = false
            completed = null
            player.play()
            refresh()
            startService()
            return
        }
        if (player.playbackState == Player.STATE_ENDED && current.readyParts == current.totalParts) {
            cancelPageTurn()
            completed = null
            player.seekTo(0, 0)
        }
        sleepAtChapterEnd = false
        player.play()
        if (!current.generating && current.readyParts < current.totalParts) launchWriter(reading)
        refresh()
        startService()
    }

    fun setMixAudio(enabled: Boolean) {
        mutableMix.value = enabled
        player.setAudioAttributes(attributes, !enabled)
        settings.edit { putBoolean("mix_audio", enabled) }
        if (enabled && isCallMode(audioManager.mode)) pause()
    }

    fun toggleMuted() {
        val mute = !muted.value
        mutableMuted.value = mute
        updateVolume()
        settings.edit { putBoolean("muted", mute) }
    }

    fun setSleepTimer(minutes: Int, extend: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val base = if (extend) sleepDeadlineMs.coerceAtLeast(now) else now
        sleepDeadlineMs = (base + minutes.coerceIn(SleepTimer.durationMinutes) * SleepTimer.minuteMs)
            .coerceAtMost(now + SleepTimer.durationMinutes.last * SleepTimer.minuteMs)
        updateSleepTimer()
    }

    fun sleepAfterChapter() {
        cancelSleepTimer()
        if (pageTurn?.isActive == true) {
            pause()
            sleepAtChapterEnd = true
        } else {
            mutableSleepTimer.value = SleepTimer.ChapterEnd
        }
    }

    fun cancelSleepTimer() {
        sleepDeadlineMs = 0L
        mutableSleepTimer.value = null
        sleepGain = 1f
        updateVolume()
    }

    internal fun updateSleepTimer(nowMs: Long = SystemClock.elapsedRealtime()) {
        if (sleepDeadlineMs == 0L) return
        val remaining = sleepDeadlineMs - nowMs
        if (remaining <= 0L) {
            pause()
            cancelSleepTimer()
        } else {
            val gain = (remaining.toFloat() / SleepTimer.fadeMs).coerceAtMost(1f)
            if (gain != sleepGain) {
                sleepGain = gain
                updateVolume()
            }
            val seconds = (remaining + 999) / 1_000
            mutableSleepTimer.value = SleepTimer.Countdown(seconds * 1_000)
        }
    }

    private fun updateVolume() {
        player.volume = if (muted.value) 0f else sleepGain
    }

    @RequiresApi(31)
    private fun registerCallListener(): () -> Unit {
        val listener = AudioManager.OnModeChangedListener { mode ->
            if (isCallMode(mode) && player.playWhenReady) pause()
        }
        audioManager.addOnModeChangedListener(application.mainExecutor, listener)
        return { audioManager.removeOnModeChangedListener(listener) }
    }

    private fun isCallMode(mode: Int) =
        mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION

    fun pause() {
        cancelPageTurn()
        player.pause()
        refresh()
        checkpoint()
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
        checkpoint()
    }

    private fun startChapter(
        article: ReadableArticle,
        chapter: Int,
        paragraph: Int,
        voice: Voice,
        bookmarkId: String,
        saved: Bookmark? = null,
        segmentLength: Int = saved?.segmentLength ?: this.segmentLength.value,
        queueId: String? = null,
    ) {
        val parts = article.chapters.getOrNull(chapter)?.paragraphs?.flatMapIndexed { index, text ->
            chapterParts(index, text, segmentLength)
        }.orEmpty()
        if (parts.isEmpty()) return
        val first = saved?.part?.takeIf { it in parts.indices && parts[it].paragraph == paragraph }
            ?: parts.indexOfFirst { it.paragraph >= paragraph }.coerceAtLeast(0)
        begin(Reading(article, chapter, parts, first, voice, cacheFile(), bookmarkId, segmentLength, queueId,
            initialOffsetMs = saved?.offsetMs ?: 0L,
            positionBase = saved?.let { (it.positionMs - it.offsetMs).coerceAtLeast(0) } ?: 0L),
            autoplay = true)
    }

    private fun begin(reading: Reading, autoplay: Boolean) {
        sleepAtChapterEnd = false
        player.stop()
        player.clearMediaItems()
        retire()
        packets.clear()
        active = reading
        completed = null
        mutableState.value = ReadingState(
            article = reading.article,
            bookmarkId = reading.bookmarkId,
            queueEntryId = reading.queueId,
            chapterIndex = reading.chapter,
            voice = reading.voice,
            speed = state.value.speed,
            totalParts = reading.parts.size - reading.first,
            estimatedTotalMs = estimatedTotal(reading),
            paragraphIndex = reading.parts[reading.first].paragraph,
            sentenceRange = reading.parts[reading.first].sentences.first().range,
        )
        player.playWhenReady = autoplay
        launchWriter(reading)
        refresh()
        checkpoint()
    }

    private fun launchWriter(reading: Reading) {
        val previous = writer
        val startPart = reading.first + packets.size
        val startByte = packets.lastOrNull()?.endByte ?: 0
        if (startPart !in reading.parts.indices) return
        mutableState.update { it.copy(generating = true) }
        writer = viewModelScope.launch {
            try {
                previous?.join()
                val synthesizer = (application as ElainaApplication).speech
                withContext(Dispatchers.IO) {
                    RandomAccessFile(reading.file, "rw").use { output ->
                        output.setLength(startByte)
                        output.seek(startByte)
                        for (partIndex in startPart until reading.parts.size) {
                            val requestedPart = partIndex - reading.first
                            if (synthesizer === AppSpeech && (!reading.voice.isOffline || reading.article.pixivFull)) {
                                while (!withContext(Dispatchers.Main.immediate) { canPrefetch(reading, requestedPart) }) {
                                    delay(250)
                                }
                            }
                            val part = reading.parts[partIndex]
                            suspend fun synthesize(text: String) =
                                SpeechSynthesis.synthesize(application, text, reading.voice, synthesizer, reading.segmentLength)

                            val (audio, cues) = if (reading.voice.isOffline) {
                                synthesizeOfflinePart(part, ::synthesize) { sentence, error ->
                                    val notice = MatchaNotice(part.paragraph + 1, sentence + 1, error.reason)
                                    android.util.Log.w("MatchaSpeech",
                                        "Skipped paragraph ${notice.paragraph}, sentence ${notice.sentence}: ${notice.reason}")
                                    withContext(Dispatchers.Main.immediate) {
                                        if (active === reading) mutableState.update { it.copy(matchaNotice = notice) }
                                    }
                                }
                            } else {
                                val audio = retryAzureSynthesis(wait = { waitMs ->
                                    delay(waitMs)
                                    while (!withContext(Dispatchers.Main.immediate) { canPrefetch(reading, requestedPart) }) {
                                        delay(250)
                                    }
                                }) { synthesize(part.text) }
                                audio to estimatedSentenceCues(part, audio.durationMs)
                            }
                            val bytes = audio.toWavBytes()
                            val offset = output.filePointer
                            output.write(bytes)
                            withContext(Dispatchers.Main.immediate) {
                                if (active === reading) append(reading, offset, bytes.size.toLong(), audio.durationMs, cues)
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
            } catch (error: AzureSpeechException) {
                if (active === reading) failSynthesis(error)
            } catch (error: MatchaEngineException) {
                android.util.Log.w("MatchaSpeech", "Offline engine unavailable: ${error.cause?.javaClass?.simpleName}")
                if (active === reading) {
                    mutableState.update { it.copy(matchaEngineFailed = true) }
                    failSynthesis()
                }
            } catch (error: Exception) {
                if (active === reading) failSynthesis()
            } catch (error: LinkageError) {
                if (active === reading) failSynthesis()
            }
        }
    }

    private suspend fun synthesizeOfflinePart(
        part: Part,
        synthesize: suspend (String) -> SpeechAudio,
        onSkipped: suspend (Int, MatchaSentenceException) -> Unit,
    ): Pair<SpeechAudio, List<SentenceCue>> {
        val clips = part.sentences.map { sentence ->
            if (sentence.text.codePoints().noneMatch(Character::isLetterOrDigit)) null
            else try {
                synthesize(sentence.text)
            } catch (error: MatchaSentenceException) {
                onSkipped(sentence.index, error)
                null
            }
        }
        val sampleRate = clips.firstNotNullOfOrNull { it?.sampleRateHz } ?: MatchaSpeech.sampleRateHz
        val sizes = clips.mapIndexed { index, clip ->
            clip?.samples?.size ?: (SpeechText.estimateMs(part.sentences[index].text)
                .coerceIn(100, 300) * sampleRate / 1_000).toInt()
        }
        val samples = FloatArray(sizes.sum())
        var offsetSamples = 0
        val cues = clips.mapIndexed { index, clip ->
            val cue = SentenceCue(offsetSamples * 1_000L / sampleRate, part.sentences[index].range)
            if (clip != null) {
                check(sampleRate == clip.sampleRateHz)
                clip.samples.copyInto(samples, offsetSamples)
            }
            offsetSamples += sizes[index]
            cue
        }
        return SpeechAudio(samples, sampleRate) to cues
    }

    private fun estimatedSentenceCues(part: Part, durationMs: Long): List<SentenceCue> {
        val weights = part.sentences.map { SpeechText.estimateMs(it.text).coerceAtLeast(1) }
        val total = weights.sum()
        var elapsedWeight = 0L
        return part.sentences.mapIndexed { index, sentence ->
            SentenceCue(durationMs * elapsedWeight / total, sentence.range)
                .also { elapsedWeight += weights[index] }
        }
    }

    private val SpeechAudio.durationMs: Long
        get() = (samples.size * 1_000L / sampleRateHz).coerceAtLeast(1)

    private fun canPrefetch(reading: Reading, part: Int): Boolean =
        active === reading && player.playWhenReady && part <= player.currentMediaItemIndex + 1

    private fun append(reading: Reading, offset: Long, bytes: Long, duration: Long, cues: List<SentenceCue>) {
        val index = packets.size
        val wasWaiting = index > 0 && player.playbackState == Player.STATE_ENDED
        val start = state.value.readyDurationMs
        packets += Packet(start, duration, offset + bytes, cues)
        mutableState.update { it.copy(readyParts = index + 1, readyDurationMs = start + duration,
            estimatedTotalMs = estimatedTotal(reading)) }
        val metadata = MediaMetadata.Builder().setTitle(reading.article.title)
            .setArtist(reading.article.chapters[reading.chapter].title).build()
        player.addMediaSource(wavMediaSource(reading.file, offset, bytes, metadata))
        if (index == 0) {
            val initial = if (reading.initialFraction > 0) (duration * reading.initialFraction).toLong()
                else reading.initialOffsetMs
            if (initial > 0) player.seekTo(0, initial.coerceAtMost(duration - 1))
            player.prepare()
        } else if (wasWaiting) {
            player.seekTo(index, 0)
        }
        refresh()
    }

    private fun refresh() {
        val reading = active ?: return
        val index = player.currentMediaItemIndex.coerceIn(0, packets.lastIndex.coerceAtLeast(0))
        val part = reading.parts[reading.first + index]
        val packet = packets.getOrNull(index)
        val offset = player.currentPosition.coerceIn(0L, packet?.durationMs ?: 0L)
        val position = (packet?.startMs ?: 0L) + offset
        val sentence = packet?.cues?.lastOrNull { it.startMs <= offset }?.range ?: part.sentences.first().range
        mutableState.update { current ->
            current.copy(
                positionMs = position,
                paragraphIndex = part.paragraph,
                sentenceRange = sentence,
                playing = player.isPlaying,
                playRequested = player.playWhenReady &&
                    (player.playbackState != Player.STATE_ENDED || current.generating || current.turningPage),
            )
        }
        advanceIfFinished(reading)
    }

    fun setSegmentLength(length: Int) {
        val adjusted = length.coerceIn(SpeechText.segmentLengths)
        mutableSegmentLength.value = adjusted
        settings.edit { putInt("segment_length", adjusted) }
    }

    private fun estimatedTotal(reading: Reading): Long {
        val audio = packets.lastOrNull()?.let { it.startMs + it.durationMs } ?: 0L
        val modeled = reading.durationPrefix[reading.first + packets.size] - reading.durationPrefix[reading.first]
        val remaining = reading.durationPrefix.last() - reading.durationPrefix[reading.first]
        val scale = if (modeled > 0) (audio.toDouble() / modeled).coerceIn(0.5, 2.0) else 1.0
        val later = if (hasNextChapter(reading)) reading.laterChaptersMs else 0L
        return (audio + (remaining - modeled + later) * scale).toLong().coerceAtLeast(1L)
    }

    fun setAutomatic(enabled: Boolean) {
        mutableAutomatic.value = enabled
        settings.edit { putBoolean("automatic", enabled) }
        if (!enabled) cancelPageTurn()
        active?.let { item -> mutableState.update { it.copy(estimatedTotalMs = estimatedTotal(item)) } }
        refresh()
    }

    fun setSeriesAutomatic(enabled: Boolean) {
        mutableSeriesAutomatic.value = enabled
        settings.edit { putBoolean("pixiv_series_automatic", enabled) }
    }

    private fun resetPageChain(article: ReadableArticle) {
        cancelPageTurn()
        followAllowed = true
        visitedPages.clear()
        spokenContent.clear()
        article.sourceUrl?.takeIf(WebAddress::isAllowed)?.let { visitedPages += WebAddress.pageId(it) }
        spokenContent += PageContent.hash(article)
        pagesRead = 1
    }

    private fun hasNextChapter(reading: Reading): Boolean =
        reading.chapter + 1 in reading.article.chapters.indices &&
            (automatic.value || reading.queueId != null || reading.article.pixivFull)

    private fun hasNextWebPage(article: ReadableArticle): Boolean {
        val page = article.pagination
        return automatic.value && followAllowed && article.sourceUrl != null &&
            page?.atEnd != true && (article.nextPageUrl != null || page?.hasControl == true || page?.current != null)
    }

    private fun nextSeriesNovel(reading: Reading): String? = reading.article.pixivSeries?.nextNovelId
        ?.takeIf { seriesAutomatic.value && reading.queueId == null && followAllowed }

    private fun canAdvance(reading: Reading): Boolean =
        hasNextChapter(reading) || hasNextWebPage(reading.article) || reading.queueId != null || nextSeriesNovel(reading) != null

    private fun advanceIfFinished(reading: Reading) {
        val current = state.value
        if (completed === reading || current.problem != null || current.generating || current.readyParts == 0 ||
            current.readyParts != current.totalParts || !player.playWhenReady ||
            player.playbackState != Player.STATE_ENDED
        ) return
        completed = reading
        checkpoint()
        if (sleepTimer.value == SleepTimer.ChapterEnd) {
            sleepAtChapterEnd = true
            cancelSleepTimer()
            pause()
            return
        }
        if (!canAdvance(reading)) return
        mutableState.update { it.copy(turningPage = true, playRequested = true) }
        pageTurn = viewModelScope.launch {
            delay(1_000)
            if (active !== reading || !player.playWhenReady) return@launch
            when {
                hasNextChapter(reading) -> {
                    pageTurn = null
                    startChapter(reading.article, reading.chapter + 1, 0, reading.voice, reading.bookmarkId,
                        segmentLength = reading.segmentLength, queueId = reading.queueId)
                }
                hasNextWebPage(reading.article) -> advanceWebPage(reading)
                reading.queueId != null -> advanceQueue(reading.queueId)
                else -> {
                    val id = nextSeriesNovel(reading)
                    if (id == null) stopTransition() else advanceSeries(reading, id)
                }
            }
        }
    }

    private suspend fun advanceWebPage(reading: Reading) {
        val article = reading.article
        val source = checkNotNull(article.sourceUrl)
        val next = article.nextPageUrl
        val result = try {
            val tab = readingTab
            val fallback = application.getString(R.string.article_title)
            when {
                tab == null || pagesRead >= maxWebPages -> null
                next == null -> tab.loadSimulatedNextPage(article, fallback)
                WebAddress.pageId(next) in visitedPages -> null
                else -> tab.loadNextPage(source, next)?.let { snapshot ->
                    withContext(Dispatchers.Default) { ArticleExtractor.extract(snapshot, fallback) }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val hash = result?.let { withContext(Dispatchers.Default) { PageContent.hash(it) } }
        if (result == null || hash == null || !spokenContent.add(hash)) {
            stopTransition(ReadingProblem.NextPage)
            return
        }
        val nextSource = result.sourceUrl
        if (nextSource != null) visitedPages += WebAddress.pageId(nextSource)
        pagesRead++
        reading.queueId?.let { queueId ->
            bookmarkWrite.withLock {
                queueDao.writeSnapshot(queueId, result.title, nextSource, Bookmark.encodeArticle(result), result.characterCount)
            }
        }
        pageTurn = null
        showArticle(result)
        startChapter(result, 0, 0, reading.voice, reading.bookmarkId,
            segmentLength = reading.segmentLength, queueId = reading.queueId)
    }

    private fun stopTransition(problem: ReadingProblem? = null) {
        pageTurn = null
        player.pause()
        mutableState.update { it.copy(turningPage = false, problem = problem) }
        refresh()
    }

    private suspend fun advanceSeries(reading: Reading, id: String) {
        val url = PixivNovel.url(id)
        val result = if (WebAddress.pageId(url) in visitedPages) null else loadPixiv(id)
        if (!seriesAutomatic.value) {
            stopTransition()
        } else if (result == null) {
            stopTransition(ReadingProblem.PixivSeries)
        } else {
            visitedPages += WebAddress.pageId(url)
            pageTurn = null
            showArticle(result)
            startChapter(result, 0, 0, reading.voice, Bookmark.idFor(result), segmentLength = reading.segmentLength)
        }
    }

    private suspend fun advanceQueue(id: String) {
        val next = bookmarkWrite.withLock {
            queueDao.complete(id)
            queueDao.nextPending(id)
        }
        if (next == null) {
            stopTransition()
        } else if (!startQueued(next)) {
            stopTransition(ReadingProblem.Queue)
        }
    }

    private fun cancelPageTurn() {
        pageTurn?.cancel()
        pageTurn = null
        if (state.value.turningPage) mutableState.update { it.copy(turningPage = false) }
    }

    private fun startService() {
        if (state.value.playRequested) PlaybackService.prepare(application)
    }

    fun saveBookmark(article: ReadableArticle) {
        if (article.incomplete) return
        viewModelScope.launch { putBookmark(article) }
    }

    suspend fun putBookmark(article: ReadableArticle): String {
        require(!article.incomplete)
        val item = if (active?.article == article) currentBookmark() else null
        val bookmark = item ?: Bookmark(
            Bookmark.idFor(article), article.title, article.sourceUrl,
            withContext(Dispatchers.Default) { Bookmark.encodeArticle(article) },
            0, 0, 0, 0, 0, voicePreferences.defaultVoice.id, state.value.speed, System.currentTimeMillis(),
            segmentLength = segmentLength.value, characterCount = article.characterCount,
        )
        return bookmarkWrite.withLock {
            val existing = findExistingBookmark(bookmark.id, bookmark.url)
            if (item != null || existing == null || (article.pixivFull && !existing.hasFullPixiv())) {
                val content = if (item == null && existing != null) existing.copy(
                    title = bookmark.title, url = bookmark.url, article = bookmark.article,
                    characterCount = bookmark.characterCount,
                ) else bookmark
                bookmarkDao.insert(content.copy(id = existing?.id ?: bookmark.id, folderId = existing?.folderId,
                    displayName = existing?.displayName, completed = content.completed || existing?.completed == true))
            }
            existing?.id ?: bookmark.id
        }
    }

    private suspend fun backfillCharacterCounts() {
        while (true) {
            val records = bookmarkDao.missingCharacterCounts()
            if (records.isEmpty()) break
            val counts = records.map { it.id to (runCatching { it.readArticle().characterCount }.getOrDefault(0)) }
            database.withTransaction { counts.forEach { (id, count) -> bookmarkDao.backfillCharacterCount(id, count) } }
        }
        while (true) {
            val records = queueDao.missingCharacterCounts()
            if (records.isEmpty()) break
            val counts = records.map {
                val count = runCatching {
                    Bookmark.decodeArticle(it.snapshotTitle ?: it.title, it.snapshotUrl, checkNotNull(it.snapshot)).characterCount
                }.getOrDefault(0)
                it.id to count
            }
            database.withTransaction { counts.forEach { (id, count) -> queueDao.backfillCharacterCount(id, count) } }
        }
    }

    private suspend fun findExistingBookmark(id: String, url: String?): Bookmark? {
        bookmarkDao.find(id)?.let { return it }
        val novel = PixivNovel.novelId(url) ?: return null
        val legacy = bookmarkDao.pixivKeys().firstOrNull { PixivNovel.novelId(it.url) == novel } ?: return null
        return bookmarkDao.find(legacy.id)
    }

    fun renameBookmark(id: String, displayName: String) {
        val name = displayName.trim().take(200)
        if (name.isBlank()) return
        viewModelScope.launch { bookmarkWrite.withLock { bookmarkDao.rename(id, name) } }
    }

    fun deleteBookmark(id: String) {
        viewModelScope.launch { removeBookmark(id) }
    }

    suspend fun removeBookmark(id: String): Boolean = bookmarkWrite.withLock { bookmarkDao.delete(id) != 0 }

    suspend fun findBookmark(id: String): Bookmark? = bookmarkDao.find(id)?.takeUnless(Bookmark::isWebsite)

    suspend fun saveWebsite(url: String, title: String?, icon: String? = null): Boolean {
        val canonical = url.substringBefore('#')
        if (!WebAddress.isAllowed(canonical)) return false
        val item = Bookmark(Bookmark.websiteId(canonical), title?.takeIf(String::isNotBlank) ?: canonical,
            canonical, "", 0, 0, 0, 0, 0, Voice.default.id, 1f, System.currentTimeMillis(),
            isWebsite = true, websiteIcon = icon)
        return bookmarkWrite.withLock {
            if (bookmarkDao.find(item.id) != null) return@withLock false
            bookmarkDao.insert(item)
            true
        }
    }

    fun pinWebsite(site: Bookmark, pinned: Boolean) {
        viewModelScope.launch { bookmarkWrite.withLock { bookmarkDao.pinWebsite(site.id, pinned) } }
    }

    suspend fun setWebsiteIcon(url: String, icon: String) = bookmarkWrite.withLock {
        bookmarkDao.setWebsiteIcon(Bookmark.websiteId(url.substringBefore('#')), icon)
    }

    suspend fun saveFolder(folderId: String?, proposed: String): Boolean = bookmarkWrite.withLock {
        val name = proposed.trim()
        if (name.isEmpty() || name.length > 40 || bookmarkDao.folderNameInUse(name, folderId.orEmpty()) > 0) {
            return@withLock false
        }
        if (folderId == null) {
            bookmarkDao.insertFolder(BookmarkFolder(UUID.randomUUID().toString(), name))
        } else {
            val folder = bookmarkDao.findFolder(folderId) ?: return@withLock false
            bookmarkDao.updateFolder(folder.copy(name = name))
        }
        true
    }

    fun moveBookmark(id: String, folderId: String?) {
        viewModelScope.launch {
            bookmarkWrite.withLock {
                if (folderId == null || bookmarkDao.findFolder(folderId) != null) bookmarkDao.move(id, folderId)
            }
        }
    }

    fun deleteFolder(id: String) {
        viewModelScope.launch { bookmarkWrite.withLock { bookmarkDao.removeFolder(id) } }
    }

    suspend fun listBookmarks(limit: Int, offset: Int): List<Bookmark> = bookmarkDao.list(limit, offset)

    suspend fun addToQueue(article: ReadableArticle): Int {
        if (article.incomplete) return 0
        val entry = withContext(Dispatchers.Default) { QueueEntry.fromArticle(article) }
        return appendNewQueueEntries(listOf(entry))
    }

    suspend fun addPixivSeries(id: String, currentArticle: ReadableArticle? = null): Int? {
        val catalog = catalogDao.find(id)?.decode() ?: refreshPixivCatalog(id, currentArticle?.pixivSeries?.title)
            ?: return null
        val currentId = PixivNovel.novelId(currentArticle?.sourceUrl)
        val entries = withContext(Dispatchers.Default) {
            catalog.entries.mapNotNull { item ->
                val url = PixivNovel.url(item.id)
                when {
                    item.id == currentId && currentArticle?.pixivFull == true -> QueueEntry.fromArticle(currentArticle)
                    item.available -> QueueEntry.fromLink(item.title, url)
                    else -> findExistingBookmark(Bookmark.idForUrl(url), url)?.takeIf(Bookmark::hasFullPixiv)
                        ?.let { bookmark -> runCatching(bookmark::readArticle).getOrNull()?.let(QueueEntry::fromArticle) }
                }
            }
        }
        return appendNewQueueEntries(entries)
    }

    private suspend fun appendNewQueueEntries(entries: List<QueueEntry>): Int = bookmarkWrite.withLock {
        val existing = queueDao.ordered().associateBy { Bookmark.workKey(it.id, it.sourceUrl) }
        queueDao.append(entries.map { entry ->
            val previous = existing[Bookmark.workKey(entry.id, entry.sourceUrl)]
            if (previous == null) entry else entry.copy(id = previous.id)
        })
    }

    suspend fun identifyPixivSeries(tab: BrowserSession, novelId: String): PixivSeries? {
        catalogs.value.values.firstOrNull { catalog -> catalog.entries.any { it.id == novelId } }
            ?.let { return PixivSeries(it.id, it.title, null) }
        return tab.readPixivNovel(novelId)?.pixivSeries
    }

    suspend fun refreshPixivCatalog(id: String, title: String? = null): PixivCatalog? {
        val safeId = PixivNovel.validId(id) ?: return null
        val entries = browser.readPixivCatalog(safeId, includeUnavailable = true)
            ?: withTemporaryPixiv(PixivNovel.seriesUrl(safeId)) { it.readPixivCatalog(safeId, includeUnavailable = true) }
        return bookmarkWrite.withLock {
            val previous = catalogDao.find(safeId)?.decode()
            if (entries == null) return@withLock previous
            val name = title?.takeIf(String::isNotBlank) ?: previous?.title
                ?: application.getString(R.string.pixiv_series_fallback, safeId)
            PixivCatalog(safeId, name, entries, previous?.failed.orEmpty()).also { catalogDao.save(it.encode()) }
        }
    }

    suspend fun savePixivSeries(id: String, article: ReadableArticle?, retryId: String? = null): SeriesImportProgress? {
        if (id in mutableSeriesImports.value) return null
        mutableSeriesImports.update { it + (id to SeriesImportProgress(0, 0, 0)) }
        try {
            val catalog = catalogDao.find(id)?.decode() ?: refreshPixivCatalog(id, article?.pixivSeries?.title)
                ?: return null
            val existing = bookmarkDao.pixivKeys().mapNotNull { PixivNovel.novelId(it.url) }.toMutableSet()
            var progress = SeriesImportProgress(catalog.entries.count { it.id in existing }, catalog.entries.size, 0)
            val failures = catalog.failed.toMutableSet()
            mutableSeriesImports.update { it + (id to progress) }
            for (entry in catalog.entries) {
                if (retryId != null && retryId != entry.id) continue
                if (entry.id in existing) {
                    failures.remove(entry.id)
                    continue
                }
                val saved = try {
                    val novel = when {
                        article?.pixivFull == true && PixivNovel.novelId(article.sourceUrl) == entry.id -> article
                        entry.available || retryId != null -> loadPixiv(entry.id)
                        else -> null
                    }
                    if (novel?.pixivFull == true) {
                        putBookmark(novel)
                        true
                    } else false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (saved) {
                    existing += entry.id
                    failures.remove(entry.id)
                    progress = progress.copy(saved = progress.saved + 1, added = progress.added + 1)
                } else failures += entry.id
                catalogDao.failures(id, failures.joinToString(","))
                mutableSeriesImports.update { it + (id to progress) }
            }
            catalogDao.failures(id, failures.joinToString(","))
            return progress
        } finally {
            mutableSeriesImports.update { it - id }
        }
    }

    fun moveQueueEntry(id: String, offset: Int) {
        viewModelScope.launch { queueDao.move(id, offset) }
    }

    fun removeQueueEntry(id: String) {
        if (active?.queueId == id) pause()
        viewModelScope.launch { bookmarkWrite.withLock { queueDao.remove(id) } }
    }

    fun playQueued(id: String?, onResult: (Boolean) -> Unit = {}) {
        queueRequest?.cancel()
        pause()
        queueRequest = viewModelScope.launch {
            val entry = id ?: resumableQueueId()
            val success = entry != null && startQueued(entry)
            onResult(success)
        }
    }

    private suspend fun resumableQueueId(): String? {
        val id = queueDao.cursor() ?: return queueDao.firstPending()
        return when (queueDao.find(id)?.completed) {
            false -> id
            true -> queueDao.nextPending(id) ?: queueDao.firstPending()
            null -> queueDao.firstPending()
        }
    }

    private suspend fun startQueued(id: String): Boolean {
        var entry = bookmarkWrite.withLock { queueDao.find(id) } ?: return false
        if (entry.snapshot == null) {
            val novelId = PixivNovel.novelId(entry.sourceUrl) ?: return false
            val article = loadPixiv(novelId) ?: return false
            val encoded = withContext(Dispatchers.Default) { Bookmark.encodeArticle(article) }
            queueDao.fillSnapshot(id, article.title, article.sourceUrl, encoded, article.characterCount)
            entry = queueDao.find(id) ?: return false
        }
        val voice = active?.voice ?: voicePreferences.defaultVoice
        val bookmark = entry.resumeBookmark(voice, state.value.speed, segmentLength.value) ?: return false
        return bookmarkWrite.withLock {
            val article = runCatching { bookmark.readArticle() }.getOrNull() ?: return@withLock false
            if (article.chapters.getOrNull(bookmark.chapter)?.paragraphs.isNullOrEmpty() ||
                !queueDao.select(id)
            ) return@withLock false
            pageTurn = null
            restore(bookmark, article, id)
            true
        }
    }

    private suspend fun loadPixiv(id: String): ReadableArticle? {
        for (tab in listOfNotNull(readingTab, browser).distinct()) {
            tab.readPixivNovel(id)?.let { return it }
        }
        return withTemporaryPixiv(PixivNovel.url(id)) { it.readPixivNovel(id) }
    }

    private suspend fun <T> withTemporaryPixiv(url: String, read: suspend (BrowserSession) -> T?): T? {
        val tab = BrowserSession(application)
        try {
            if (!tab.open(url)) return null
            return withTimeoutOrNull(18_000) {
                while (tab.state.loading) delay(100)
                if (tab.state.error == null && PixivNovel.isOrigin(tab.state.url)) read(tab) else null
            }
        } finally {
            tab.close()
        }
    }

    fun restoreResume(draft: ReadingResume, onFailure: () -> Unit = {}): Boolean {
        if (draft.queueEntryId != null) {
            playQueued(null) { success -> if (!success) onFailure() }
            return true
        }
        return restoreBookmark(draft.bookmark)
    }

    fun restoreBookmark(saved: Bookmark): Boolean {
        queueRequest?.cancel()
        if (saved.isWebsite) return false
        val key = Bookmark.workKey(saved.id, saved.url)
        val reading = active?.takeIf { Bookmark.workKey(it.bookmarkId, it.article.sourceUrl) == key && !it.article.incomplete }
        val current = state.value
        val canResume = current.problem == null || current.problem == ReadingProblem.Synthesis && current.azureProblem != null
        val finished = player.playbackState == Player.STATE_ENDED && current.readyParts == current.totalParts
        if (reading != null && reading.queueId == null && canResume && !finished) {
            showArticle(reading.article)
            if (!current.playRequested) togglePlayback()
            return true
        }
        val bookmark = reading?.let { currentBookmark() } ?: saved
        val article = runCatching { bookmark.readArticle() }.getOrNull() ?: return false
        if (article.chapters.getOrNull(bookmark.chapter)?.paragraphs.isNullOrEmpty()) return false
        checkpoint()
        restore(bookmark, article)
        return true
    }

    private fun restore(bookmark: Bookmark, article: ReadableArticle, queueId: String? = null) {
        resetPageChain(article)
        showArticle(article)
        val voice = Voice.fromId(bookmark.voiceId)
        val speed = bookmark.speed.coerceIn(0.8f, 2.5f)
        player.setPlaybackSpeed(speed)
        mutableState.update { it.copy(speed = speed) }
        val tabs = (application as ElainaApplication).tabs
        val source = article.sourceUrl
        readingTab = if (source == null) null else tabs.items.firstOrNull { it.state.url == source }
        if (readingTab == null && source != null && (article.nextPageUrl != null || article.pagination?.hasControl == true)) {
            if (browser.state.url.isEmpty() || tabs.add()) {
                readingTab = browser
                browser.open(source)
                followAllowed = true
            }
        }
        startChapter(article, bookmark.chapter, bookmark.paragraph, voice, bookmark.id, bookmark, queueId = queueId)
        startService()
    }

    private fun currentBookmark(): Bookmark? {
        val reading = active ?: return null
        val current = state.value
        val index = player.currentMediaItemIndex.coerceIn(0, packets.lastIndex.coerceAtLeast(0))
        val packet = packets.getOrNull(index)
        val offset = packet?.let { player.currentPosition.coerceIn(0, it.durationMs - 1) } ?: reading.initialOffsetMs
        return Bookmark(reading.bookmarkId, reading.article.title, reading.article.sourceUrl,
            reading.encodedArticle, reading.chapter, reading.parts[reading.first + index].paragraph,
            reading.first + index, offset, reading.positionBase + (packet?.startMs ?: 0L) + offset,
            reading.voice.id, current.speed, System.currentTimeMillis(), segmentLength = reading.segmentLength,
            completed = completed === reading && reading.chapter == reading.article.chapters.lastIndex,
            characterCount = reading.characterCount)
    }

    fun persistProgress() = checkpoint()

    private fun checkpoint() {
        val reading = active ?: return
        val article = reading.article
        val item = currentBookmark() ?: return
        viewModelScope.launch {
            bookmarkWrite.withLock {
                val queueId = reading.queueId?.takeIf { queueDao.contains(it) }
                database.withTransaction {
                    bookmarkDao.putResume(ReadingResume(bookmark = item, queueEntryId = queueId))
                    if (queueId != null) queueDao.checkpoint(queueId, item.chapter, item.paragraph, item.part,
                        item.offsetMs, item.positionMs, item.voiceId, item.speed, item.segmentLength)
                }
                val key = Bookmark.workKey(item.id, item.url)
                if (!article.incomplete && bookmarks.value.any { Bookmark.workKey(it.id, it.url) == key }) {
                    val existing = findExistingBookmark(item.id, item.url)
                    if (existing != null && (article.pixivFull || PixivNovel.novelId(article.sourceUrl) == null ||
                            !existing.hasFullPixiv())) {
                        bookmarkDao.checkpoint(item.copy(id = existing.id, folderId = existing.folderId,
                            displayName = existing.displayName, completed = existing.completed || item.completed))
                    }
                }
            }
        }
    }

    private fun failSynthesis(error: AzureSpeechException? = null) {
        if (error != null) {
            android.util.Log.w("AzurePlayback",
                "Synthesis stopped: reason=${error.reason}, status=${error.httpStatus}, detail=${error.detail}, readyParts=${state.value.readyParts}")
        }
        mutableState.update { it.copy(generating = false, problem = ReadingProblem.Synthesis,
            azureProblem = error?.reason, azureHttpStatus = error?.httpStatus, azureFailureDetail = error?.detail) }
        if (packets.isEmpty() || player.playbackState == Player.STATE_ENDED) player.pause()
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

    private fun cacheFile() = ReadingFiles.newSession(application)

    override fun onCleared() {
        removeCallListener?.invoke()
        (application as ElainaApplication).tabs.onManualNavigation = null
        player.release()
        retire()
        database.close()
    }

    private fun chapterParts(paragraph: Int, text: String, length: Int): List<Part> {
        val sentences = SpeechText.sentences(text)
        var textOffset = 0
        var sentenceIndex = 0
        return SpeechText.split(text, length).map { segment ->
            val start = text.indexOf(segment, textOffset)
            val end = start + segment.length
            val slices = buildList {
                while (sentenceIndex < sentences.size) {
                    val range = sentences[sentenceIndex]
                    if (range.first >= end) break
                    val first = maxOf(start, range.first)
                    val last = minOf(end, range.last + 1)
                    if (first < last) add(SentenceSlice(range, text.substring(first, last).trim(), sentenceIndex))
                    if (range.last >= end) break
                    sentenceIndex++
                }
                if (isEmpty()) add(SentenceSlice(start until end, segment, sentenceIndex))
            }
            textOffset = end
            Part(paragraph, segment, slices)
        }
    }

    private data class SentenceSlice(val range: IntRange, val text: String, val index: Int)
    private data class SentenceCue(val startMs: Long, val range: IntRange)
    private data class Part(val paragraph: Int, val text: String, val sentences: List<SentenceSlice>)
    private data class Packet(val startMs: Long, val durationMs: Long, val endByte: Long, val cues: List<SentenceCue>)
    private data class Reading(
        val article: ReadableArticle,
        val chapter: Int,
        val parts: List<Part>,
        val first: Int,
        val voice: Voice,
        val file: File,
        val bookmarkId: String,
        val segmentLength: Int,
        val queueId: String?,
        val initialFraction: Double = 0.0,
        val initialOffsetMs: Long = 0,
        val positionBase: Long = 0,
    ) {
        val encodedArticle = Bookmark.encodeArticle(article)
        val characterCount = article.characterCount
        val durationPrefix = parts.runningFold(0L) { total, part -> total + SpeechText.estimateMs(part.text) }
        val laterChaptersMs = article.chapters.drop(chapter + 1)
            .sumOf { part -> part.paragraphs.sumOf(SpeechText::estimateMs) }
    }

    private companion object { const val maxWebPages = 21 }
}
