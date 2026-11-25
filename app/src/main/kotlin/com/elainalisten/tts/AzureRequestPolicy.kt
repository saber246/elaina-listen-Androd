package com.elainalisten.tts

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AzureRequestLimiter(
    private val nowMs: () -> Long,
    private val wait: suspend (Long) -> Unit,
) {
    constructor() : this(SystemClock::elapsedRealtime, ::delay)

    private val mutex = Mutex()
    private val requests = ArrayDeque<Long>()

    suspend fun acquire() {
        while (true) {
            val remaining = mutex.withLock {
                val now = nowMs()
                while (requests.firstOrNull()?.let { now - it >= windowMs } == true) requests.removeFirst()
                if (requests.size < maxRequests) {
                    requests.addLast(now)
                    return
                }
                windowMs - (now - requests.first())
            }
            wait(remaining)
        }
    }

    private companion object {
        const val maxRequests = 15
        const val windowMs = 60_000L
    }
}

internal suspend fun <T> retryAzureSynthesis(
    wait: suspend (Long) -> Unit,
    request: suspend () -> T,
): T {
    var remainingRetries = 4
    var backoffMs = 2_000L
    var remainingQuotaWindowMs = 60_000L
    while (true) {
        try {
            return request()
        } catch (error: AzureSpeechException) {
            if (!error.isTransient || remainingRetries == 0) throw error
            val delayMs = if (remainingRetries == 1 && error.httpStatus == 429 && error.retryAfterMs == null) {
                // Give a quota window time to reset before the last attempt if the server gave no guidance.
                maxOf(backoffMs, remainingQuotaWindowMs)
            } else {
                maxOf(backoffMs, error.retryAfterMs ?: 0L)
            }
            wait(delayMs)
            remainingQuotaWindowMs = (remainingQuotaWindowMs - delayMs).coerceAtLeast(0L)
            remainingRetries--
            backoffMs *= 2
        }
    }
}
