package com.aicode.feature.workspace.domain

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract

/**
 * Resolves a SAF tree URI (returned by the system directory picker) to a real host path.
 *
 * Only supports explicit docId forms from the Android external storage provider:
 * - `primary:<relative path>` → `<primary storage root>/<relative path>` (the primary storage root comes from `Environment.getExternalStorageDirectory()`; under multi-user / work profiles it is not fixed to `/storage/emulated/0`)
 * - `<volume UUID>:<relative path>` → `/storage/<volume UUID>/<relative path>` (e.g. `XXXX-XXXX:Download`)
 *
 * Any other provider-defined docId returns null, to avoid treating a provider-defined opaque ID as a host path.
 */
object UriPathResolver {

    /** tree URI → real host directory path; returns null if it cannot be resolved. */
    fun toFilePath(context: Context, uri: Uri): String? {
        if (!DocumentsContract.isTreeUri(uri)) return null
        if (uri.authority != "com.android.externalstorage.documents") return null
        val primaryRoot = runCatching { Environment.getExternalStorageDirectory().absolutePath }
            .getOrDefault("/storage/emulated/0")
        return resolveDocId(DocumentsContract.getTreeDocumentId(uri), primaryRoot)
    }

    /** Pure function: externalstorage docId → real host directory path; returns null if it cannot be resolved.
     *  [primaryRoot] is the primary storage root mapped by the primary: branch, defaulting to the usual primary-user path; injectable for tests. */
    fun resolveDocId(docId: String, primaryRoot: String = "/storage/emulated/0"): String? {
        if (docId.startsWith("primary:")) {
            val sub = docId.substringAfter("primary:", "")
            if (!isSafeSubpath(sub)) return null
            return primaryRoot.trimEnd('/') + "/" + sub.trimStart('/')
        }
        val separator = docId.indexOf(':')
        if (separator <= 0) return null
        val volume = docId.substring(0, separator)
        val sub = docId.substring(separator + 1)
        if (!volume.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) || !isSafeSubpath(sub)) return null
        return "/storage/$volume/" + sub.trimStart('/')
    }

    private fun isSafeSubpath(subpath: String): Boolean =
        subpath.trimStart('/').split('/').none { it == ".." }
}
