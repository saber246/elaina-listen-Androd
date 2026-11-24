package com.elainalisten.mcp

import com.elainalisten.ElainaApplication
import com.elainalisten.R
import com.elainalisten.article.ArticleExtractor
import com.elainalisten.article.ReadableArticle
import com.elainalisten.browser.WebAddress
import com.elainalisten.tts.Voice
import com.elainalisten.tts.VoicePreferences
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

internal class McpActions(private val app: ElainaApplication) {
    private val browser get() = app.browser
    private val reading get() = app.reading
    private var extracted: Extracted? = null

    suspend fun call(
        tool: String,
        args: JSONObject,
        permissions: Set<McpPermission>,
        confirmDeletion: suspend (String) -> Boolean,
    ): JSONObject {
        val permission = when (tool) {
            "open_webpage", "extract_article" -> McpPermission.Browser
            "start_reading", "pause_reading", "resume_reading", "seek_reading", "set_voice",
            "set_speed", "get_playback_status" -> McpPermission.Playback
            "add_bookmark", "list_bookmarks", "delete_bookmark" -> McpPermission.Bookmarks
            else -> fail("Unknown tool.")
        }
        if (permission !in permissions) fail("This tool's permission was not granted in Android settings.")
        return when (tool) {
            "open_webpage" -> {
                val url = args.text("url")
                if (url.length > 4_096 || !WebAddress.isAllowed(url)) fail("Enter a valid HTTPS URL.")
                extracted = null
                if (!browser.open(url)) fail("The URL could not be opened.")
                JSONObject().put("url", browser.state.url).put("loading", browser.state.loading)
            }
            "extract_article" -> extract()
            "start_reading" -> {
                requireForeground()
                val article = article(args.text("articleId"))
                val chapter = args.int("chapter", 0, 0..60_000)
                val paragraph = args.int("paragraph", 0, 0..60_000)
                if (article.chapters.getOrNull(chapter)?.paragraphs?.getOrNull(paragraph) == null) {
                    fail("Chapter or paragraph is out of range.")
                }
                val voice = if (args.has("voiceId")) localVoice(args.text("voiceId"))
                    else VoicePreferences(app).defaultVoice.takeUnless(Voice::isAzure) ?: Voice.default
                reading.playArticle(article, chapter, paragraph, voice)
                playbackStatus()
            }
            "pause_reading" -> {
                reading.pause()
                playbackStatus()
            }
            "resume_reading" -> {
                requireForeground()
                val state = reading.state.value
                if (state.article == null || state.voice.isAzure || state.problem != null) {
                    fail("Open the player to resume a valid local reading session.")
                }
                if (!state.playRequested) reading.togglePlayback()
                playbackStatus()
            }
            "seek_reading" -> {
                val state = reading.state.value
                if (state.readyDurationMs <= 0 || state.voice.isAzure) fail("No local audio is ready to seek.")
                reading.seekTo(args.int("positionMs", null, 0..Int.MAX_VALUE).toLong())
                playbackStatus()
            }
            "set_voice" -> {
                val voice = localVoice(args.text("voiceId"))
                val state = reading.state.value
                if (state.article == null) {
                    VoicePreferences(app).defaultVoice = voice
                } else {
                    if (state.playRequested) requireForeground()
                    reading.selectVoice(voice)
                }
                JSONObject().put("voiceId", voice.id)
            }
            "set_speed" -> {
                val speed = (args.opt("speed") as? Number)?.toDouble()
                if (speed == null || speed !in 0.8..2.5) fail("Speed must be between 0.8 and 2.5.")
                reading.setSpeed(speed.toFloat())
                playbackStatus()
            }
            "get_playback_status" -> playbackStatus()
            "add_bookmark" -> {
                val article = if (args.has("articleId")) article(args.text("articleId"))
                    else reading.state.value.article ?: fail("There is no current reading to bookmark.")
                JSONObject().put("id", reading.putBookmark(article)).put("title", article.title)
            }
            "list_bookmarks" -> {
                val limit = args.int("limit", 20, 1..50)
                val offset = args.int("offset", 0, 0..100_000)
                val bookmarks = reading.listBookmarks(limit, offset).map { item ->
                    JSONObject().put("id", item.id).put("title", item.title)
                        .put("url", item.url ?: JSONObject.NULL).put("chapter", item.chapter)
                        .put("paragraph", item.paragraph).put("positionMs", item.positionMs)
                        .put("voiceId", item.voiceId).put("speed", item.speed.toDouble())
                }
                JSONObject().put("bookmarks", JSONArray(bookmarks)).put("offset", offset)
            }
            "delete_bookmark" -> {
                requireForeground()
                val id = args.text("id")
                if (!id.matches(Regex("[a-f0-9]{64}"))) fail("Invalid bookmark ID.")
                val item = reading.findBookmark(id) ?: fail("Bookmark was not found.")
                if (!confirmDeletion(item.title)) fail("Bookmark deletion was not approved.")
                if (!reading.removeBookmark(id)) fail("The bookmark was already removed.")
                JSONObject().put("id", id).put("deleted", true)
            }
            else -> fail("Unknown tool.")
        }
    }

