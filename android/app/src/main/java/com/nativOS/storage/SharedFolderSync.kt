package com.nativOS.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import com.nativOS.runtime.RootShell
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps Internal storage/NativOS and Linux /root/Shared in sync. */
object SharedFolderSync {
    private const val TAG = "NativOS.SharedSync"
    private const val PREFS = "nativos_shared_sync"
    private const val STATE = "file_state"
    private val started = AtomicBoolean(false)
    private val syncing = AtomicBoolean(false)

    private data class FileState(val size: Long, val modified: Long)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "nativOS-shared-sync").apply { isDaemon = true }
        }
        executor.scheduleWithFixedDelay(
            { syncNow(appContext) },
            0,
            2,
            TimeUnit.SECONDS
        )
    }

    private fun syncNow(context: Context) {
        if (!syncing.compareAndSet(false, true)) return
        try {
            if (!ensureStorageAccess(context)) return

            val privateDir = File(context.filesDir, "shared").apply { mkdirs() }
            val publicDir = File(Environment.getExternalStorageDirectory(), "NativOS").apply { mkdirs() }
            if (!publicDir.isDirectory) return

            // Linux applications normally create 0644 files. Make existing
            // entries writable so Android can safely update them as well.
            runCatching {
                RootShell(context).exec("chmod -R 0777 ${privateDir.absolutePath}")
            }

            reconcile(context, publicDir, privateDir)
        } catch (error: Exception) {
            Log.w(TAG, "Shared-folder sync failed", error)
        } finally {
            syncing.set(false)
        }
    }

    private fun ensureStorageAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        if (Environment.isExternalStorageManager()) return true

        runCatching {
            RootShell(context).exec(
                "appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow"
            )
        }.onFailure { Log.w(TAG, "Could not grant all-files access", it) }

        val granted = Environment.isExternalStorageManager()
        if (!granted) Log.w(TAG, "All-files access is not granted")
        return granted
    }

    private fun reconcile(context: Context, publicRoot: File, privateRoot: File) {
        val previous = loadState(context)
        val publicFiles = scan(publicRoot)
        val privateFiles = scan(privateRoot)

        (publicFiles.keys + privateFiles.keys).forEach { relative ->
            val publicState = publicFiles[relative]
            val privateState = privateFiles[relative]
            val oldState = previous[relative]
            when {
                publicState != null && privateState != null -> {
                    if (publicState != privateState) {
                        if (publicState.modified >= privateState.modified) {
                            copyFile(File(publicRoot, relative), File(privateRoot, relative), relative)
                        } else {
                            copyFile(File(privateRoot, relative), File(publicRoot, relative), relative)
                        }
                    }
                }
                publicState != null -> {
                    // If this file existed after the last successful sync and
                    // is unchanged, its Linux copy was deliberately deleted.
                    if (oldState == publicState) {
                        deleteFile(File(publicRoot, relative), relative)
                    } else {
                        copyFile(File(publicRoot, relative), File(privateRoot, relative), relative)
                    }
                }
                privateState != null -> {
                    // The matching Android copy was deliberately deleted.
                    if (oldState == privateState) {
                        deleteFile(File(privateRoot, relative), relative)
                    } else {
                        copyFile(File(privateRoot, relative), File(publicRoot, relative), relative)
                    }
                }
            }
        }

        removeEmptyDirectories(publicRoot)
        removeEmptyDirectories(privateRoot)
        saveState(context, scan(publicRoot))
    }

    private fun scan(root: File): Map<String, FileState> {
        val result = linkedMapOf<String, FileState>()
        val canonicalRoot = root.canonicalFile
        root.walkTopDown().forEach { file ->
            if (file == root || !file.isFile || Files.isSymbolicLink(file.toPath()) ||
                file.name.endsWith(".nativos-sync")) return@forEach
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@forEach
            if (!canonical.path.startsWith(canonicalRoot.path + File.separator)) return@forEach
            result[file.relativeTo(root).path] = FileState(file.length(), file.lastModified())
        }
        return result
    }

    private fun copyFile(source: File, target: File, relative: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.nativos-sync")
        source.inputStream().buffered().use { input ->
            temporary.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        temporary.setLastModified(source.lastModified())
        try {
            Files.move(
                temporary.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        target.setLastModified(source.lastModified())
        Log.i(TAG, "Synced $relative")
    }

    private fun deleteFile(file: File, relative: String) {
        if (file.delete()) Log.i(TAG, "Synced deletion of $relative")
    }

    private fun removeEmptyDirectories(root: File) {
        root.walkBottomUp().filter { it != root && it.isDirectory }.forEach { directory ->
            if (directory.list()?.isEmpty() == true) directory.delete()
        }
    }

    private fun loadState(context: Context): Map<String, FileState> {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(STATE, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(encoded)
            json.keys().asSequence().associateWith { key ->
                val item = json.getJSONObject(key)
                FileState(item.getLong("size"), item.getLong("modified"))
            }
        }.getOrDefault(emptyMap())
    }

    private fun saveState(context: Context, state: Map<String, FileState>) {
        val json = JSONObject()
        state.forEach { (path, item) ->
            json.put(path, JSONObject().put("size", item.size).put("modified", item.modified))
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encoded = json.toString()
        if (prefs.getString(STATE, null) != encoded) prefs.edit().putString(STATE, encoded).apply()
    }
}
