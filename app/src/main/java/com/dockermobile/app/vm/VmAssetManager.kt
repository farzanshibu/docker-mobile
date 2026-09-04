package com.dockermobile.app.vm

import android.app.Application
import android.net.Uri
import com.dockermobile.app.core.AppSettings
import com.dockermobile.app.core.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.utils.IOUtils
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** The three boot files QEMU needs plus the emulator binary itself. */
enum class AssetKind(val fileName: String, val humanName: String, val typicalSize: String) {
    KERNEL("vmlinuz-virt", "Alpine kernel", "~13 MB"),
    INITRAMFS("initramfs-virt", "Alpine initramfs", "~47 MB"),
    ROOTFS("rootfs.img", "Root filesystem (Docker preinstalled)", "~1.5 GB"),
}

data class AssetReport(
    val kernel: File?,
    val initramfs: File?,
    val rootfs: File?,
    val qemuBinary: File?,
) {
    val allPresent: Boolean
        get() = kernel != null && initramfs != null && rootfs != null && qemuBinary != null

    fun statusOf(kind: AssetKind): Boolean = when (kind) {
        AssetKind.KERNEL -> kernel != null
        AssetKind.INITRAMFS -> initramfs != null
        AssetKind.ROOTFS -> rootfs != null
    }
}

/**
 * Manages the VM disk/kernel assets under <filesDir>/vm and the QEMU binary
 * under <nativeLibraryDir>. Assets are imported via SAF or downloaded from a
 * mirror URL; a single-file .tar.gz bundle is also accepted.
 */
