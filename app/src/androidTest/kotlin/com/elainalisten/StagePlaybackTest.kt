package com.elainalisten

import android.content.ComponentName
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.elainalisten.article.ArticleExtractor
import com.elainalisten.article.Chapter
import com.elainalisten.article.ReadableArticle
import com.elainalisten.data.BookmarkDatabase
import com.elainalisten.playback.ReadingProblem
import com.elainalisten.playback.PlaybackService
import com.elainalisten.tts.AzureSpeechException
import com.elainalisten.tts.SpeechAudio
import com.elainalisten.tts.Voice
import com.elainalisten.tts.VoicePreferences
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class StagePlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as ElainaApplication

    @Test
    fun resumingAnActiveBookmarkKeepsTheInFlightAzureRequest() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val testApp = app as PlaybackTestApplication
            val reader = app.reading
            val article = ReadableArticle("收藏续听", null, listOf(Chapter("第一章", listOf("雨点落在木窗上。"))))
            val requests = AtomicInteger()
            val response = CompletableDeferred<SpeechAudio>()
            testApp.azureSpeechOverride = {
                requests.incrementAndGet()
                response.await()
            }
            try {
                instrumentation.runOnMainSync { reader.playArticle(article, voice = Voice.AzureXiaoxiao) }
                await { requests.get() == 1 }
                instrumentation.runOnMainSync { reader.saveBookmark(article) }
                await { reader.bookmarks.value.any { it.id == reader.state.value.bookmarkId } }
                val bookmark = reader.bookmarks.value.first { it.id == reader.state.value.bookmarkId }
                assertEquals(1, requests.get())
                instrumentation.runOnMainSync { assertTrue(reader.restoreBookmark(bookmark)) }
                response.complete(SpeechAudio(FloatArray(96_000), 24_000))
                await { reader.state.value.playing && reader.state.value.readyParts == 1 }
                assertEquals(1, requests.get())
                assertNull(reader.state.value.problem)
            } finally {
                response.cancel()
                testApp.azureSpeechOverride = null
                instrumentation.runOnMainSync {
                    reader.pause()
                    reader.state.value.bookmarkId?.let(reader::deleteBookmark)
                }
            }
        }
    }

    @Test
    fun retryingAnAzureBookmarkKeepsBufferedAudioAndRestartsAtTheMissingPart() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val testApp = app as PlaybackTestApplication
            val reader = app.reading
            val first = "水流过石桥。"
            val second = "村口亮起了灯。"
            val article = ReadableArticle("收藏限流重试", null, listOf(Chapter("第一章", listOf(first, second))))
            val firstRequests = AtomicInteger()
            val secondRequests = AtomicInteger()
            testApp.azureSpeechOverride = { text ->
                if (text == first) firstRequests.incrementAndGet()
                if (text == second && secondRequests.incrementAndGet() <= 5) {
                    throw AzureSpeechException(AzureSpeechException.Reason.Quota, 429, retryAfterMs = 1_000)
                }
                SpeechAudio(FloatArray(72_000), 24_000)
            }
            try {
                val bookmark = checkNotNull(runBlocking { reader.findBookmark(reader.putBookmark(article)) })
                    .copy(voiceId = Voice.AzureXiaoxiao.id)
                instrumentation.runOnMainSync { assertTrue(reader.restoreBookmark(bookmark)) }
                await { reader.state.value.problem == ReadingProblem.Synthesis && !reader.state.value.playRequested }
                assertEquals(429, reader.state.value.azureHttpStatus)
                assertEquals(1, reader.state.value.readyParts)
                assertEquals(5, secondRequests.get())
                instrumentation.runOnMainSync { assertTrue(reader.restoreBookmark(bookmark)) }
                await { reader.state.value.paragraphIndex == 1 && reader.state.value.playing }
                assertEquals(1, firstRequests.get())
                assertEquals(6, secondRequests.get())
                assertNull(reader.state.value.problem)
            } finally {
                testApp.azureSpeechOverride = null
                instrumentation.runOnMainSync {
                    reader.pause()
                    reader.state.value.bookmarkId?.let(reader::deleteBookmark)
                }
            }
        }
    }

    @Test
    fun azureCombinesEightSentencesAndContinuesBufferedPlaybackDuringRateLimitRetries() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val testApp = app as PlaybackTestApplication
            val reader = app.reading
            val sentences = listOf("风吹过。", "水流过。", "灯亮了。", "鸟飞走。", "雨停了。", "门开了。", "云散了。", "天晴了。")
            val first = sentences.joinToString("")
            val second = "故事仍在继续。"
            val article = ReadableArticle("整段朗读测试", null, listOf(Chapter("第一章", listOf(first, second))))
            val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
            val secondRequests = AtomicInteger()
            testApp.azureSpeechOverride = { text ->
                calls += text
                if (text == second) {
                    when (secondRequests.incrementAndGet()) {
                        1 -> throw AzureSpeechException(AzureSpeechException.Reason.Quota, 429)
                        2 -> throw AzureSpeechException(AzureSpeechException.Reason.Quota, 429, retryAfterMs = 4_250)
                    }
                }
                SpeechAudio(FloatArray(16 * 24_000), 24_000)
            }
            try {
                instrumentation.runOnMainSync {
                    reader.setSpeed(1f)
                    reader.playArticle(article, voice = Voice.AzureXiaoxiao)
                }
                await { secondRequests.get() == 1 && reader.state.value.readyParts == 1 &&
                    reader.state.value.playing && reader.state.value.positionMs >= 300 }
                assertTrue(reader.state.value.generating)
                assertNull(reader.state.value.problem)
                await { reader.state.value.readyParts == 2 }
                assertEquals(listOf(first, second, second, second), calls.toList())
                assertNull(reader.state.value.problem)

                instrumentation.runOnMainSync { reader.pause() }
                sentences.indices.forEach { index ->
                    instrumentation.runOnMainSync { reader.seekTo(index * 2_000L + 500) }
                    val start = index * sentences.first().length
                    await { reader.state.value.sentenceRange == (start until start + sentences[index].length) }
                }
            } finally {
                testApp.azureSpeechOverride = null
                instrumentation.runOnMainSync { reader.pause() }
            }
        }
    }

    @Test
    fun continuousReadingAndManualNavigationCancelPendingTurn() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val reader = app.reading
            val savedSetting = reader.automatic.value
            val article = ReadableArticle(
                "自动翻页测试", "https://example.test/first",
                listOf(Chapter("第一章", listOf("山上的风轻轻吹过。")), Chapter("第二章", listOf("晚霞点亮了天边。"))),
                "https://example.test/next",
            )
            try {
                instrumentation.runOnMainSync {
                    reader.setAutomatic(true)
                    reader.setSpeed(2.5f)
                    reader.playArticle(article)
                }
                await { reader.state.value.chapterIndex == 1 && reader.state.value.playing }
                await { reader.state.value.chapterIndex == 1 && reader.state.value.turningPage }
                instrumentation.runOnMainSync { app.browser.open("https://example.test/manual") }
                await { reader.state.value.navigationCancelled && !reader.state.value.playRequested }
                assertEquals(article.sourceUrl, reader.state.value.article?.sourceUrl)
            } finally {
                instrumentation.runOnMainSync {
                    reader.pause()
                    reader.setAutomatic(savedSetting)
                    reader.setSpeed(1f)
                }
            }
        }
    }

    @Test
    fun temporaryReadingWritesAResumeRecordAndMixingIgnoresCompetingFocus() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val reader = app.reading
            val article = checkNotNull(ArticleExtractor.fromText("窗边的白云慢慢向山谷飘去。".repeat(14), "临时续听"))
            val audio = app.getSystemService(android.media.AudioManager::class.java)
            val competing = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener { }
                .build()
            val previous = reader.mixAudio.value
            try {
                instrumentation.runOnMainSync {
                    reader.setMixAudio(true)
                    reader.playArticle(article)
                }
                await { reader.state.value.playing && reader.state.value.positionMs > 500 }
                assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(competing))
                val before = reader.state.value.positionMs
                await { reader.state.value.playing && reader.state.value.positionMs > before + 300 }
                instrumentation.runOnMainSync { reader.pause() }
                await { reader.resume.value?.bookmark?.let { it.title == article.title && it.offsetMs > 0 } == true }
                val reopened = BookmarkDatabase.open(app)
                val saved = try {
                    runBlocking { checkNotNull(reopened.bookmarks().observeResume().first()).bookmark }
                } finally {
                    reopened.close()
                }
                assertEquals(article, saved.readArticle())
                assertTrue(saved.offsetMs > 0)
                assertTrue(runBlocking { reader.findBookmark(saved.id) == null })
                val replaced = checkNotNull(ArticleExtractor.fromText("临时替换。", "临时替换"))
                instrumentation.runOnMainSync {
                    reader.playArticle(replaced)
                    assertTrue(reader.restoreBookmark(saved))
                }
                await { reader.state.value.playing && reader.state.value.article == article &&
                    reader.state.value.positionMs >= saved.offsetMs }
            } finally {
                audio.abandonAudioFocusRequest(competing)
                instrumentation.runOnMainSync {
                    reader.pause()
                    reader.setMixAudio(previous)
                }
            }
        }
    }

    @Test
    fun foregroundMediaSessionContinuesAfterActivityStopsAndBookmarkRestores() {
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            val reader = app.reading
            val article = checkNotNull(ArticleExtractor.fromText(
                "夜色落在窗前，林间又响起熟悉的歌声。\n" +
                    "山脚亮起了一排灯，河水绕过街角，慢慢流向前方。".repeat(5),
                "后台收藏测试",
            ))
            val prefs = VoicePreferences(app)
            val originalVoice = prefs.defaultVoice
            instrumentation.runOnMainSync {
                prefs.defaultVoice = Voice.AzureYunxi
                reader.setSpeed(1.2f)
                reader.playArticle(article, paragraph = 1)
                assertTrue(reader.state.value.estimatedTotalMs > 0)
                assertEquals(0L, reader.state.value.readyDurationMs)
            }
            val bookmarkId = checkNotNull(reader.state.value.bookmarkId)
            try {
                await { reader.state.value.playing && reader.state.value.positionMs > 300 }
                instrumentation.runOnMainSync { reader.saveBookmark(article) }
                await { reader.bookmarks.value.any { it.id == bookmarkId } }
                activity.moveToState(Lifecycle.State.CREATED)
                val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
                val future = MediaController.Builder(app, token).buildAsync()
                val controller = future.get(15, TimeUnit.SECONDS)
                try {
                    val before = reader.state.value.positionMs
                    await { reader.state.value.playing && reader.state.value.positionMs > before + 400 }
                    val services = instrumentation.uiAutomation.executeShellCommand(
                        "dumpsys activity services com.elainalisten",
                    ).let { descriptor ->
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                            .bufferedReader().use { it.readText() }
                    }
                    assertTrue(services.contains("PlaybackService") && services.contains("isForeground=true"))
                    instrumentation.runOnMainSync { controller.pause() }
                    await { !reader.state.value.playRequested && !reader.state.value.playing }
                    instrumentation.runOnMainSync { controller.play() }
                    await { reader.state.value.playing }
                    instrumentation.runOnMainSync { controller.pause() }
                    await { !reader.state.value.playRequested }
                } finally {
                    instrumentation.runOnMainSync { MediaController.releaseFuture(future) }
                }
                val reopened = BookmarkDatabase.open(app)
                val persisted = try {
                    runBlocking { checkNotNull(reopened.bookmarks().find(bookmarkId)) }
                } finally {
                    reopened.close()
                }
                assertEquals(1, persisted.paragraph)
                assertEquals(Voice.AzureYunxi.id, persisted.voiceId)
                assertEquals(1.2f, persisted.speed, 0.01f)
                assertTrue(persisted.offsetMs > 0)
                activity.moveToState(Lifecycle.State.RESUMED)
                activity.recreate()
                assertEquals(article, reader.state.value.article)
                val replacement = checkNotNull(ArticleExtractor.fromText("临时朗读。", "临时"))
                instrumentation.runOnMainSync {
                    reader.playArticle(replacement)
                    assertTrue(reader.restoreBookmark(persisted))
                }
                await { reader.state.value.article == article && reader.state.value.playing &&
                    reader.state.value.positionMs >= persisted.offsetMs }
                assertEquals(1, reader.state.value.paragraphIndex)
                assertEquals(Voice.AzureYunxi, reader.state.value.voice)
                assertEquals(1.2f, reader.state.value.speed, 0.01f)
            } finally {
                instrumentation.runOnMainSync {
                    reader.pause()
                    reader.deleteBookmark(bookmarkId)
                    prefs.defaultVoice = originalVoice
                    reader.setSpeed(1f)
                }
                await { reader.bookmarks.value.none { it.id == bookmarkId } }
            }
        }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(30)
        assertTrue("Timed out at ${app.reading.state.value}", predicate())
    }
}
