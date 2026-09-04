package com.dockermobile.app.store

import android.app.Application
import java.io.File

/**
 * Stores compose projects (stacks) under <filesDir>/stacks/<project>/compose.yaml.
 * Bind-mount style volumes are rooted per service under the same folder so
 * nothing escapes the app sandbox.
 */
class StackStore(private val app: Application) {

    private val root: File get() = File(app.filesDir, "stacks").apply { mkdirs() }

    fun list(): List<String> =
        root.listFiles { f -> f.isDirectory }
            ?.filter { File(it, "compose.yaml").exists() }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    fun composeFile(project: String): File = File(projectDir(project), "compose.yaml")

    fun projectDir(project: String): File =
        File(root, sanitize(project)).apply { mkdirs() }

    fun volumeDir(project: String, service: String): File =
        File(projectDir(project), "volumes/$service").apply { mkdirs() }

    fun read(project: String): String? =
        composeFile(project).takeIf { it.exists() }?.readText()

    fun write(project: String, yaml: String) {
        composeFile(project).writeText(yaml)
    }

    fun delete(project: String) {
        composeFile(project).delete()
        projectDir(project).deleteRecursively()
    }

    fun exists(project: String): Boolean = composeFile(project).exists()

    private fun sanitize(name: String): String =
        name.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "-").ifBlank { "stack" }
}
