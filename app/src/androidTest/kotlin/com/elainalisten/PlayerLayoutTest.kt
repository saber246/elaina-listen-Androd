package com.elainalisten

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.elainalisten.article.ArticleExtractor
import com.elainalisten.tts.Voice
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlayerLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun playerControlsStayReachableWhileDetailsScroll() {
        val reading = compose.activity.reading
        val sample = checkNotNull(ArticleExtractor.fromText(
            "夕阳穿过窗户照在桌上。她把书翻到最后一页，抬头看见远处的灯亮了。".repeat(15),
            "黄昏后的图书馆",
        ))
        try {
            compose.runOnIdle {
                reading.setSpeed(1f)
                reading.playArticle(sample, voice = Voice.AzureXiaoxiao)
                reading.pause()
            }
            compose.onNodeWithTag("tab-player").performClick()
            val toggle = compose.onNodeWithTag("player-toggle")
            val dock = compose.onNodeWithTag("player-dock")
            val seek = compose.onNodeWithTag("player-progress")
            toggle.assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            seek.assertIsDisplayed()

            val arguments = InstrumentationRegistry.getArguments()
            val compactHeight = arguments.getString("shortPlayer") == "true"
            if (!compactHeight) {
                val pinned = dock.getUnclippedBoundsInRoot()
                val navigation = compose.onNodeWithTag("tab-player").getUnclippedBoundsInRoot()
                assertTrue(pinned.bottom <= navigation.top)
                compose.onNodeWithTag("player-speed").performScrollTo().assertIsDisplayed()
                assertEquals(pinned, dock.getUnclippedBoundsInRoot())
                toggle.assertIsDisplayed()
            } else {
                compose.onNodeWithTag("player-speed").performScrollTo().assertIsDisplayed()
                toggle.performScrollTo().assertIsDisplayed()
            }

            compose.onNodeWithTag("player-original").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(R.string.sleep_start)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(R.string.queue_add)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("player-original").performScrollTo()
            if (arguments.getString("capturePlayer") == "true") {
                compose.waitUntil(timeoutMillis = 15_000) { !reading.state.value.generating }
                compose.runOnIdle { reading.seekTo(42_000L) }
                val screen = compose.onRoot().captureToImage().asAndroidBitmap()
                File(compose.activity.cacheDir, "player-layout-preview.png").outputStream().use {
                    check(screen.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
        } finally {
            compose.runOnIdle { reading.pause() }
        }
    }
}
