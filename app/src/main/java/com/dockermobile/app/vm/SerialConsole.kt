package com.dockermobile.app.vm

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File
import java.io.OutputStream

/**
 * Streams the guest serial console (ttyAMA0) exposed by QEMU on a unix socket.
 * Useful for observing boot messages and logging in as root if the daemon
 * refuses to come up.
 *
 * [readLoop] blocks — launch it on Dispatchers.IO.
 */
class SerialConsole {

    private var socket: LocalSocket? = null
    private var out: OutputStream? = null
    private val writeLock = Any()

    @Volatile var shouldReconnect: Boolean = true
        private set

    fun readLoop(socketPath: String, onChunk: (String) -> Unit) {
        val path = File(socketPath).absolutePath
        while (shouldReconnect) {
            val s = LocalSocket()
            try {
                s.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
                socket = s
                out = s.outputStream
                val buf = ByteArray(4096)
                while (true) {
                    val n = s.inputStream.read(buf)
                    if (n == -1) break
                    if (n > 0) onChunk(String(buf, 0, n, Charsets.UTF_8))
                }
            } catch (_: Exception) {
                // Socket not there yet or VM went away; retry below.
            } finally {
                runCatching { s.close() }
                socket = null
                out = null
            }
            if (!shouldReconnect) return
            try { Thread.sleep(1500) } catch (_: InterruptedException) { return }
        }
    }

    fun send(raw: String) {
        synchronized(writeLock) {
            runCatching {
                out?.write(raw.toByteArray(Charsets.UTF_8))
                out?.flush()
            }
        }
    }

    fun close() {
        shouldReconnect = false
        runCatching { socket?.close() }
    }

    fun reset() {
        shouldReconnect = true
    }
}