    fun clear() {
        extracted = null
    }

    private suspend fun extract(): JSONObject {
        val ready = withTimeoutOrNull(18_000) {
            while (browser.state.loading) delay(100)
            browser.state
        } ?: fail("Wait for the browser to finish loading.")
        val snapshot = browser.snapshot() ?: fail("There is no readable browser page.")
        val result = withContext(Dispatchers.Default) {
            ArticleExtractor.extract(snapshot, app.getString(R.string.article_title))
        } ?: fail("No article text was found.")
        if (ready.revision != browser.state.revision) fail("The browser page changed during extraction.")
        val item = Extracted(UUID.randomUUID().toString(), ready.revision, result)
        extracted = item
        return JSONObject().put("articleId", item.id).put("title", result.title)
            .put("url", snapshot.url).put("chapters", JSONArray(result.chapters.map { chapter ->
                JSONObject().put("title", chapter.title).put("paragraphs", JSONArray(chapter.paragraphs))
            }))
    }

    private fun article(id: String): ReadableArticle =
        extracted?.takeIf { it.id == id && it.revision == browser.state.revision }?.article
            ?: fail("Extract the current page again to obtain a valid article ID.")

    private fun playbackStatus(): JSONObject {
        val state = reading.state.value
        return JSONObject().put("title", state.article?.title ?: JSONObject.NULL)
            .put("playing", state.playing).put("playRequested", state.playRequested)
            .put("generating", state.generating).put("turningPage", state.turningPage)
            .put("chapter", state.chapterIndex).put("paragraph", state.paragraphIndex)
            .put("positionMs", state.positionMs).put("readyDurationMs", state.readyDurationMs)
            .put("speed", state.speed.toDouble()).put("voiceId", state.voice.id)
            .put("problem", state.problem?.name ?: JSONObject.NULL)
    }

    private fun requireForeground() {
        if (!app.activityForeground) fail("Bring Elaina Listen to the foreground for this tool.")
    }

    private fun localVoice(id: String): Voice =
        Voice.local.firstOrNull { it.id == id } ?: fail("Choose a supported offline voice.")

    private fun JSONObject.text(field: String): String =
        opt(field) as? String ?: fail("Missing or invalid $field.")

    private fun JSONObject.int(field: String, default: Int?, range: IntRange): Int {
        if (!has(field) && default != null) return default
        val value = (opt(field) as? Number)?.toDouble() ?: fail("Missing or invalid $field.")
        if (value < range.first || value > range.last || value != value.toInt().toDouble()) {
            fail("Invalid $field.")
        }
        return value.toInt()
    }

    private fun fail(message: String): Nothing = throw McpFailure(message)

    private data class Extracted(val id: String, val revision: Long, val article: ReadableArticle)
}
