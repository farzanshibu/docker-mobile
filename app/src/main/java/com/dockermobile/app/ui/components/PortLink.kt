package com.dockermobile.app.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.dockermobile.app.core.AppSettings
import com.dockermobile.app.core.EndpointMode

/**
 * Opens a published container port in the browser. In embedded-VM mode the
 * phone forwards 127.0.0.1:<androidPort> into the guest; in remote mode we hit
 * the configured host directly.
 */
fun openPortInBrowser(context: Context, settings: AppSettings, androidPort: Int) {
    val url = when (settings.mode) {
        EndpointMode.EMBEDDED_VM -> "http://127.0.0.1:$androidPort"
        EndpointMode.REMOTE_TCP -> {
            val host = Uri.parse(DockerHostUtil.normalize(settings.remoteBaseUrl)).host
                ?: return Toast.makeText(context, "Remote host not configured", Toast.LENGTH_SHORT).show()
            "http://$host:$androidPort"
        }
    }
    openUrl(context, url)
}

fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show()
    }
}

object DockerHostUtil {
    fun normalize(raw: String): String {
        val url = raw.trim().trimEnd('/')
        return if (url.startsWith("http")) url else "http://$url"
    }
}
