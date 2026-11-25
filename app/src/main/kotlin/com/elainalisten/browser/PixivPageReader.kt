package com.elainalisten.browser

import com.elainalisten.article.PixivEntry
import com.elainalisten.article.PixivNovel
import com.elainalisten.article.ReadableArticle
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal class PixivPageReader(private val evaluate: suspend (String) -> JSONObject?) {
    suspend fun novel(id: String, usePreload: Boolean): ReadableArticle? {
        val safeId = PixivNovel.validId(id) ?: return null
        if (usePreload) {
            val preloaded = evaluate("""
                (() => {
                    if (!['https://www.pixiv.net', 'https://pixiv.net'].includes(location.origin)) return null;
                    const meta = document.querySelector('meta[name="meta-preload-data"],meta#meta-preload-data');
                    if (!meta || meta.content.length > 5500000) return null;
                    try {
                        const record = JSON.parse(meta.content).novel?.['$safeId'];
                        if (!record || typeof record.content !== 'string' || record.content.length > 300000) return null;
                        return record;
                    } catch (_) { return null; }
                })()
            """.trimIndent())?.let { PixivNovel.parse(safeId, it) }
            if (preloaded != null) return preloaded
        }
        val body = response("/ajax/novel/$safeId")?.optJSONObject("body") ?: return null
        return PixivNovel.parse(safeId, body)
    }

    suspend fun catalog(id: String, includeUnavailable: Boolean): List<PixivEntry>? {
        val safeId = PixivNovel.validId(id) ?: return null
        val body = response("/ajax/novel/series/$safeId/content_titles")?.optJSONArray("body") ?: return null
        return PixivNovel.catalog(body, includeUnavailable).takeIf(List<PixivEntry>::isNotEmpty)
    }

    private suspend fun response(path: String): JSONObject? {
        val key = JSONObject.quote("elainaPixiv-${UUID.randomUUID()}")
        val target = JSONObject.quote(path)
        val started = evaluate("""
            (() => {
                if (!['https://www.pixiv.net', 'https://pixiv.net'].includes(location.origin)) return null;
                const key = $key;
                const controller = new AbortController();
                const deadline = setTimeout(() => controller.abort(), 9000);
                window[key] = {pending: true};
                (async () => {
                    try {
                        const reply = await fetch(location.origin + $target, {
                            credentials: 'same-origin', redirect: 'error', signal: controller.signal
                        });
                        if (!reply.ok || !reply.body) throw new Error();
                        const reader = reply.body.getReader();
                        const decoder = new TextDecoder();
                        let text = '', size = 0;
                        for (;;) {
                            const chunk = await reader.read();
                            if (chunk.done) break;
                            size += chunk.value.byteLength;
                            if (size > 3500000) throw new Error();
                            text += decoder.decode(chunk.value, {stream: true});
                        }
                        window[key] = {value: JSON.parse(text + decoder.decode())};
                    } catch (_) {
                        window[key] = {failed: true};
                    } finally {
                        clearTimeout(deadline);
                    }
                })();
                return {started: true};
            })()
        """.trimIndent()) ?: return null
        if (!started.optBoolean("started")) return null
        return withTimeoutOrNull(11_000) {
            var result: JSONObject
            do {
                delay(150)
                result = evaluate("""
                    (() => {
                        const result = window[$key] || {failed: true};
                        if (!result.pending) delete window[$key];
                        return result;
                    })()
                """.trimIndent()) ?: return@withTimeoutOrNull null
            } while (result.optBoolean("pending"))
            result.optJSONObject("value")?.takeIf { it.opt("error") == false }
        }
    }
}
