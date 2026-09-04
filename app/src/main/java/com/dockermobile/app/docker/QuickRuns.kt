package com.dockermobile.app.docker

/**
 * One-tap deploy presets matching the classic Docker getting-started flows —
 * the Android equivalent of `docker run -d -p 8080:80 nginx`.
 */
data class QuickRun(
    val label: String,
    val image: String,
    val ports: List<Pair<Int, Int>> = emptyList(),
    val env: List<String> = emptyList(),
    val cmd: List<String>? = null,
    val description: String,
)

object QuickRuns {

    val all: List<QuickRun> = listOf(
        QuickRun(
            label = "nginx", image = "nginx:latest",
            ports = listOf(8080 to 80),
            description = "Web server — open http://127.0.0.1:8080",
        ),
        QuickRun(
            label = "redis", image = "redis:8",
            ports = listOf(6379 to 6379),
            description = "In-memory key/value store",
        ),
        QuickRun(
            label = "postgres", image = "postgres:17",
            ports = listOf(5432 to 5432),
            env = listOf("POSTGRES_PASSWORD=postgres"),
            description = "SQL database (password: postgres)",
        ),
        QuickRun(
            label = "n8n", image = "docker.n8n.io/n8nio/n8n:latest",
            ports = listOf(5678 to 5678),
            env = listOf("N8N_SECURE_COOKIE=false"),
            description = "Workflow automation UI",
        ),
        QuickRun(
            label = "busybox", image = "busybox:latest",
            cmd = listOf("sleep", "infinity"),
            description = "Tiny shell sandbox for docker exec",
        ),
        QuickRun(
            label = "alpine", image = "alpine:latest",
            cmd = listOf("sleep", "infinity"),
            description = "Minimal shell sandbox for docker exec",
        ),
        QuickRun(
            label = "traefik", image = "traefik:v3.1",
            ports = listOf(8081 to 80),
            cmd = listOf("--api.dashboard=true", "--providers.docker=false"),
            description = "Reverse proxy with dashboard",
        ),
    )
}
