package com.dockermobile.app.docker

import com.dockermobile.app.core.Ansi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.OutputStream

enum class ExecStatus { IDLE, CONNECTING, CONNECTED, CLOSED, FAILED }

/**
 * Owns one interactive exec session (a "terminal tab") against a container.
 * Streams the hijacked socket into a text buffer the UI can render, and
 * forwards user keystrokes back up the socket.
 */
class ExecSession(
    private val repo: DockerRepo,
    private val containerId: String,
) {
    val text = kotlinx.coroutines.flow.MutableStateFlow("")
    val status = kotlinx.coroutines.flow.MutableStateFlow(ExecStatus.IDLE)
    val failure = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    private var conn: ExecConnection? = null
    private var readerJob: Job? = null
    private val writeMutex = Mutex()
    private var out: OutputStream? = null
    private var execId: String? = null

    /** Creates the exec instance, hijacks the stream and starts pumping output. */
    fun start(scope: CoroutineScope, shell: String = "/bin/sh") {
        if (status.value == ExecStatus.CONNECTED || status.value == ExecStatus.CONNECTING) return
        status.value = ExecStatus.CONNECTING
        failure.value = null

        // The hijacked socket is fully blocking — run the whole session on IO.
        readerJob = scope.launch(Dispatchers.IO) {
            try {
                val id = repo.withClient { it.execCreate(containerId, listOf(shell)) }
                execId = id
                val base = repo.currentBaseUrl()
                val connection = HijackClient.open(base, id)
                conn = connection
                out = connection.output
                status.value = ExecStatus.CONNECTED
                appendToText("[connected to $shell — type commands below]\n")

                val buf = ByteArray(8192)
                val demuxer = StreamDemuxer()
                while (!connection.closed) {
                    val n = connection.input.read(buf)
                    if (n == -1) break
                    if (n > 0) {
                        // Exec streams are raw when Tty=true; demuxer is a no-op pass-through then.
                        demuxer.feed(buf.copyOf(n)).forEach { appendToText("$it\n") }
                    }
                }
                demuxer.flush().forEach { appendToText("$it\n") }
                status.value = ExecStatus.CLOSED
                appendToText("\n[session closed]\n")
            } catch (e: Exception) {
                failure.value = e.message ?: e.javaClass.simpleName
                status.value = ExecStatus.FAILED
                appendToText("\n[error: ${e.message}]\n")
            }
        }
    }

    suspend fun send(raw: String) = writeMutex.withLock {
        out?.let { o ->
            runCatching {
                o.write(raw.toByteArray(Charsets.UTF_8))
                o.flush()
            }.onFailure {
                conn?.close()
            }
        }
    }

    /** Resize the TTY (best-effort). */
    fun resize(cols: Int, rows: Int, scope: CoroutineScope) {
        val id = execId ?: return
        scope.launch { repo.withClient { it.resizeExec(id, cols, rows) } }
    }

    fun close() {
        conn?.close()
        readerJob?.cancel()
        status.value = ExecStatus.CLOSED
    }

    private fun appendToText(chunk: String) {
        val clean = Ansi.strip(chunk)
        val cur = text.value
        val next = if (cur.length + clean.length > MAX_BUFFER) {
            (cur + clean).substring((cur.length + clean.length) - MAX_BUFFER)
        } else {
            cur + clean
        }
        text.value = next
    }

    companion object {
        private const val MAX_BUFFER = 200_000

        /** Keystroke helpers for the terminal toolbar. */
        const val CTRL_C = "\u0003"
        const val CTRL_D = "\u0004"
        const val TAB = "\t"
        const val ESC = "\u001b"
        const val NEWLINE = "\n"
    }
}
