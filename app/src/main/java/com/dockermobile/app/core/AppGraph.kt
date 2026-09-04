package com.dockermobile.app.core

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dockermobile.app.docker.ComposeEngine
import com.dockermobile.app.docker.DockerRepo
import com.dockermobile.app.store.StackStore
import com.dockermobile.app.vm.VmAssetManager
import com.dockermobile.app.vm.VmController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Where the Docker daemon lives. */
enum class EndpointMode { EMBEDDED_VM, REMOTE_TCP }

data class AppSettings(
    val mode: EndpointMode = EndpointMode.EMBEDDED_VM,
    val remoteBaseUrl: String = "",
    val daemonPort: Int = 23750,
    val sshPort: Int = 2222,
    val vmRamMb: Int = 1536,
    val vmCpus: Int = 2,
    val assetMirror: String = "",
    /**
     * Bind published container ports on 0.0.0.0 instead of 127.0.0.1, making
     * them reachable from other devices on the same Wi-Fi ("home server"
     * mode). Off by default: the Docker daemon and the guest sshd always stay
     * on loopback regardless of this flag.
     */
    val exposeOnLan: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dockermobile_settings")

/**
 * Small DataStore-backed settings repository. Writes are suspend and immediate;
 * reads are reactive flows (UI collects them, controllers take snapshots).
 */
class SettingsRepository(private val app: Application) {

    private object K {
        val mode = stringPreferencesKey("mode")
        val remoteBaseUrl = stringPreferencesKey("remote_base_url")
        val daemonPort = intPreferencesKey("daemon_port")
        val sshPort = intPreferencesKey("ssh_port")
        val vmRamMb = intPreferencesKey("vm_ram_mb")
        val vmCpus = intPreferencesKey("vm_cpus")
        val assetMirror = stringPreferencesKey("asset_mirror")
        val exposeOnLan = booleanPreferencesKey("expose_on_lan")
    }

    val settings: Flow<AppSettings> = app.dataStore.data.map { p ->
        AppSettings(
            mode = p[K.mode]?.let { runCatching { EndpointMode.valueOf(it) }.getOrNull() }
                ?: EndpointMode.EMBEDDED_VM,
            remoteBaseUrl = p[K.remoteBaseUrl] ?: "",
            daemonPort = p[K.daemonPort] ?: 23750,
            sshPort = p[K.sshPort] ?: 2222,
            vmRamMb = p[K.vmRamMb] ?: 1536,
            vmCpus = p[K.vmCpus] ?: 2,
            assetMirror = p[K.assetMirror] ?: "",
            exposeOnLan = p[K.exposeOnLan] ?: false,
        )
    }

    suspend fun snapshot(): AppSettings = settings.first()

    suspend fun setMode(mode: EndpointMode) = app.dataStore.edit { it[K.mode] = mode.name }
    suspend fun setRemoteBaseUrl(url: String) = app.dataStore.edit { it[K.remoteBaseUrl] = url.trim() }
    suspend fun setDaemonPort(port: Int) = app.dataStore.edit { it[K.daemonPort] = port.coerceIn(1024, 65535) }
    suspend fun setSshPort(port: Int) = app.dataStore.edit { it[K.sshPort] = port.coerceIn(1024, 65535) }
    suspend fun setVmRamMb(mb: Int) = app.dataStore.edit { it[K.vmRamMb] = mb.coerceIn(512, 4096) }
    suspend fun setVmCpus(n: Int) = app.dataStore.edit { it[K.vmCpus] = n.coerceIn(1, 8) }
    suspend fun setAssetMirror(url: String) = app.dataStore.edit { it[K.assetMirror] = url.trim() }
    suspend fun setExposeOnLan(on: Boolean) = app.dataStore.edit { it[K.exposeOnLan] = on }
}

/**
 * Process-wide service locator. Everything the UI needs hangs off here —
 * deliberately simple instead of pulling in Hilt for a v1 codebase.
 */
class AppGraph(app: Application) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settings = SettingsRepository(app)
    val assets = VmAssetManager(app, settings)
    val vm = VmController(app, settings)
    val repo = DockerRepo(settings, vm, appScope)
    val stacks = StackStore(app)
    val compose = ComposeEngine(repo, stacks)

    init {
        // Kick off auto-refresh + Docker event watching as soon as the app opens.
        appScope.launch { repo.startWatching() }
    }
}
