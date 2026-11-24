package com.elainalisten.playback

import android.app.Application
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
import com.elainalisten.ElainaApplication
import com.elainalisten.R
import com.elainalisten.article.ArticleExtractor
import com.elainalisten.article.ReadableArticle
import com.elainalisten.browser.WebAddress
import com.elainalisten.data.Bookmark
import com.elainalisten.data.BookmarkDatabase
import com.elainalisten.tts.AzureSpeechException
import com.elainalisten.tts.SpeechSynthesis
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ReadingProblem { Synthesis, Playback, NextPage }

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
    val turningPage: Boolean = false,
    val navigationCancelled: Boolean = false,
    val bookmarkId: String? = null,
    val azureProblem: AzureSpeechException.Reason? = null,
)

@OptIn(UnstableApi::class)
internal class ReadingViewModel(private val application: Application) : AndroidViewModel(application) {
    private val browser get() = (application as ElainaApplication).browser
    private val database = BookmarkDatabase.open(application)
    private val bookmarkDao = database.bookmarks()
    private val bookmarkWrite = Mutex()
    val bookmarks = bookmarkDao.observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val settings = application.getSharedPreferences("reading", android.content.Context.MODE_PRIVATE)
    private val mutableAutomatic = MutableStateFlow(settings.getBoolean("automatic", true))
    val automatic = mutableAutomatic.asStateFlow()
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
        .setWakeMode(C.WAKE_MODE_LOCAL)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(30_000)
        .build()
    internal val mediaPlayer: Player get() = player
    private var pageTurn: Job? = null
    private var completed: Reading? = null
    private var followAllowed = true
    private val visitedPages = mutableSetOf<String>()
    private val spokenContent = mutableSetOf<String>()
    private var active: Reading? = null
    private var writer: Job? = null
    private val packets = mutableListOf<Packet>()