class VmAssetManager(
    private val app: Application,
    private val settings: SettingsRepository,
) {
    val vmDir: File get() = File(app.filesDir, "vm").apply { mkdirs() }

    val kernelFile: File get() = File(vmDir, AssetKind.KERNEL.fileName)
    val initramfsFile: File get() = File(vmDir, AssetKind.INITRAMFS.fileName)
    val rootfsFile: File get() = File(vmDir, AssetKind.ROOTFS.fileName)

    /** QEMU is packaged as a fake shared library so the OS extracts it executable. */
    val qemuBinary: File?
        get() {
            val candidate = File(app.applicationInfo.nativeLibraryDir, QEMU_LIB_NAME)
            return if (candidate.exists() && candidate.canExecute()) candidate else null
        }

    fun report(): AssetReport = AssetReport(
        kernel = kernelFile.takeIf { it.length() > 0 },
        initramfs = initramfsFile.takeIf { it.length() > 0 },
        rootfs = rootfsFile.takeIf { it.length() > 0 },
        qemuBinary = qemuBinary,
    )

    suspend fun snapshotSettings(): AppSettings = settings.snapshot()

    // ---------------------------------------------------------------- import

    /**
     * Imports one file picked via SAF. Understands the plain names, .gz
     * compressed variants, and a full tar.gz bundle containing all assets.
     */
    suspend fun import(uri: Uri, kind: AssetKind?, onProgress: (String) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            val displayName = queryDisplayName(uri) ?: "import.bin"
            val lower = displayName.lowercase()

            if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
                importBundle(uri, onProgress)
            } else {
                val target = when {
                    lower.contains("initramfs") -> initramfsFile
                    lower.contains("vmlinuz") || lower.contains("kernel") -> kernelFile
                    lower.contains("rootfs") || lower.endsWith(".img") || lower.endsWith(".qcow2") -> rootfsFile
                    kind != null -> when (kind) {
                        AssetKind.KERNEL -> kernelFile
                        AssetKind.INITRAMFS -> initramfsFile
                        AssetKind.ROOTFS -> rootfsFile
                    }
                    else -> throw IllegalArgumentException("Cannot infer asset type of $displayName")
                }
                copyUri(uri, target, onProgress)
                if (target == rootfsFile && lower.endsWith(".gz")) {
                    gunzipInPlace(target)
                }
                "Imported ${target.name}"
            }
        }

    private suspend fun importBundle(uri: Uri, onProgress: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            onProgress("Extracting bundle…")
            val tmp = File(app.cacheDir, "bundle.tar.gz")
            copyUri(uri, tmp, onProgress)
            TarArchiveInputStream(GZIPInputStream4(BufferedInputStream(FileInputStream(tmp)))).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    val name = File(entry.name).name.lowercase()
                    val out = when {
                        name.startsWith("vmlinuz") || name.startsWith("kernel") -> kernelFile
                        name.startsWith("initramfs") -> initramfsFile
                        name.startsWith("rootfs") -> rootfsFile
                        else -> null
                    }
                    if (out != null) {
                        onProgress("Extracting ${out.name}…")
                        FileOutputStream(out).use { IOUtils.copy(tar, it) }
                    }
                    entry = tar.nextTarEntry
                }
            }
            tmp.delete()
            "Bundle imported: kernel + initramfs + rootfs"
        }

    // -------------------------------------------------------------- download

    /**
     * Downloads kernel/initramfs/rootfs from [mirrorBase] (a directory-style
     * URL). Missing entries are skipped with a note; rootfs may be published as
     * rootfs.img.gz and is decompressed automatically.
     */
    suspend fun downloadAll(
        mirrorBase: String,
        onProgress: (String, Float?) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val base = mirrorBase.trimEnd('/')
        require(base.startsWith("http")) { "Mirror URL must start with http(s)://" }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val plan = listOf(
            Triple(AssetKind.KERNEL, "vmlinuz-virt", false),
            Triple(AssetKind.INITRAMFS, "initramfs-virt", false),
            Triple(AssetKind.ROOTFS, "rootfs.img.gz", true),
            Triple(AssetKind.ROOTFS, "rootfs.img", false),
        )

        val notes = mutableListOf<String>()
        for ((kind, remoteName, decompress) in plan) {
            val already = when (kind) {
                AssetKind.ROOTFS -> rootfsFile.length() > 0
                else -> false
            }
            if (already) continue
            val url = "$base/$remoteName"
            try {
                onProgress("Downloading $remoteName…", null)
                val target = targetFor(kind, decompress)
                download(client, url, target, onProgress)
                // gunzip reads the .gz that was just fetched — never rootfsFile,
                // which is the output and does not exist yet.
                if (decompress) gunzipInPlace(target)
                notes += "$remoteName ✓"
                if (kind == AssetKind.ROOTFS) break // one of the two variants sufficed
            } catch (e: Exception) {
                notes += "$remoteName ✗ (${e.message?.take(80)})"
            }
        }
        onProgress("Finished.", null)
        "Download results: " + notes.joinToString("; ")
    }

    private fun targetFor(kind: AssetKind, compressed: Boolean): File = when (kind) {
        AssetKind.KERNEL -> kernelFile
        AssetKind.INITRAMFS -> initramfsFile
        AssetKind.ROOTFS -> if (compressed) File(vmDir, "rootfs.img.gz") else rootfsFile
    }

    private fun download(client: OkHttpClient, url: String, target: File, onProgress: (String, Float?) -> Unit) {
        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        resp.use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code} for $url")
            val body = r.body ?: throw java.io.IOException("Empty body")
            val total = body.contentLength()
            val tmp = File(target.absolutePath + ".part")
            body.byteStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        output.write(buf, 0, n)
                        copied += n
                        if (total > 0) onProgress("Downloading ${target.name}…", copied.toFloat() / total)
                    }
                }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    // ----------------------------------------------------------------- utils

    private fun copyUri(uri: Uri, target: File, onProgress: (String) -> Unit) {
        onProgress("Copying ${target.name}…")
        app.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output, 256 * 1024) }
        } ?: throw java.io.IOException("Cannot open $uri")
    }

    private fun gunzipInPlace(file: File) {
        val outFile = File(vmDir, AssetKind.ROOTFS.fileName)
        FileInputStream(file).use { fin ->
            GZIPInputStream4(BufferedInputStream(fin)).use { gz ->
                FileOutputStream(outFile).use { fo -> IOUtils.copy(gz, fo) }
            }
        }
        file.delete()
    }

    /** Explicit 4-arg-free GZIP stream (avoids pulling another dependency). */
    private class GZIPInputStream4(input: java.io.InputStream) : java.util.zip.GZIPInputStream(input, 64 * 1024)

    private fun queryDisplayName(uri: Uri): String? =
        app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }

    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").run {
        FileInputStream(file).use { fin ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = fin.read(buf)
                if (n == -1) break
                update(buf, 0, n)
            }
        }
        digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val QEMU_LIB_NAME = "libqemu_system_aarch64.so"
    }
}
