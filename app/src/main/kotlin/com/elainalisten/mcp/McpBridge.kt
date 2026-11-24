package com.elainalisten.mcp

import android.os.SystemClock
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal enum class McpPermission { Browser, Playback, Bookmarks }

internal class PendingBookmarkDeletion(val title: String) {
    val decision = CompletableDeferred<Boolean>()
}

internal class McpBridge(private val actions: McpActions) {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableStatus = MutableStateFlow(Status())
    val status = mutableStatus.asStateFlow()
    private val mutableDeletion = MutableStateFlow<PendingBookmarkDeletion?>(null)
    val deletion = mutableDeletion.asStateFlow()
    private var active: Listener? = null

    fun enable(permissions: Set<McpPermission>) {
        if (active != null || status.value.starting || permissions.isEmpty()) return
        mutableStatus.value = Status(starting = true)
        main.launch {
            try {
                val token = Base64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                val server = withContext(Dispatchers.IO) {
                    ServerSocket().apply {
                        try {
                            reuseAddress = true
                            bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8)
                        } catch (error: IOException) {
                            close()
                            throw error
                        }
                    }
                }
                val listener = Listener(server, token, permissions.toSet())
                active = listener
                mutableStatus.value = Status(token = token, permissions = listener.permissions)
                listener.scope.launch { accept(listener) }
            } catch (error: IOException) {
                mutableStatus.value = Status(failed = true)
            }
        }
    }

    fun disable() {
        val listener = active ?: return
        active = null
        actions.clear()
        mutableStatus.value = Status()
        mutableDeletion.value?.decision?.complete(false)
        listener.close()
    }

    fun respondToDeletion(approved: Boolean) {
        mutableDeletion.value?.decision?.complete(approved)
    }

    private suspend fun confirmDeletion(title: String): Boolean {
        if (mutableDeletion.value != null) throw McpFailure("Another deletion is awaiting confirmation.")
        val request = PendingBookmarkDeletion(title)
        mutableDeletion.value = request
        return try {
            withTimeout(25_000) { request.decision.await() }
        } catch (_: TimeoutCancellationException) {
            false
        } finally {
            mutableDeletion.value = null
        }
    }

    private suspend fun accept(listener: Listener) {
        try {
            while (!listener.server.isClosed) {
                val connection = listener.server.accept()
                if (listener.connections.size >= 4) {
                    connection.close()
                    continue
                }
                listener.connections.add(connection)
                if (listener.server.isClosed) {
                    listener.connections.remove(connection)
                    connection.close()
                    continue
                }
                listener.scope.launch {
                    try {
                        connection.use { handle(listener, it) }
                    } finally {
                        listener.connections.remove(connection)
                    }
                }
            }
        } catch (_: IOException) {
            withContext(Dispatchers.Main.immediate) {
                if (active === listener) {
                    disable()
                    mutableStatus.value = Status(failed = true)
                }
            }
        }
    }

    private suspend fun handle(listener: Listener, socket: Socket) {
        try {
            socket.soTimeout = 5_000
            val reply = try {
                val request = readRequest(socket)
                val token = request.opt("token") as? String ?: ""
                if (token.length != listener.token.length || !MessageDigest.isEqual(
                        token.toByteArray(Charsets.UTF_8), listener.token.toByteArray(Charsets.UTF_8),
                    )
                ) throw McpFailure("Invalid or expired MCP token.")
                val tool = request.opt("tool") as? String ?: throw McpFailure("Missing tool name.")
                val args = request.optJSONObject("args") ?: throw McpFailure("Invalid tool arguments.")
                val result = withTimeout(35_000) {
                    withContext(Dispatchers.Main.immediate) {
                        checkActive(listener)
                        actions.call(tool, args, listener.permissions) { title ->
                            val approved = confirmDeletion(title)
                            checkActive(listener)
                            approved
                        }.also { checkActive(listener) }
                    }
                }
                JSONObject().put("ok", true).put("result", result)
            } catch (_: TimeoutCancellationException) {
                failure("The Android operation timed out.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: McpFailure) {
                failure(error.message.orEmpty())
            } catch (_: Exception) {
                failure("Android could not complete the MCP request.")
            }
            socket.getOutputStream().write((reply.toString() + "\n").toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
            // The client or ADB forward may disconnect at any time.
        }
    }

    private fun checkActive(listener: Listener) {
        if (active !== listener) throw McpFailure("MCP authorization was revoked.")
    }

    private fun readRequest(socket: Socket): JSONObject {
        val input = socket.getInputStream().buffered()
        val bytes = ByteArrayOutputStream()
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (bytes.size() < 32_768 && SystemClock.elapsedRealtime() < deadline) {
            val value = input.read()
            if (value == '\n'.code) return JSONObject(bytes.toString(Charsets.UTF_8.name()))
            if (value == -1) break
            bytes.write(value)
        }
        throw McpFailure("Invalid bridge request.")
    }

    private fun failure(message: String) = JSONObject().put("ok", false).put("error", message)

    internal class Status(
        val token: String? = null,
        val permissions: Set<McpPermission> = emptySet(),
        val starting: Boolean = false,
        val failed: Boolean = false,
    )

    private class Listener(val server: ServerSocket, val token: String, val permissions: Set<McpPermission>) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val connections = ConcurrentHashMap.newKeySet<Socket>()

        fun close() {
            scope.cancel()
            runCatching { server.close() }
            connections.forEach { socket -> runCatching { socket.close() } }
        }
    }

    companion object { const val port = 8765 }
}

internal class McpFailure(message: String) : Exception(message)
