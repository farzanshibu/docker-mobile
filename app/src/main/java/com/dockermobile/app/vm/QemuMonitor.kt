package com.dockermobile.app.vm

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Speaks the QEMU Human Monitor Protocol (HMP) over the monitor unix socket.
 *
 * Used at runtime to add/remove `hostfwd` rules so published container ports
 * inside the guest become reachable on 127.0.0.1 on the phone — without
 * restarting the VM.
 */
class QemuMonitor {

    private val lock = ReentrantLock()
    private var lastError: String? = null

    fun lastErrorOrNull(): String? = lastError

    private fun connect(path: String): LocalSocket {
        val socket = LocalSocket()
        socket.connect(LocalSocketAddress(File(path).absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        socket.soTimeout = 3000
        return socket
    }

    /**
     * Sends one HMP command and waits for the `(qemu)` prompt.
     * Throws IOException with the monitor's complaint if the command failed.
     * Blocking call — invoke from Dispatchers.IO.
     */
    fun command(socketPath: String, command: String) {
        lock.withLock {
            val socket = connect(socketPath)
            try {
                val input = socket.inputStream
                val output = socket.outputStream

                // Drain the banner up to the first prompt.
                readUntilPrompt(input, firstConnect = true)

                output.write((command + "\n").toByteArray(Charsets.US_ASCII))
                output.flush()

                val response = readUntilPrompt(input, firstConnect = false)
                val failure = response.lineSequence()
                    .filter { it.contains("Error", ignoreCase = true) || it.contains("could not", ignoreCase = true) }
                    .firstOrNull()
                if (failure != null) {
                    lastError = failure.trim()
                    throw java.io.IOException("Monitor: $failure")
                }
                lastError = null
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    /**
     * Adds a rule: Android [bindAddress]:[hostPort] -> guest [guestPort].
     *
     * [bindAddress] is 127.0.0.1 by default, which keeps a published port
     * reachable only from the phone itself. Pass 0.0.0.0 to serve it to the
     * local network (Settings -> "Expose published ports on Wi-Fi").
     */
    fun addHostForward(
        socketPath: String,
        hostPort: Int,
        guestPort: Int,
        bindAddress: String = LOOPBACK,
    ) {
        command(socketPath, "hostfwd_add tcp:$bindAddress:$hostPort-:$guestPort")
    }

    /** Best-effort removal; older QEMU builds may not support it (errors ignored). */
    fun removeHostForward(socketPath: String, hostPort: Int, bindAddress: String = LOOPBACK) {
        try {
            command(socketPath, "hostfwd_remove tcp:$bindAddress:$hostPort")
        } catch (_: Exception) {
        }
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val ALL_INTERFACES = "0.0.0.0"
    }

    /** Reads bytes until the "(qemu)" prompt appears or the deadline passes. */
    private fun readUntilPrompt(input: java.io.InputStream, firstConnect: Boolean): String {
        val sb = StringBuilder()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(if (firstConnect) 5 else 6)
        val buf = ByteArray(2048)
        while (System.nanoTime() < deadline) {
            val available = input.available()
            if (available > 0) {
                val n = input.read(buf, 0, buf.size)
                if (n == -1) break
                sb.append(String(buf, 0, n, Charsets.UTF_8))
                if (sb.contains("(qemu)")) return sb.toString()
            } else {
                Thread.sleep(40)
            }
        }
        return sb.toString()
    }
}
