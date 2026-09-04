package com.dockermobile.app.docker

import com.dockermobile.app.store.StackStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.yaml.snakeyaml.Yaml

/**
 * A pragmatic mini "docker compose" that runs on top of the plain Engine API:
 * parses compose.yaml, pulls images, creates + starts containers in
 * dependency order, and tears stacks down by label.
 *
 * Supported service keys: image, ports, environment, command, entrypoint,
 * working_dir, restart, depends_on, tty, volumes (bind to per-service folder).
 */
class ComposeEngine(
    private val repo: DockerRepo,
    private val stacks: StackStore,
) {

    sealed class Ev {
        data class Info(val text: String) : Ev()
        data class Pull(val layer: String?, val text: String) : Ev()
        data class Done(val ok: Boolean, val text: String) : Ev()
    }

    suspend fun up(
        project: String,
        yaml: String,
        onEvent: suspend (Ev) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        var ok = true
        try {
            val services = parse(yaml)
            if (services.isEmpty()) {
                onEvent(Ev.Done(false, "compose.yaml contains no services"))
                return@withContext false
            }
            val order = topologicalOrder(services)
            onEvent(Ev.Info("Stack '$project': ${order.joinToString(", ")}"))

            for (name in order) {
                val svc = services.getValue(name)
                val cname = "$project-$name"
                onEvent(Ev.Info("[$name] ensuring image ${svc.image}"))

                val imagePresent = repo.withClient { it.imageExists(svc.image) }
                if (!imagePresent) {
                    repo.withClient { c -> c.pull(svc.image) }.collect { p ->
                        onEvent(Ev.Pull(p.id, "[${p.id ?: "pull"}] ${p.status}"))
                    }
                }

                val existing = repo.containers.value.firstOrNull { it.name == cname }
                val containerId: String
                if (existing != null) {
                    containerId = existing.id
                    onEvent(Ev.Info("[$name] reusing container $cname"))
                } else {
                    val binds = svc.volumes.map { vol ->
                        val hostPart = stacks.volumeDir(project, name).absolutePath
                        val containerPart = vol.substringAfter(':').ifBlank { vol }
                        "$hostPart:$containerPart"
                    }
                    val spec = repo.withClient {
                        it.buildRunSpec(
                            image = svc.image,
                            name = cname,
                            portPairs = svc.ports,
                            env = svc.env,
                            cmd = svc.cmd,
                            binds = binds,
                            restart = svc.restart,
                            labels = mapOf(
                                "com.docker.compose.project" to project,
                                "com.docker.compose.service" to name,
                            ),
                            tty = svc.tty,
                        )
                    }
                    containerId = repo.withClient { it.createContainer(spec, cname) }
                    onEvent(Ev.Info("[$name] created $cname"))
                }
                repo.withClient { it.startContainer(containerId) }
                onEvent(Ev.Info("[$name] started"))
            }
            onEvent(Ev.Done(true, "Stack '$project' is up"))
        } catch (e: Exception) {
            ok = false
            onEvent(Ev.Done(false, "Up failed: ${e.message}"))
        }
        ok
    }

    suspend fun down(project: String, onEvent: suspend (Ev) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            var ok = true
            try {
                repo.withClient { c ->
                    val targets = c.listContainers(all = true).mapNotNull { el ->
                        val o = el as? JsonObject ?: return@mapNotNull null
                        val labels = (o["Labels"] as? JsonObject)
                            ?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                        if (labels["com.docker.compose.project"] != project) return@mapNotNull null
                        val id = o["Id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val name = (o["Names"] as? JsonArray)
                            ?.firstOrNull()?.jsonPrimitive?.content?.removePrefix("/") ?: "?"
                        val state = o["State"]?.jsonPrimitive?.content ?: ""
                        Triple(id, name, state)
                    }
                    targets.forEach { (id, name, state) ->
                        onEvent(Ev.Info("[$name] stopping…"))
                        runCatching { if (state == "running") c.stopContainer(id) }
                        runCatching { c.removeContainer(id, force = true) }
                        onEvent(Ev.Info("[$name] removed"))
                    }
                    if (targets.isEmpty()) onEvent(Ev.Info("No containers found for '$project'"))
                }
                onEvent(Ev.Done(true, "Stack '$project' is down"))
            } catch (e: Exception) {
                ok = false
                onEvent(Ev.Done(false, "Down failed: ${e.message}"))
            }
            ok
        }

    // ------------------------------------------------------------------ yaml

    internal data class Service(
        val image: String,
        val ports: List<Pair<Int, Int>>,
        val env: List<String>,
        val cmd: List<String>?,
        val restart: String?,
        val tty: Boolean,
        val volumes: List<String>,
        val dependsOn: List<String>,
    )

    @Suppress("UNCHECKED_CAST")
    internal fun parse(yaml: String): Map<String, Service> {
        val root = Yaml().load<Any?>(yaml) as? Map<String, Any> ?: return emptyMap()
        val servicesRaw = root["services"] as? Map<String, Any> ?: return emptyMap()
        val out = mutableMapOf<String, Service>()
        servicesRaw.forEach { (name, raw) ->
            val m = raw as? Map<String, Any> ?: return@forEach
            val image = (m["image"] as? String) ?: return@forEach

            val ports = when (val p = m["ports"]) {
                is List<*> -> p.mapNotNull { parsePort(it) }
                else -> emptyList()
            }
            val env = when (val e = m["environment"]) {
                is Map<*, *> -> e.map { (k, v) -> "$k=$v" }
                is List<*> -> e.mapNotNull { it as? String }
                else -> emptyList()
            }
            val cmd = when (val c = m["command"]) {
                is String -> c.split(' ').filter { it.isNotBlank() }
                is List<*> -> c.mapNotNull { it.toString() }
                else -> null
            }
            val dependsOn = when (val d = m["depends_on"]) {
                is List<*> -> d.mapNotNull { it as? String }
                else -> emptyList()
            }
            out[name] = Service(
                image = image,
                ports = ports,
                env = env,
                cmd = cmd,
                restart = (m["restart"] as? String)?.takeIf { it == "always" || it == "unless-stopped" },
                tty = (m["tty"] as? Boolean) ?: true,
                volumes = (m["volumes"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                dependsOn = dependsOn,
            )
        }
        return out
    }

    /** "8080:80", "127.0.0.1:8080:80", "80" -> host-to-container pairs. */
    private fun parsePort(raw: Any?): Pair<Int, Int>? {
        val s = (raw as? String)?.trim() ?: return null
        val parts = s.split(':')
        return when (parts.size) {
            3 -> parts[1].toIntOrNull()?.let { h -> parts[2].toIntOrNull()?.let { c -> h to c } }
            2 -> parts[0].toIntOrNull()?.let { h -> parts[1].toIntOrNull()?.let { c -> h to c } }
            1 -> parts[0].toIntOrNull()?.let { c -> c to c }
            else -> null
        }
    }

    private fun topologicalOrder(services: Map<String, Service>): List<String> {
        val visited = mutableSetOf<String>()
        val order = mutableListOf<String>()
        fun visit(name: String, stack: Set<String>) {
            if (name in visited || name !in services) return
            require(name !in stack) { "Circular depends_on involving '$name'" }
            services.getValue(name).dependsOn.forEach { visit(it, stack + name) }
            visited += name
            order += name
        }
        services.keys.sorted().forEach { visit(it, emptySet()) }
        return order
    }
}
