package com.dockermobile.app.docker

import okio.Buffer

/**
 * Docker multiplexes stdout/stderr when the container was created with Tty=false:
 * every frame is prefixed with an 8-byte header:
 *
 *   [STREAM_TYPE, 0, 0, 0, SIZE1, SIZE2, SIZE3, SIZE4]
 *
 * When Tty=true the stream is raw text. This demuxer auto-detects the framing
 * on the first chunk and converts either format into plain text lines.
 */
class StreamDemuxer {

    private enum class Mode { UNDETECTED, MULTIPLEXED, RAW }

    private var mode = Mode.UNDETECTED
    private val lineBuf = StringBuilder()

    /** Leftover bytes that did not yet form a complete frame (multiplexed mode). */
    private var pending = ByteArray(0)

    /**
     * Feed a raw chunk received from the daemon; returns any complete lines
     * (without trailing newline). The final partial line is returned by [flush].
     */
    fun feed(chunk: ByteArray): List<String> {
        if (mode == Mode.UNDETECTED) {
            mode = if (looksMultiplexed(chunk)) Mode.MULTIPLEXED else Mode.RAW
        }
        return when (mode) {
            Mode.MULTIPLEXED -> feedMultiplexed(chunk)
            else -> consumeText(String(chunk, Charsets.UTF_8))
        }
    }

    /** Returns true if the 8-byte header of a multiplexed stream is plausible. */
    private fun looksMultiplexed(chunk: ByteArray): Boolean {
        if (chunk.size < 8) return false
        val streamType = chunk[0].toInt() and 0xFF
        return streamType in 0..2 && chunk[1] == 0.toByte() &&
            chunk[2] == 0.toByte() && chunk[3] == 0.toByte()
    }

    private fun feedMultiplexed(chunk: ByteArray): List<String> {
        val out = mutableListOf<String>()
        var data: ByteArray = if (pending.isEmpty()) chunk else pending + chunk
        pending = ByteArray(0)

        var offset = 0
        while (true) {
            if (data.size - offset < 8) break               // incomplete header
            val size = ((data[offset + 4].toLong() and 0xFF) shl 24) or
                ((data[offset + 5].toLong() and 0xFF) shl 16) or
                ((data[offset + 6].toLong() and 0xFF) shl 8) or
                (data[offset + 7].toLong() and 0xFF)
            val payloadStart = offset + 8
            if (data.size - payloadStart < size) break       // incomplete payload
            val text = String(data, payloadStart, size.toInt(), Charsets.UTF_8)
            out += consumeText(text)
            offset = payloadStart + size.toInt()
        }
        if (offset < data.size) pending = data.copyOfRange(offset, data.size)
        return out
    }

    private fun consumeText(text: String): List<String> {
        val lines = mutableListOf<String>()
        for (c in text) {
            when {
                c == '\n' -> {
                    lines += lineBuf.toString()
                    lineBuf.setLength(0)
                }
                c == '\r' -> { /* swallow CR */ }
                else -> lineBuf.append(c)
            }
        }
        return lines
    }

    /** Call at EOF: returns the trailing partial line, if any. */
    fun flush(): List<String> {
        val out = mutableListOf<String>()
        if (pending.isNotEmpty() && mode == Mode.MULTIPLEXED) {
            // Trailing incomplete frame: emit whatever text it holds.
            val start = 8.coerceAtMost(pending.size)
            if (pending.size > start) out += consumeText(String(pending, start, pending.size - start, Charsets.UTF_8))
            pending = ByteArray(0)
        }
        if (lineBuf.isNotEmpty()) {
            out += lineBuf.toString()
            lineBuf.setLength(0)
        }
        return out
    }

    companion object {
        /** Convenience helper (also handy for tests). */
        fun frame(streamType: Int, payload: ByteArray): ByteArray {
            val header = Buffer()
            header.writeByte(streamType)
            header.writeByte(0); header.writeByte(0); header.writeByte(0)
            header.writeInt(payload.size)
            return header.readByteArray() + payload
        }
    }
}
