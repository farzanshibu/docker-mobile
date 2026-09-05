package com.dockermobile.app.vm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dockermobile.app.DockMobileApp
import com.dockermobile.app.core.EndpointMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Brings the embedded VM back up after a reboot, so a phone left running as a
 * home server does not need someone to open the app and press Start.
 *
 * Opt-in: does nothing unless Settings -> "Start the VM when the phone boots"
 * is on. Android also only delivers BOOT_COMPLETED to apps that have been
 * launched at least once since install (and never to force-stopped ones), so
 * the very first run is always manual.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? DockMobileApp ?: return

        // Settings live in DataStore, so the decision is async; hold the
        // broadcast open until it is made. Starting a foreground service from
        // BOOT_COMPLETED is one of the documented exemptions to the background
        // FGS-start restrictions, but only inside this window.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = app.graph.settings.snapshot()
                when {
                    !settings.startOnBoot -> Unit
                    settings.mode != EndpointMode.EMBEDDED_VM -> Unit
                    // Without assets the service would start, fail, and leave a
                    // notification behind on every single boot.
                    !app.graph.vm.assets.report().allPresent ->
                        Log.w(TAG, "startOnBoot is on but VM assets are incomplete — skipping")
                    else -> withContext(Dispatchers.Main) { VmService.start(context) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "boot start failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DockerMobileBoot"
    }
}
