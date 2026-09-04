package com.dockermobile.app.docker

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/**
 * Docker's "hijacked" connection: POST /exec/{id}/start with an Upgrade header
 * makes the daemon answer `101 UPGRADED` and hand the raw TCP socket over to
 * the exec'd process — stdin/stdout become a bidirectional byte pipe.
 *
 * OkHttp does not expose post-upgrade sockets, so this is implemented on a
 * plain java.net.Socket (TLS supported via SSLSocketFactory for https:// URLs).
 */
class ExecConnection internal constructor(
    val socket: Socket,
    val input: InputStream,
    val output: OutputStream,
) {
    @Volatile var closed = false
        private set

    fun close() {
        if (!closed) {
            closed = true
            runCatching { socket.close() }
        }
    }
}

object HijackClient {

    /**
     * Opens the hijacked stream for [execId] against the daemon at [baseUrl]
     * (http://host:port or https://host:port).
     */
    fun open(baseUrl: String, execId: String): ExecConnection {
        val url = runCatching { java.net.URI(baseUrl.trim()) }
            .getOrElse { throw DockerException("Invalid daemon URL: $baseUrl") }
        val host = url.host ?: throw DockerException("Daemon URL has no host: $baseUrl")
        val port = if (url.port > 0) url.port else if (url.scheme == "https") 443 else 2375
        val secure = url.scheme == "https"

        val socket = if (secure) {
            val f = SSLSocketFactory.getDefault() as SSLSocketFactory
            val s = f.createSocket()
            s.connect(InetSocketAddress(host, port), 8000)
            s
        } else {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 8000)
            s
        }
        socket.soTimeout = 0
        socket.tcpNoDelay = true

        try {
            val body = """{"Detach":false,"Tty":true}"""
            val req = buildString {
                append("POST /exec/").append(execId).append("/start HTTP/1.1\r\n")
                append("Host: ").append(host).append(':').append(port).append("\r\n")
                append("User-Agent: DockerMobile/0.1\r\n")
                append("Content-Type: application/json\r\n")
                append("Connection: Upgrade\r\n")
                append("Upgrade: tcp\r\n")
                append("Content-Length: ").append(body.toByteArray().size).append("\r\n")
                append("\r\n")
                append(body)
            }
            socket.getOutputStream().apply {
                write(req.toByteArray(Charsets.US_ASCII))
                flush()
            }

            val input = socket.getInputStream()
            val statusLine = readLineAscii(input)
                ?: throw DockerException("Daemon closed connection during exec handshake")

            // Headers up to the blank line.
            while (true) {
                val line = readLineAscii(input) ?: break
                if (line.isEmpty()) break
            }

            val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
            if (code != 101 && code != 200) {
                throw DockerException("Exec handshake failed: $statusLine")
            }
            return ExecConnection(socket, input, socket.getOutputStream())
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    private fun readLineAscii(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }
}
