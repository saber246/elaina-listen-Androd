package com.elainalisten.playback

import android.content.Context
import com.elainalisten.tts.AzureAudioCache
import java.io.File
import java.util.UUID

internal object ReadingFiles {
    private val process = UUID.randomUUID().toString()
    private val sessionName = Regex("reading-(?:[0-9a-f-]{36}-)?[0-9a-f-]{36}\\.wav")

    fun newSession(context: Context): File = File(context.cacheDir, "reading-$process-${UUID.randomUUID()}.wav")

    fun cleanableBytes(context: Context): Long =
        leftovers(context).sumOf(File::length) + AzureAudioCache(context).sizeBytes()

    fun clear(context: Context): Long = AzureAudioCache(context).clear() + leftovers(context).sumOf { file ->
        val bytes = file.length()
        if (file.delete()) bytes else 0
    }

    private fun leftovers(context: Context): List<File> = context.cacheDir.listFiles().orEmpty().filter { file ->
        file.isFile && sessionName.matches(file.name) && !file.name.startsWith("reading-$process-")
    }
}
