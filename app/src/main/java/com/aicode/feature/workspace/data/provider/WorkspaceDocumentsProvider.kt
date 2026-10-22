package com.aicode.feature.workspace.data.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.aicode.R
import com.aicode.feature.agent.domain.container.ContainerInstaller
import java.io.File
import java.io.FileNotFoundException
import java.util.LinkedList

/**
 * SAF DocumentsProvider that exposes the app-private `filesDir` as a **single root** to the system "Files" app and to other apps'
 * SAF pickers, satisfying "externally visible". Only two subdirectories are exposed under the root:
 * - `projects`: workspaces (individual projects);
 * - `aicode`: AI config directory (skills/ and mcp.json; inside the container this is `/root/.aicode`).
 *
 * Other internal directories (rootfs, container binaries, databases, DataStore, etc.) are deliberately not listed under the root, to avoid external exposure / accidental deletion.
 *
 * Workspaces physically live on app-private ext4 (filesDir), in exchange for symlink support (npm/pnpm/yarn/git work with zero configuration);
 * emulated storage is naturally visible but the kernel rejects symlinks there. Visibility is restored here at the API layer with the official SAF API, decoupled from the physical location.
 *
 * docId uses the file's absolute path directly (consistent with the Termux implementation), simple and stable across processes.
 */
class WorkspaceDocumentsProvider : DocumentsProvider() {

    private companion object {
        const val ALL_MIME_TYPES = "*/*"
        const val MAX_SEARCH_RESULTS = 50

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES,
        )

        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
        )
    }

    /** The single exposed root: the app-private filesDir. */
    private fun baseDir(): File =
        ctx().filesDir

    /**
     * Whitelist of the only subdirectory names visible externally under the root: the workspace `projects` and the AI config `aicode` (inside the container /root/.aicode,
     * containing skills/ and mcp.json). Both are created on first access; other internal filesDir directories are not listed.
     */
    private fun exposedChildren(): List<File> {
        ContainerInstaller.extractDocs(ctx())
        return listOf(
            File(baseDir(), "projects").apply { mkdirs() },
            File(baseDir(), "aicode").apply { mkdirs() },
        )
    }

    /** On minSdk 26 the base class has no requireContext(), so obtain a non-null context here. */
    private fun ctx() =
        context ?: throw IllegalStateException("Provider context unavailable")

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        addRoot(result, baseDir(), ctx().getString(R.string.app_name))
        return result
    }

    /** Append one root to the root cursor: docId uses the directory's absolute path, consistent with [docIdForFile]. */
    private fun addRoot(result: MatrixCursor, dir: File, title: String) {
        result.newRow().apply {
            add(Root.COLUMN_ROOT_ID, docIdForFile(dir))
            add(Root.COLUMN_DOCUMENT_ID, docIdForFile(dir))
            add(Root.COLUMN_SUMMARY, null)
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_SUPPORTS_IS_CHILD,
            )
            add(Root.COLUMN_TITLE, title)
            add(Root.COLUMN_MIME_TYPES, ALL_MIME_TYPES)
            add(Root.COLUMN_AVAILABLE_BYTES, dir.freeSpace)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeFile(result, documentId, null)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = fileForDocId(parentDocumentId)
        // Root directory (filesDir itself) exposes only whitelisted subdirectories, hiding internal directories such as rootfs/databases;
        // other levels list all contents as usual.
        val children = if (parent.absolutePath == baseDir().absolutePath) {
            exposedChildren()
        } else {
            parent.listFiles()?.toList() ?: emptyList()
        }
        children.forEach { child -> includeFile(result, null, child) }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = fileForDocId(documentId)
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, accessMode)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = fileForDocId(parentDocumentId)
        var newFile = File(parent, displayName)
        var conflictId = 2
        while (newFile.exists()) {
            newFile = File(parent, "$displayName ($conflictId)")
            conflictId++
        }
        val ok = try {
            if (Document.MIME_TYPE_DIR == mimeType) newFile.mkdir() else newFile.createNewFile()
        } catch (e: Exception) {
            throw FileNotFoundException("Failed to create document: ${newFile.path}")
        }
        if (!ok) throw FileNotFoundException("Failed to create document: ${newFile.path}")
        return docIdForFile(newFile)
    }

    override fun deleteDocument(documentId: String) {
        val file = fileForDocId(documentId)
        if (!file.deleteRecursively()) {
            throw FileNotFoundException("Failed to delete document: $documentId")
        }
    }

    override fun getDocumentType(documentId: String): String =
        mimeType(fileForDocId(documentId))

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?,
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val root = fileForDocId(rootId)
        val rootCanonical = root.canonicalPath
        // When searching from the root (filesDir), descend only into whitelisted subdirectories to avoid scanning internal directories such as rootfs; other levels behave as usual.
        val seeds = if (root.absolutePath == baseDir().absolutePath) exposedChildren() else listOf(root)
        val pending = LinkedList<File>().apply { addAll(seeds) }
        val needle = query.lowercase()

        while (pending.isNotEmpty() && result.count < MAX_SEARCH_RESULTS) {
            val file = pending.removeFirst()
            // Search only within the root directory, to avoid a symlink pointing outside causing a scan of the entire disk
            val insideRoot = try {
                file.canonicalPath.startsWith(rootCanonical)
            } catch (e: Exception) {
                false
            }
            if (!insideRoot) continue
            if (file.isDirectory) {
                file.listFiles()?.let { pending.addAll(it) }
            } else if (file.name.lowercase().contains(needle)) {
                includeFile(result, null, file)
            }
        }
        return result
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId.startsWith(parentDocumentId)

    private fun docIdForFile(file: File): String = file.absolutePath

    private fun fileForDocId(docId: String): File {
        val f = File(docId)
        if (!f.exists()) throw FileNotFoundException("${f.absolutePath} not found")
        return f
    }

    private fun mimeType(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        }
        return "application/octet-stream"
    }

    private fun includeFile(result: MatrixCursor, docId: String?, file: File?) {
        val resolvedFile = file ?: docId?.let { fileForDocId(it) }
        ?: throw FileNotFoundException("docId and file both null")
        val resolvedDocId = docId ?: docIdForFile(resolvedFile)

        var flags = 0
        if (resolvedFile.isDirectory) {
            if (resolvedFile.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (resolvedFile.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (resolvedFile.parentFile?.canWrite() == true) flags = flags or Document.FLAG_SUPPORTS_DELETE

        val mime = mimeType(resolvedFile)
        if (mime.startsWith("image/")) flags = flags or Document.FLAG_SUPPORTS_THUMBNAIL

        result.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, resolvedDocId)
            add(Document.COLUMN_DISPLAY_NAME, resolvedFile.name)
            add(Document.COLUMN_SIZE, resolvedFile.length())
            add(Document.COLUMN_MIME_TYPE, mime)
            add(Document.COLUMN_LAST_MODIFIED, resolvedFile.lastModified())
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_ICON, R.mipmap.ic_launcher)
        }
    }
}