    init {
        browser.onManualNavigation = {
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
                refresh()
                if (events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                        Player.EVENT_PLAYBACK_STATE_CHANGED)) checkpoint()
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
        checkpoint()
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
            startChapter(article, index, 0, current.voice, current.bookmarkId ?: Bookmark.idFor(article))
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
        if (active?.voice?.isAzure == true) {
            pause()
            selectVoice(Voice.default)
        }
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
        if (current.problem == ReadingProblem.NextPage) return
        if (current.problem != null) {
            val index = player.currentMediaItemIndex.coerceIn(0, packets.lastIndex.coerceAtLeast(0))
            begin(reading.copy(file = cacheFile(), first = reading.first + index,
                initialFraction = 0.0, initialOffsetMs = player.currentPosition,
                positionBase = reading.positionBase + (packets.getOrNull(index)?.startMs ?: 0L)), autoplay = true)
            startService()
            return
        }
        if (player.playbackState == Player.STATE_ENDED && current.readyParts == current.totalParts) {
            cancelPageTurn()
            completed = null
            player.seekTo(0, 0)
        }
        player.play()
        if (!current.generating && current.readyParts < current.totalParts) launchWriter(reading)
        refresh()
        startService()
    }

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
    ) {
        val parts = article.chapters.getOrNull(chapter)?.paragraphs?.flatMapIndexed { index, text ->
            SpeechText.split(text).map { Part(index, it) }
        }.orEmpty()
        if (parts.isEmpty()) return
        val first = saved?.part?.takeIf { it in parts.indices && parts[it].paragraph == paragraph }
            ?: parts.indexOfFirst { it.paragraph >= paragraph }.coerceAtLeast(0)
        begin(Reading(article, chapter, parts, first, voice, cacheFile(), bookmarkId,
            initialOffsetMs = saved?.offsetMs ?: 0L,
            positionBase = saved?.let { (it.positionMs - it.offsetMs).coerceAtLeast(0) } ?: 0L),
            autoplay = true)
    }

    private fun begin(reading: Reading, autoplay: Boolean) {
        player.stop()
        player.clearMediaItems()
        retire()
        packets.clear()
        active = reading
        completed = null
        mutableState.value = ReadingState(
            article = reading.article,
            bookmarkId = reading.bookmarkId,
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
                        SpeechSynthesis.synthesizeSegments(
                            application, texts, reading.voice,
                            beforeAzureSegment = { index ->
                                val requestedPart = startPart - reading.first + index
                                while (!withContext(Dispatchers.Main.immediate) {
                                        active === reading && player.playWhenReady &&
                                            requestedPart <= player.currentMediaItemIndex + 1
                                    }) delay(250)
                            },
                        ) { audio, _, _ ->
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
            } catch (error: AzureSpeechException) {
                if (active === reading) failSynthesis(error.reason)
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
        val position = packets.getOrNull(index)?.let {
            it.startMs + player.currentPosition.coerceIn(0, it.durationMs)
        } ?: 0L
        mutableState.update { current ->
            current.copy(
                positionMs = position,
                paragraphIndex = reading.parts[reading.first + index].paragraph,
                playing = player.isPlaying,
                playRequested = player.playWhenReady &&
                    (player.playbackState != Player.STATE_ENDED || current.generating || current.turningPage),
            )
        }
        advanceIfFinished(reading)
    }

    fun setAutomatic(enabled: Boolean) {
        mutableAutomatic.value = enabled
        settings.edit { putBoolean("automatic", enabled) }
        if (!enabled) cancelPageTurn()
        refresh()
    }

    private fun resetPageChain(article: ReadableArticle) {
        cancelPageTurn()
        followAllowed = true
        visitedPages.clear()
        spokenContent.clear()
        article.sourceUrl?.takeIf(WebAddress::isAllowed)?.let { visitedPages += WebAddress.pageId(it) }
        spokenContent += contentId(article)
    }

    private fun contentId(article: ReadableArticle): String =
        article.chapters.flatMap { it.paragraphs }.joinToString("").filterNot(Char::isWhitespace)

    private fun advanceIfFinished(reading: Reading) {
        val current = state.value
        if (completed === reading || current.problem != null || current.generating || current.readyParts == 0 ||
            current.readyParts != current.totalParts || !player.playWhenReady ||
            player.playbackState != Player.STATE_ENDED
        ) return
        completed = reading
        checkpoint()
        if (!automatic.value) return
        val nextChapter = reading.chapter + 1
        val source = reading.article.sourceUrl
        val nextUrl = reading.article.nextPageUrl
        val hasChapter = nextChapter in reading.article.chapters.indices
        if (!hasChapter && (!followAllowed || source == null || nextUrl == null)) return
        mutableState.update { it.copy(turningPage = true, playRequested = true) }
        pageTurn = viewModelScope.launch {
            delay(1_000)
            if (active !== reading || !player.playWhenReady) return@launch
            if (hasChapter) {
                pageTurn = null
                startChapter(reading.article, nextChapter, 0, reading.voice, reading.bookmarkId)
                return@launch
            }
            val target = WebAddress.nextPage(checkNotNull(source), checkNotNull(nextUrl))
            val result = try {
                if (target == null || visitedPages.size >= 21 || WebAddress.pageId(target) in visitedPages) {
                    null
                } else {
                    val snapshot = browser.loadNextPage(source, target)
                    snapshot?.let {
                        withContext(Dispatchers.Default) {
                            ArticleExtractor.extract(it, application.getString(R.string.article_title))
                        }
                    }?.takeIf { article -> contentId(article) !in spokenContent }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            pageTurn = null
            if (result == null) {
                player.pause()
                mutableState.update { it.copy(turningPage = false, problem = ReadingProblem.NextPage) }
                refresh()
            } else {
                visitedPages += WebAddress.pageId(checkNotNull(result.sourceUrl))
                spokenContent += contentId(result)
                showArticle(result)
                startChapter(result, 0, 0, reading.voice, reading.bookmarkId)
            }
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
        viewModelScope.launch { putBookmark(article) }
    }

    suspend fun putBookmark(article: ReadableArticle): String {
        val item = if (active?.article == article) currentBookmark() else null
        val bookmark = item ?: Bookmark(
            Bookmark.idFor(article), article.title, article.sourceUrl, Bookmark.encodeArticle(article),
            0, 0, 0, 0, 0, voicePreferences.defaultVoice.id, state.value.speed, System.currentTimeMillis(),
        )
        bookmarkWrite.withLock {
            if (item != null || bookmarkDao.find(bookmark.id) == null) bookmarkDao.insert(bookmark)
        }
        return bookmark.id
    }

    fun deleteBookmark(id: String) {
        viewModelScope.launch { removeBookmark(id) }
    }

    suspend fun removeBookmark(id: String): Boolean = bookmarkWrite.withLock { bookmarkDao.delete(id) != 0 }

    suspend fun findBookmark(id: String): Bookmark? = bookmarkDao.find(id)

    suspend fun listBookmarks(limit: Int, offset: Int): List<Bookmark> = bookmarkDao.list(limit, offset)

    fun restoreBookmark(saved: Bookmark): Boolean {
        val bookmark = currentBookmark()?.takeIf { it.id == saved.id } ?: saved
        val article = runCatching { bookmark.readArticle() }.getOrNull() ?: return false
        if (article.chapters.getOrNull(bookmark.chapter)?.paragraphs.isNullOrEmpty()) return false
        checkpoint()
        resetPageChain(article)
        showArticle(article)
        val voice = Voice.entries.firstOrNull { it.id == bookmark.voiceId } ?: Voice.default
        val speed = bookmark.speed.coerceIn(0.8f, 2.5f)
        player.setPlaybackSpeed(speed)
        mutableState.update { it.copy(speed = speed) }
        if (article.sourceUrl != null && article.nextPageUrl != null && browser.state.url != article.sourceUrl) {
            browser.open(article.sourceUrl)
            followAllowed = true
        }
        startChapter(article, bookmark.chapter, bookmark.paragraph, voice, bookmark.id, bookmark)
        startService()
        return true
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
            reading.voice.id, current.speed, System.currentTimeMillis())
    }

    private fun checkpoint() {
        val id = active?.bookmarkId ?: return
        if (bookmarks.value.none { it.id == id }) return
        val item = currentBookmark() ?: return
        viewModelScope.launch { bookmarkWrite.withLock { bookmarkDao.checkpoint(item) } }
    }

    private fun failSynthesis(azureProblem: AzureSpeechException.Reason? = null) {
        player.pause()
        mutableState.update { it.copy(generating = false, problem = ReadingProblem.Synthesis, azureProblem = azureProblem) }
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
        browser.onManualNavigation = null
        player.release()
        retire()
        database.close()
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
        val bookmarkId: String,
        val initialFraction: Double = 0.0,
        val initialOffsetMs: Long = 0,
        val positionBase: Long = 0,
    ) {
        val encodedArticle = Bookmark.encodeArticle(article)
    }
}
