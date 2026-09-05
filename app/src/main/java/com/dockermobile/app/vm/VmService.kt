package com.dockermobile.app.vm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.dockermobile.app.MainActivity
import com.dockermobile.app.R
import com.dockermobile.app.core.AppGraph
import com.dockermobile.app.docker.VmPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the QEMU process alive and visible while the
 * embedded VM hosts the Docker daemon.
 */
class VmService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val graph: AppGraph by lazy { (application as com.dockermobile.app.DockMobileApp).graph }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch { graph.vm.stop() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startVmAndForeground()
        }
        return START_STICKY
    }

    private fun startVmAndForeground() {
        createChannel()
        val notification = buildNotification(getString(R.string.notif_vm_booting))
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)

        acquireWakeLock()
        acquireWifiLock()

        scope.launch {
            graph.vm.phase.collect { phase ->
                when (phase) {
                    is VmPhase.Running ->
                        updateNotification(buildNotification(getString(R.string.notif_vm_running_text)))
                    is VmPhase.Failed -> {
                        updateNotification(buildNotification(phase.message.take(120)))
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    else -> Unit
                }
            }
        }
        scope.launch {
            try {
                graph.vm.start()
            } catch (e: Exception) {
                updateNotification(buildNotification("VM error: ${e.message?.take(120)}"))
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DockerMobile:vm").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // 12h safety cap
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    /**
     * A PARTIAL_WAKE_LOCK keeps the CPU alive but says nothing about the radio:
     * with the screen off Wi-Fi drops into power save, which shows up as
     * seconds-long stalls (or dropped connections) on a published port. The
     * high-performance Wi-Fi lock is what keeps a phone usable as a server.
     */
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = runCatching {
            wm.createWifiLock(mode, "DockerMobile:vm").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.release() }
        wifiLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        releaseWifiLock()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ notifications

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, VmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_vm_running_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop_vm), stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(n: Notification) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, n)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_vm),
                NotificationManager.IMPORTANCE_LOW,
            )
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "vm_status"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.dockermobile.app.vm.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, VmService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, VmService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
