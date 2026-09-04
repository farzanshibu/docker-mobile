package com.dockermobile.app.core

import java.util.Locale
import java.util.concurrent.TimeUnit

/** Human-friendly formatting helpers used across the UI. */
object Format {

    fun humanBytes(bytes: Long?): String {
        if (bytes == null || bytes < 0) return "?"
        if (bytes < 1024) return "$bytes B"
        // units[0] is KB, so scale into KB first — otherwise every size is
        // reported one unit too large (189 MB shown as "180.8 GB").
        var value = bytes.toDouble() / 1024.0
        val units = listOf("KB", "MB", "GB", "TB", "PB")
        var unit = 0
        while (value >= 1024.0 && unit < units.size - 1) {
            value /= 1024.0
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    /** Unix-seconds string (Docker API) -> relative age like "3 days ago". */
    fun relativeAge(unixSeconds: String?): String {
        val secs = unixSeconds?.toLongOrNull() ?: return "?"
        val diff = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) - secs
        return when {
            diff < 60 -> "just now"
            diff < 3600 -> "${diff / 60} min ago"
            diff < 86400 -> "${diff / 3600} h ago"
            diff < 86400 * 30 -> "${diff / 86400} d ago"
            else -> "${diff / (86400 * 30)} mo ago"
        }
    }

    fun shortId(id: String?): String =
        id?.removePrefix("sha256:")?.take(12) ?: "?"
}

object Ansi {
    private val csi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    private val osc = Regex("\u001B\\][^\u0007]*(\u0007|\u001B\\\\)")
    private val misc = Regex("\u001B[@-Z\\\\-_]")
    private val ctrl = Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F]")

    /** Strip ANSI escape sequences and stray control chars, keep \n \r \t. */
    fun strip(text: String): String = text
        .replace(osc, "")
        .replace(csi, "")
        .replace(misc, "")
        .replace(ctrl, "")
}
