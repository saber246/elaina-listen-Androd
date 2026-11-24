package com.elainalisten.playback

internal sealed interface SleepTimer {
    data object ChapterEnd : SleepTimer
    data class Countdown(val remainingMs: Long) : SleepTimer

    companion object {
        val durationMinutes = 15..120
        const val minuteMs = 60_000L
        const val fadeMs = 1_000L
    }
}
