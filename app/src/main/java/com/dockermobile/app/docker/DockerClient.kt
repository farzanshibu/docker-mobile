package com.dockermobile.app.docker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class DockerException(message: String, val code: Int = -1) : IOException(message)

/**
 * Minimal but real Docker Engine API client (pure Kotlin + OkHttp).
 *
 * Speaks HTTP to a daemon reachable over TCP. In embedded-VM mode the daemon
 * lives inside the QEMU guest and is exposed on 127.0.0.1:<daemonPort> via a
 * QEMU user-mode hostfwd rule; in remote mode any tcp:// endpoint works.
 *
 * Deliberately uses JsonElement parsing (no generated models) so it tolerates
 * API version drift between daemon releases.
 */
class DockerClient(
    private val baseUrl: String,
    private val api: OkHttpClient = defaultApi,
    private val streams: OkHttpClient = defaultStreams,
) {
    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private val defaultApi = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        // Streams (logs, events, pull, exec attach) must never time out while idle.
        private val defaultStreams = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val json = Json { ignoreUnknownKeys = true }

        fun normalizeBaseUrl(raw: String): String {
            val url = raw.trim().trimEnd('/')
            return when {
                url.isEmpty() -> url
                url.startsWith("http://") || url.startsWith("https://") -> url
                else -> "http://$url"
            }
        }
    }

    // ------------------------------------------------------------------ core

    private suspend fun <T> call(
        path: String,
        method: String = "GET",
        body: JsonElement? = null,
        query: Map<String, String> = emptyMap(),
        useStreams: Boolean = false,
        ok: suspend (Response) -> T,
    ): T {
        val client = if (useStreams) streams else api
        val sb = StringBuilder(baseUrl).append(path)
        if (query.isNotEmpty()) {
            sb.append('?')
            sb.append(query.entries.joinToString("&") { (k, v) ->
                val ek = java.net.URLEncoder.encode(k, "UTF-8")
                val ev = java.net.URLEncoder.encode(v, "UTF-8")
                "$ek=$ev"
            })
        }
        val request = Request.Builder().url(sb.toString()).method(method, body?.let {
            it.toString().toRequestBody(JSON_MEDIA)
        } ?: (if (method == "POST") "".toRequestBody(null) else null)).build()

        val response = withIO { client.newCall(request).execute() }
        response.use { resp ->
            if (!resp.isSuccessful) {
                val err = runCatching { resp.body?.string().orEmpty().take(400) }.getOrDefault("")
                throw DockerException("HTTP ${resp.code} $path — ${err.ifBlank { resp.message }}", resp.code)
            }
            return ok(resp)
        }
    }

    private suspend fun <T> withIO(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    private suspend fun jsonBody(resp: Response): JsonElement =
        withIO { json.parseToJsonElement(resp.body?.string().orEmpty().ifBlank { "{}" }) }

    // ---------------------------------------------------------------- system

    suspend fun ping(): Boolean = try {
        call("/_ping") { true }
    } catch (e: DockerException) {
        if (e.code in 400..599) throw e else false
    }

    suspend fun version(): String = call("/version") { resp ->
        withIO {
            val o = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            o["Version"]?.jsonPrimitive?.content ?: "?"
        }
    }

    // ------------------------------------------------------------- containers

    suspend fun listContainers(all: Boolean): JsonArray =
        call("/containers/json", query = mapOf("all" to all.toString())) { jsonBody(it).jsonArray }

    suspend fun inspectContainer(id: String): JsonObject =
        call("/containers/${enc(id)}/json") { jsonBody(it).jsonObject }

    suspend fun createContainer(spec: JsonObject, name: String?): String {
        val query = if (name.isNullOrBlank()) emptyMap() else mapOf("name" to name)
        return call("/containers/create", "POST", spec, query) { resp ->
            withIO {
                json.parseToJsonElement(resp.body?.string().orEmpty())
                    .jsonObject["Id"]?.jsonPrimitive?.content
                    ?: throw DockerException("Create response missing Id")
            }
        }
    }

    suspend fun startContainer(id: String) = call("/containers/${enc(id)}/start", "POST") {}
    suspend fun stopContainer(id: String, timeoutSec: Int = 10) =
        call("/containers/${enc(id)}/stop", "POST", query = mapOf("t" to timeoutSec.toString())) {}

    suspend fun restartContainer(id: String) = call("/containers/${enc(id)}/restart", "POST") {}
    suspend fun killContainer(id: String) = call("/containers/${enc(id)}/kill", "POST") {}

    suspend fun removeContainer(id: String, force: Boolean = false, removeVolumes: Boolean = true) =
        call(
            "/containers/${enc(id)}", "DELETE",
            query = mapOf(
                "force" to force.toString(),
                "v" to removeVolumes.toString(),
            ),
        ) {}

    suspend fun containerStats(id: String): ContainerStats =
        call("/containers/${enc(id)}/stats", query = mapOf("stream" to "false")) { resp ->
            withIO { parseStats(json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject) }
        }

    // ----------------------------------------------------------------- images

    suspend fun listImages(): JsonArray = call("/images/json") { jsonBody(it).jsonArray }

    suspend fun imageExists(ref: String): Boolean = try {
        call("/images/${enc(ref)}/json") { true }
    } catch (e: DockerException) {
        if (e.code == 404) false else throw e
    }

    suspend fun removeImage(id: String, force: Boolean = true) =
        call("/images/${enc(id)}", "DELETE", query = mapOf("force" to force.toString())) {}

    /** Streaming image pull; emits progress events until the daemon finishes. */
    fun pull(ref: String): Flow<PullEvent> = flow {
        val (repoPart, tag, digest) = splitRef(ref)
        val query = buildMap {
            put("fromImage", repoPart)
            if (tag != null) put("tag", tag)
            if (digest != null) put("digest", digest)
        }
        call("/images/create", "POST", useStreams = true, query = query) { resp ->
            val source = resp.body?.source() ?: throw DockerException("Empty pull response")
            while (true) {
                val line = withIO { source.readUtf8Line() } ?: break
                if (line.isBlank()) continue
                val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                val status = obj["status"]?.jsonPrimitive?.content ?: continue
                val layerId = obj["id"]?.jsonPrimitive?.content
                val pd = obj["progressDetail"] as? JsonObject
                val current = pd?.get("current")?.jsonPrimitive?.content?.toLongOrNull()
                val total = pd?.get("total")?.jsonPrimitive?.content?.toLongOrNull()
                val errText = obj["error"]?.jsonPrimitive?.content
                if (errText != null) throw DockerException("Pull failed: $errText")
                emit(PullEvent(status, layerId, current, total))
            }
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------- logs

    /** Follows container logs. Emits plain-text lines regardless of Tty setting. */
    fun followLogs(id: String, tail: Int = 300): Flow<String> = flow {
        call(
            "/containers/${enc(id)}/logs", useStreams = true,
            query = mapOf(
                "follow" to "true", "stdout" to "true", "stderr" to "true",
                "tail" to tail.toString(),
            ),
        ) { resp ->
            val source = resp.body?.source() ?: throw DockerException("Empty log stream")
            val demuxer = StreamDemuxer()
            while (true) {
                val hasData = withIO { source.request(1) }
                if (!hasData) break
                val available = minOf(source.buffer.size, 8192L)
                val chunk = withIO { source.readByteArray(available) }
                demuxer.feed(chunk).forEach { emit(it) }
            }
            demuxer.flush().forEach { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    // ----------------------------------------------------------------- events

    /** Daemon event stream; each emission is a raw event JSON object. */
    fun events(): Flow<JsonObject> = flow {
        call("/events", useStreams = true, query = mapOf("filters" to """{"type":["container"]}""")) { resp ->
            val source = resp.body?.source() ?: throw DockerException("Empty event stream")
            while (true) {
                val line = withIO { source.readUtf8Line() } ?: break
                if (line.isBlank()) continue
                val obj = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                emit(obj)
            }
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------- exec

    suspend fun execCreate(containerId: String, cmd: List<String>, workdir: String? = null): String {
        val spec = buildJsonObject {
            put("AttachStdin", true)
            put("AttachStdout", true)
            put("AttachStderr", true)
            put("Tty", true)
            put("OpenStdin", true)
            putJsonArray("Cmd") { cmd.forEach { add(JsonPrimitive(it)) } }
            if (!workdir.isNullOrBlank()) put("WorkingDir", workdir)
        }
        return call("/containers/${enc(containerId)}/exec", "POST", spec) { resp ->
            withIO {
                json.parseToJsonElement(resp.body?.string().orEmpty())
                    .jsonObject["Id"]?.jsonPrimitive?.content
                    ?: throw DockerException("Exec create response missing Id")
            }
        }
    }

    suspend fun resizeExec(execId: String, cols: Int, rows: Int) {
        runCatching {
            call(
                "/exec/${enc(execId)}/resize", "POST",
                query = mapOf("h" to rows.toString(), "w" to cols.toString()),
            ) {}
        }
    }

    // ------------------------------------------------------------------ specs

    /** Builds a /containers/create request body (containers get a TTY for clean streams). */
    fun buildRunSpec(
        image: String,
        name: String? = null,
        portPairs: List<Pair<Int, Int>> = emptyList(),   // host to container
        env: List<String> = emptyList(),
        cmd: List<String>? = null,
        binds: List<String> = emptyList(),
        restart: String? = null,
        labels: Map<String, String> = emptyMap(),
        tty: Boolean = true,
    ): JsonObject = buildJsonObject {
        put("Image", image)
        put("Tty", tty)
        put("OpenStdin", tty)
        put("AttachStdout", false)
        put("AttachStderr", false)
        if (cmd != null && cmd.isNotEmpty()) putJsonArray("Cmd") { cmd.forEach { add(JsonPrimitive(it)) } }
        if (env.isNotEmpty()) putJsonArray("Env") { env.forEach { add(JsonPrimitive(it)) } }
        if (labels.isNotEmpty()) putJsonObject("Labels") { labels.forEach { (k, v) -> put(k, v) } }
        putJsonObject("HostConfig") {
            if (portPairs.isNotEmpty()) {
                putJsonObject("PortBindings") {
                    portPairs.forEach { (host, cont) ->
                        put("$cont/tcp", JsonArray(listOf(buildJsonObject {
                            put("HostIp", "")
                            put("HostPort", host.toString())
                        })))
                    }
                }
            }
            if (binds.isNotEmpty()) putJsonArray("Binds") { binds.forEach { add(JsonPrimitive(it)) } }
            if (restart != null) putJsonObject("RestartPolicy") { put("Name", restart) }
        }
    }

    // ------------------------------------------------------------------ utils

    private fun enc(raw: String): String = java.net.URLEncoder.encode(raw, "UTF-8")

    internal fun parseStats(o: JsonObject): ContainerStats {
        fun jLong(obj: JsonObject?, key: String): Long =
            obj?.get(key)?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

        val cpu = o["cpu_stats"] as? JsonObject
        val preCpu = o["precpu_stats"] as? JsonObject
        val mem = o["memory_stats"] as? JsonObject
        val networks = o["networks"] as? JsonObject

        val cpuTotal = jLong(cpu, "total_usage")
        val preCpuTotal = jLong(preCpu, "total_usage")
        val systemTotal = jLong(cpu, "system_cpu_usage")
        val preSystemTotal = jLong(preCpu, "system_cpu_usage")
        val onlineCpus = cpu?.get("online_cpus")?.jsonPrimitive?.content?.toIntOrNull() ?: 1

        val cpuDelta = cpuTotal - preCpuTotal
        val sysDelta = systemTotal - preSystemTotal
        val cpuPct = if (sysDelta > 0 && cpuDelta > 0) {
            (cpuDelta.toDouble() / sysDelta.toDouble()) * onlineCpus * 100.0
        } else 0.0

        val cache = (mem?.get("stats") as? JsonObject)?.get("cache")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val memUsed = (jLong(mem, "usage") - cache).coerceAtLeast(0)
        val memLimit = jLong(mem, "limit")
        val memPct = if (memLimit > 0) memUsed * 100.0 / memLimit else 0.0

        var rx = 0L; var tx = 0L
        networks?.forEach { (_, v) ->
            if (v is JsonObject) {
                rx += jLong(v, "rx_bytes"); tx += jLong(v, "tx_bytes")
            }
        }

        return ContainerStats(cpuPct, memUsed, memLimit, memPct, rx, tx)
    }
}

/** Splits "registry:5000/name:tag", "name:tag", "name@sha256:..." into parts. */
internal fun splitRef(ref: String): Triple<String, String?, String?> {
    val r = ref.trim()
    val digestIdx = r.indexOf('@')
    if (digestIdx >= 0) {
        val digest = r.substring(digestIdx + 1)
        val namePart = r.substring(0, digestIdx)
        val tagIdx = namePart.lastIndexOf(':')
        return if (tagIdx > namePart.lastIndexOf('/')) {
            Triple(namePart.substring(0, tagIdx), null, digest)
        } else {
            Triple(namePart, null, digest)
        }
    }
    val tagIdx = r.lastIndexOf(':')
    return if (tagIdx > r.lastIndexOf('/')) {
        Triple(r.substring(0, tagIdx), r.substring(tagIdx + 1), null)
    } else {
        Triple(r, "latest", null)
    }
}
