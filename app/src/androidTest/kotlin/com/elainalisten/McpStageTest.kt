package com.elainalisten

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.elainalisten.article.ArticleExtractor
import com.elainalisten.mcp.McpBridge
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class McpStageTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as ElainaApplication

    @Test
    fun enabledScopesAuthenticateAndRevocationInvalidatesToken() {
        enterSettings()
        assertTrue(runCatching { request("invalid", "get_playback_status") }.exceptionOrNull() is IOException)
        compose.onNodeWithText(text(R.string.mcp_enable)).assertIsNotEnabled()
        try {
            compose.onNodeWithTag("mcp-permission-Playback").performClick()
            val token = enable()
            assertFalse(request("wrong", "get_playback_status").getBoolean("ok"))
            assertTrue(request(token, "get_playback_status").getBoolean("ok"))
            assertFalse(request(token, "list_bookmarks").getBoolean("ok"))
            val cloud = JSONObject().put("voiceId", "zh-CN-YunxiNeural")
            assertFalse(request(token, "set_voice", cloud).getBoolean("ok"))
            compose.onNodeWithText(text(R.string.mcp_disable)).performClick()
            compose.waitUntil(timeoutMillis = 5_000) { app.mcp.status.value.token == null }
            assertTrue(runCatching { request(token, "get_playback_status") }.exceptionOrNull() is IOException)
            val replacement = enable()
            assertNotEquals(token, replacement)
            assertFalse(request(token, "get_playback_status").getBoolean("ok"))
        } finally {
            compose.runOnIdle { app.mcp.disable() }
        }
    }

    @Test
    fun deleteBookmarkRequiresOnDeviceApproval() {
        enterSettings()
        val article = checkNotNull(ArticleExtractor.fromText("山谷里落下轻细的雨。", "MCP 授权测试"))
        val id = runBlocking { app.reading.putBookmark(article) }
        try {
            compose.onNodeWithTag("mcp-permission-Bookmarks").performClick()
            val token = enable()
            val args = JSONObject().put("id", id)
            val first = CompletableFuture.supplyAsync { request(token, "delete_bookmark", args) }
            awaitDeletion()
            compose.onNodeWithText(text(R.string.mcp_refuse_delete)).performClick()
            assertFalse(first.get(10, TimeUnit.SECONDS).getBoolean("ok"))
            assertTrue(runBlocking { app.reading.findBookmark(id) != null })
            val second = CompletableFuture.supplyAsync { request(token, "delete_bookmark", args) }
            awaitDeletion()
            compose.onNodeWithText(text(R.string.mcp_allow_delete)).performClick()
            assertTrue(second.get(10, TimeUnit.SECONDS).getJSONObject("result").getBoolean("deleted"))
            assertTrue(runBlocking { app.reading.findBookmark(id) == null })
        } finally {
            compose.runOnIdle { app.mcp.disable() }
            runBlocking { app.reading.removeBookmark(id) }
        }
    }

    private fun enterSettings() {
        compose.onNodeWithContentDescription(text(R.string.nav_settings)).performClick()
        compose.onNodeWithText(text(R.string.mcp_title)).performClick()
    }

    private fun enable(): String {
        compose.onNodeWithText(text(R.string.mcp_enable)).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { app.mcp.status.value.token != null }
        return checkNotNull(app.mcp.status.value.token)
    }

    private fun awaitDeletion() {
        compose.waitUntil(timeoutMillis = 5_000) { app.mcp.deletion.value != null }
        compose.onNodeWithText(text(R.string.mcp_delete_title)).assertIsDisplayed()
    }

    private fun text(id: Int) = compose.activity.getString(id)

    private fun request(token: String, tool: String, args: JSONObject = JSONObject()): JSONObject {
        return Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", McpBridge.port), 1_000)
            socket.soTimeout = 35_000
            val json = JSONObject().put("token", token).put("tool", tool).put("args", args)
            socket.getOutputStream().write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
            JSONObject(socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine())
        }
    }
}
