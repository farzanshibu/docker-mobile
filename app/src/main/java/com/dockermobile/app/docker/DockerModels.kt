package com.dockermobile.app.docker

import androidx.compose.ui.graphics.Color

/** A published/mapped port on a container. */
data class UiPort(
    val hostIp: String?,
    val containerPort: Int,
    val publicPort: Int?,
    val type: String,
)

data class UiContainer(
    val id: String,
    val name: String,
    val image: String,
    val state: String,
    val status: String,
    val ports: List<UiPort>,
    val labels: Map<String, String>,
    val created: Long,
) {
    val isRunning: Boolean get() = state == "running"

    /** Which port should a browser open, per port entry (may be adjusted by VM forwarding). */
    fun tapPort(port: UiPort, forwarded: Int?): Int? = forwarded ?: port.publicPort
}

data class UiImage(
    val id: String,
    val tags: List<String>,
    val sizeBytes: Long,
    val createdUnix: Long,
) {
    val displayTag: String get() = tags.firstOrNull()?.takeIf { it != "<none>:<none>" }
        ?: id.removePrefix("sha256:").take(12)
}

data class ContainerStats(
    val cpuPercent: Double,
    val memUsedBytes: Long,
    val memLimitBytes: Long,
    val memPercent: Double,
    val netRxBytes: Long,
    val netTxBytes: Long,
) {
    val memPercentSafe: Double get() = if (memPercent.isNaN()) 0.0 else memPercent
    val cpuPercentSafe: Double get() = if (cpuPercent.isNaN()) 0.0 else cpuPercent
}

/** One event line from `POST /images/create` (layered pull progress). */
data class PullEvent(
    val status: String,
    val id: String? = null,
    val current: Long? = null,
    val total: Long? = null,
)

sealed class VmPhase {
    data object Idle : VmPhase()
    data class Booting(val message: String) : VmPhase()
    data object Running : VmPhase()
    data object Stopping : VmPhase()
    data class Failed(val message: String) : VmPhase()
}

fun stateColor(state: String): Color = when (state) {
    "running" -> Color(0xFF3FB950)      // green
    "paused", "restarting" -> Color(0xFFD29922)  // amber
    "created", "removing" -> Color(0xFF8B949E)   // grey
    "exited", "dead" -> Color(0xFFF85149)        // red
    else -> Color(0xFF8B949E)
}
