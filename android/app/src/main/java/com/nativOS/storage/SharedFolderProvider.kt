package com.nativOS.storage

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.nativOS.runtime.RootShell
import java.io.File
import java.io.FileNotFoundException

/** Exposes Linux /root/Shared in Android's system file picker. */
class SharedFolderProvider : DocumentsProvider() {
    companion object {
        private const val ROOT_ID = "nativos_shared"
        private const val ROOT_DOCUMENT_ID = "root"
    }

    private val sharedDir: File
        get() = File(requireNotNull(context).filesDir, "shared").apply { mkdirs() }

    override fun onCreate(): Boolean {
        sharedDir
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES
        )
        return MatrixCursor(columns).apply {
            newRow().apply {
                add(Root.COLUMN_ROOT_ID, ROOT_ID)
                add(Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
                add(Root.COLUMN_TITLE, "NativOS Shared")
                add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY)
                add(Root.COLUMN_MIME_TYPES, "*/*")
                add(Root.COLUMN_AVAILABLE_BYTES, sharedDir.usableSpace)
            }
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        documentCursor(projection).apply { includeFile(resolve(documentId), documentId) }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        makeLinuxFilesWritable()
        val parent = resolve(parentDocumentId)
        return documentCursor(projection).apply {
            parent.listFiles()?.sortedBy { it.name.lowercase() }?.forEach {
                includeFile(it, idFor(it))
            }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        if (mode != "r") makeLinuxFilesWritable()
        return ParcelFileDescriptor.open(resolve(documentId), ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        makeLinuxFilesWritable()
        val parent = resolve(parentDocumentId)
        val safeName = File(displayName).name
        var target = File(parent, safeName)
        var suffix = 1
        while (target.exists()) {
            val dot = safeName.lastIndexOf('.')
            val stem = if (dot > 0) safeName.substring(0, dot) else safeName
            val extension = if (dot > 0) safeName.substring(dot) else ""
            target = File(parent, "$stem ($suffix)$extension")
            suffix++
        }
        val created = if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
        if (!created) throw FileNotFoundException("Could not create $displayName")
        return idFor(target)
    }

    override fun deleteDocument(documentId: String) {
        makeLinuxFilesWritable()
        val file = resolve(documentId)
        if (file == sharedDir || !file.deleteRecursively()) {
            throw FileNotFoundException("Could not delete $documentId")
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        makeLinuxFilesWritable()
        val file = resolve(documentId)
        val target = File(file.parentFile, File(displayName).name)
        if (file == sharedDir || !file.renameTo(target)) {
            throw FileNotFoundException("Could not rename $documentId")
        }
        return idFor(target)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = resolve(parentDocumentId).canonicalFile
        val child = resolve(documentId).canonicalFile
        return child.path.startsWith(parent.path + File.separator)
    }

    private fun makeLinuxFilesWritable() {
        runCatching { RootShell(requireNotNull(context)).exec("chmod -R 0777 ${sharedDir.absolutePath}") }
    }

    private fun resolve(documentId: String): File {
        if (documentId == ROOT_DOCUMENT_ID) return sharedDir
        if (!documentId.startsWith("$ROOT_DOCUMENT_ID/")) throw FileNotFoundException(documentId)
        val file = File(sharedDir, documentId.removePrefix("$ROOT_DOCUMENT_ID/")).canonicalFile
        val root = sharedDir.canonicalFile
        if (!file.path.startsWith(root.path + File.separator)) throw FileNotFoundException(documentId)
        return file
    }

    private fun idFor(file: File): String = if (file.canonicalFile == sharedDir.canonicalFile) {
        ROOT_DOCUMENT_ID
    } else {
        "$ROOT_DOCUMENT_ID/${file.canonicalFile.relativeTo(sharedDir.canonicalFile).path}"
    }

    private fun documentCursor(projection: Array<out String>?): MatrixCursor = MatrixCursor(
        projection ?: arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
        )
    )

    private fun MatrixCursor.includeFile(file: File, documentId: String) {
        val mime = if (file.isDirectory) Document.MIME_TYPE_DIR else
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
                ?: "application/octet-stream"
        val flags = if (file.isDirectory) {
            Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        } else {
            Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, if (documentId == ROOT_DOCUMENT_ID) "NativOS Shared" else file.name)
            add(Document.COLUMN_MIME_TYPE, mime)
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        }
    }
}
