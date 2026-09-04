package com.dockermobile.app.docker

import com.dockermobile.app.core.AppSettings
import com.dockermobile.app.core.EndpointMode
import com.dockermobile.app.core.Format
import com.dockermobile.app.vm.VmController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class DockerRepo(
    private val settings: com.dockermobile.app.core.SettingsRepository,
    private val vm: VmController,
    private val scope: CoroutineScope,
) {
    val containers = MutableStateFlow<List<UiContainer>>(emptyList())
    val images = MutableStateFlow<List<UiImage>>(emptyList())
    val lastError = MutableStateFlow<String?>(null)
    val version = MutableStateFlow<String?>(null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val messages: SharedFlow<String> = _messages

    @Volatile private var watchingStarted = false
    @Volatile private var lastSyncPorts: Set<Int> = emptySet()

    // ---------------------------------------------------------------- access

    /** Resolves the daemon base URL for the current mode (or throws with guidance). */
    suspend fun currentBaseUrl(): String {
        val s = settings.snapshot()
        return when (s.mode) {
            EndpointMode.EMBEDDED_VM -> {
                if (vm.phase.value !is VmPhase.Running) {
                    throw DockerException("The embedded VM is not running — open the VM tab and start it first.")
                }
                "http://127.0.0.1:${s.daemonPort}"
            }
            EndpointMode.REMOTE_TCP -> {
                val base = DockerClient.normalizeBaseUrl(s.remoteBaseUrl)
                if (base.isBlank()) throw DockerException("No remote daemon configured — set it in Settings.")
                base
            }
        }
    }

    suspend fun <T> withClient(block: suspend (DockerClient) -> T): T {
        val client = DockerClient(currentBaseUrl())
        return block(client)
    }

    // -------------------------------------------------------------- watching

    fun startWatching() {
        if (watchingStarted) return
        watchingStarted = true
        scope.launch { refreshLoop() }
        scope.launch { eventsLoop() }
    }

    private suspend fun refreshLoop() {
        while (scope.isActive) {
            refreshQuietly()
            delay(5000)
        }
    }

    private suspend fun eventsLoop() {
        while (scope.isActive) {
            try {
                val base = currentBaseUrl()
                DockerClient(base).events().collect { ev ->
                    val action = ev["Action"]?.jsonPrimitive?.content ?: ""
                    if (action in setOf("start", "stop", "die", "destroy", "create", "kill", "rename", "pause", "unpause")) {
                        refreshQuietly()
                        if (action in setOf("start", "die", "destroy")) syncPortForwards()
                    }
                }
            } catch (e: Exception) {
                lastError.value = e.message
                delay(4000)
            }
        }
    }

    // --------------------------------------------------------------- refresh

    suspend fun refresh() {
        try {
            withClient { client ->
                version.value = runCatching { client.version() }.getOrNull()
                val cs = client.listContainers(all = true)
                containers.value = cs.mapNotNull { el ->
                    (el as? JsonObject)?.let { mapContainer(it) }
                }
                val im = client.listImages()
                images.value = im.mapNotNull { el ->
                    (el as? JsonObject)?.let { mapImage(it) }
                }
                lastError.value = null
            }
            syncPortForwards()
        } catch (e: Exception) {
            lastError.value = e.message
        }
    }

    private suspend fun refreshQuietly() {
        try { refresh() } catch (_: Exception) { /* surfaced via lastError */ }
    }

    private fun mapContainer(o: JsonObject): UiContainer {
        val names = (o["Names"] as? JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?: emptyList()
        val ports = (o["Ports"] as? JsonArray)?.mapNotNull { p ->
            (p as? JsonObject)?.let {
                UiPort(
                    hostIp = it["IP"]?.jsonPrimitive?.content,
                    containerPort = it["PrivatePort"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    publicPort = it["PublicPort"]?.jsonPrimitive?.content?.toIntOrNull(),
                    type = it["Type"]?.jsonPrimitive?.content ?: "tcp",
                )
            }
        }.orEmpty().sortedBy { it.publicPort ?: Int.MAX_VALUE }
        val labels = (o["Labels"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        return UiContainer(
            id = o["Id"]?.jsonPrimitive?.content.orEmpty(),
            name = (names.firstOrNull() ?: o["Id"]?.jsonPrimitive?.content.orEmpty()).removePrefix("/"),
            image = o["Image"]?.jsonPrimitive?.content.orEmpty(),
            state = o["State"]?.jsonPrimitive?.content ?: "unknown",
            status = o["Status"]?.jsonPrimitive?.content ?: "",
            ports = ports,
            labels = labels,
            created = o["Created"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        )
    }

    private fun mapImage(o: JsonObject): UiImage = UiImage(
        id = o["Id"]?.jsonPrimitive?.content.orEmpty(),
        tags = (o["RepoTags"] as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?: emptyList(),
        sizeBytes = o["Size"]?.jsonPrimitive?.longOrNull ?: 0L,
        createdUnix = o["Created"]?.jsonPrimitive?.longOrNull ?: 0L,
    )

    // --------------------------------------------------------------- actions

    fun containerAction(id: String, action: String, onDone: suspend () -> Unit = {}) {
        scope.launch {
            try {
                withClient { c ->
                    when (action) {
                        "start" -> c.startContainer(id)
                        "stop" -> c.stopContainer(id)
                        "restart" -> c.restartContainer(id)
                        "kill" -> c.killContainer(id)
                        else -> throw DockerException("Unknown action $action")
                    }
                }
                _messages.emit("Container $action — done")
            } catch (e: Exception) {
                // 304 means already in desired state; not an error.
                if (e !is DockerException || e.code != 304) {
                    lastError.value = e.message
                    _messages.emit("Failed: ${e.message}")
                }
            }
            onDone()
            refreshQuietly()
        }
    }

    fun removeContainer(id: String, name: String) {
        scope.launch {
            try {
                withClient { it.removeContainer(id, force = true) }
                _messages.emit("Removed $name")
            } catch (e: Exception) {
                _messages.emit("Remove failed: ${e.message}")
            }
            refreshQuietly()
        }
    }

    fun deleteImage(id: String, tag: String) {
        scope.launch {
            try {
                withClient { it.removeImage(id, force = true) }
                _messages.emit("Deleted image $tag")
            } catch (e: Exception) {
                _messages.emit("Delete failed: ${e.message}")
            }
            refreshQuietly()
        }
    }

    /** Run dialog entry point. */
    fun runImage(
        image: String,
        name: String?,
        portPairs: List<Pair<Int, Int>>,
        env: List<String>,
        cmd: List<String>?,
        restartAlways: Boolean,
    ) {
        scope.launch {
            try {
                val spec = withClient { c ->
                    c.buildRunSpec(
                        image = image, name = name, portPairs = portPairs,
                        env = env, cmd = cmd,
                        restart = if (restartAlways) "always" else null,
                    )
                }
                val id = withClient { it.createContainer(spec, name) }
                withClient { it.startContainer(id) }
                _messages.emit("Started $image")
            } catch (e: Exception) {
                _messages.emit("Run failed: ${e.message}")
            }
            refreshQuietly()
        }
    }

    fun pullImage(ref: String, onEvent: (PullEvent) -> Unit, onDone: (Throwable?) -> Unit): JobHandle =
        JobHandle(scope.launch {
            try {
                withClient { it.pull(ref) }.collect { onEvent(it) }
                onDone(null)
                _messages.emit("Pulled $ref")
            } catch (e: Exception) {
                onDone(e)
                _messages.emit("Pull failed: ${e.message}")
            }
            refreshQuietly()
        })

    class JobHandle internal constructor(private val job: kotlinx.coroutines.Job) {
        fun cancel() = job.cancel()
    }

    fun newExecSession(containerId: String): ExecSession = ExecSession(this, containerId)

    // ------------------------------------------------- port forwarding (VM)

    /**
     * Mirrors published container ports of running containers onto the Android
     * host through QEMU monitor hostfwd rules (embedded mode only).
     */
    private suspend fun syncPortForwards() {
        val s = settings.snapshot()
        if (s.mode != EndpointMode.EMBEDDED_VM) return
        if (vm.phase.value !is VmPhase.Running) return

        val published = containers.value
            .filter { it.isRunning }
            .flatMap { c -> c.ports.mapNotNull { it.publicPort } }
            .toSet()
        if (published == lastSyncPorts) return
        lastSyncPorts = published
        vm.syncHostForwards(published)
    }

    /** Android-side port for a container port (embedded VM), or the raw public port. */
    fun androidPortFor(port: UiPort, s: AppSettings): Int? {
        return if (s.mode == EndpointMode.EMBEDDED_VM) {
            vm.hostForwards.value[port.publicPort ?: return null]
        } else {
            port.publicPort
        }
    }
}
