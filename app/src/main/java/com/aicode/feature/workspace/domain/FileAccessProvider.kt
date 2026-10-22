package com.aicode.feature.workspace.domain

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

/** Directory entry information, returned by [FileAccessProvider.listFiles]. */
data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    /** File size (bytes); may be 0 for directories. */
    val size: Long,
    /** Last modified time (epoch milliseconds). */
    val lastModified: Long,
    /** In local mode a host [File] may be provided for callers that need a local path (e.g. [BitmapFactory]); null in remote mode. */
    val localFile: File? = null,
    /** Readable/writable/executable permission bits (rwx string, e.g. "rwx"); in remote mode parsed from SFTP permissions, with a sensible default when unavailable. */
    val permissions: String = "---"
)

/**
 * File read/write backend abstraction: decouples "where files are read and written" from hardcoded local java.io.File.
 *
 * Two implementations:
 * - `LocalFileAccess`: wraps a layer of [WorkspacePathMapper] and reads directly via java.io.File (local-mode behaviour unchanged);
 * - `RemoteSftpFileAccess`: reads and writes remote files via SFTP.
 *
 * Path arguments are uniformly the "container paths" given by AI (`~/workspace/...`), mapped internally by each implementation to a local host path or a remote path.
 * The tool layer (FileTools/ImageTools/ListFilesTool/EditFileTool) depends on this interface rather than a concrete implementation,
 * and DI injects the corresponding instance according to the current execution mode.
 *
 * Capabilities that need a local file path (e.g. [android.graphics.BitmapFactory.decodeFile]) use [readBytes] or
 * [copyToLocal] to obtain a local temporary file first -- in remote mode it is downloaded via SFTP to a temporary directory.
 */
interface FileAccessProvider {

    /**
     * Read the full text of a file. Throws [NoSuchFileException] when the file does not exist.
     * In remote mode, throws [RemoteOutputTooLargeException] for files exceeding the implementation limit, to avoid reading the whole file into memory.
     */
    fun readFile(path: String): String

    /**
     * Lazily read a file line by line, for [ReadFileTool] to read by line window: reads only as far as needed and the whole file never enters memory;
     * a single line exceeding the limit (64K characters by default) is truncated.
     * The sequence can be iterated repeatedly (each iteration reopens the underlying reader); callers must iterate on an IO thread; throws [NoSuchFileException] when the file does not exist.
     */
    fun readLines(path: String): Sequence<String>

    /**
     * Write the full text of a file. Parent directories are created automatically if missing. Throws [FileAlreadyExistsException] when [overwrite] is false and the file already exists.
     * [encoding] specifies the on-disk character encoding; UTF-8 by default. Callers should pass back the "encoding detected when reading" unchanged,
     * to avoid non-UTF-8 files such as GBK being silently transcoded and corrupted on save.
     */
    fun writeFile(path: String, content: String, overwrite: Boolean = true, encoding: Charset = Charsets.UTF_8)

    /** Whether the file exists. */
    fun exists(path: String): Boolean

    /** Whether the file is a directory. */
    fun isDirectory(path: String): Boolean

    /** Whether the file is a regular file. */
    fun isFile(path: String): Boolean

    /** File size (bytes). */
    fun fileSize(path: String): Long

    /** Last modified time (epoch milliseconds). */
    fun lastModified(path: String): Long

    /** Permission bits string (e.g. "rwx"). */
    fun permissions(path: String): String

    /**
     * List the entries in a directory. The local implementation uses [File.listFiles]; the remote implementation uses SFTP ls.
     * Does not include `.` and `..`. The returned [FileEntry.localFile] is null in remote mode.
     */
    fun listFiles(path: String): List<FileEntry>

    /**
     * Recursively list all regular files under [path], to a depth of at most [maxDepth] (direct children of [path] count as level 1).
     * Returns paths relative to [path] (`/`-separated); returns an empty list when the directory does not exist.
     * Used for skill / sub-agent directory scans.
     */
    fun listFilesRecursive(path: String, maxDepth: Int): List<String>

    /**
     * Batch-read the full text of multiple files, returning `path -> content`; when a single file fails to read, that entry is absent (does not throw).
     *
     * The default implementation calls [readFile] one by one. Channels where "one call = one cross-process or cross-network round trip", such as device ROOT / SFTP,
     * **must** override this to fetch in a single round trip: skill scans and sub-agent scans read hundreds of files at once,
     * and reading them one by one degrades into hundreds of child-process forks (tens to hundreds of milliseconds each), dragging the caller straight into an ANR.
     */
    fun readFiles(paths: List<String>): Map<String, String> {
        if (paths.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>(paths.size)
        for (p in paths) runCatching { readFile(p) }.onSuccess { out[p] = it }
        return out
    }

    /**
     * Batch-read the first [maxBytes] characters of multiple files (truncated by character, with no guarantee of UTF-8 boundary alignment --
     * only for scenarios such as parsing frontmatter that care only about the file head).
     *
     * Default implementation = [readFiles] then truncate each entry; channels where "one call = one cross-process or cross-network round trip", such as device ROOT / SFTP,
     * **must** override this to fetch in a single round trip: locating a skill by skill name reads the heads of hundreds of SKILL.md files,
     * and reading them one by one degrades into hundreds of child-process forks (tens to hundreds of milliseconds each), dragging loadSkill straight into an ANR.
     * Same return convention as [readFiles]: entries that fail to read are absent and do not throw.
     */
    fun readFileHeads(paths: List<String>, maxBytes: Int): Map<String, String> {
        if (paths.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>(paths.size)
        for (p in paths) runCatching { readFile(p) }.onSuccess { out[p] = it.take(maxBytes) }
        return out
    }

    /**
     * Read a file's raw bytes. Used by tools such as [ViewImageTool] that need binary data.
     * In remote mode, if the caller needs a local file path, use [copyToLocal] instead; throws [RemoteOutputTooLargeException] when the file is too large.
     */
    fun readBytes(path: String): ByteArray

    /**
     * Write a file's raw bytes. Parent directories are created automatically if missing. Throws [FileAlreadyExistsException] when [overwrite] is false and the file already exists.
     * Used by tools such as [GenerateImageTool] that need to write binary data (images, etc.) to disk, and for binary writes such as uploading attachments;
     * text content still uses [writeFile].
     */
    fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean = true)

    /**
     * Stream-write a file from [input], returning the number of bytes written; the content does not reside in memory as a whole.
     * Parent directories are created automatically if missing. Throws [FileAlreadyExistsException] when [overwrite] is false and the file already exists.
     * Used for scenarios such as uploading large attachments.
     */
    fun writeStream(path: String, input: InputStream, overwrite: Boolean = true): Long

    /**
     * Copy a file to a local temporary file and return its [File].
     * In local mode, returns the mapped host [File] directly (no copy);
     * in remote mode, downloads via SFTP to a cache directory and returns the local temporary file.
     * Used by [ViewImageTool] (which needs a local path to feed BitmapFactory), etc.
     */
    fun copyToLocal(path: String): File

    /** Delete a file or empty directory. */
    fun delete(path: String)

    /** Recursively delete a file or directory (when the directory is non-empty, delete it together with its contents). Returns silently when the target does not exist. */
    fun deleteRecursively(path: String)

    /**
     * Rename / move. Throws [FileAlreadyExistsException] when [newPath] already exists,
     * [NoSuchFileException] when [path] does not exist, and [java.io.IOException] for other failures.
     */
    fun rename(path: String, newPath: String)

    /**
     * Copy a file or directory (including its contents) to [newPath]. Throws [NoSuchFileException] when [path] does not exist,
     * and [java.io.IOException] for other failures. Throws [FileAlreadyExistsException] when [overwrite] is false and [newPath] already exists;
     * when true, overwrites.
     */
    fun copy(path: String, newPath: String, overwrite: Boolean = false)

    /**
     * Move (cut) [path] to [newPath]. Throws [NoSuchFileException] when [path] does not exist,
     * and [java.io.IOException] for other failures. Throws [FileAlreadyExistsException] when [overwrite] is false and [newPath] already exists;
     * when true, overwrites.
     * Implementations may use rename or "copy + delete source".
     */
    fun move(path: String, newPath: String, overwrite: Boolean = false)

    /** Create a directory (including parent directories). */
    fun mkdirs(path: String)

    /** Get the parent directory path (in container-path form). */
    fun parentPath(path: String): String?

    /** Restore an internal real path to the container path from AI's perspective (for display). */
    fun toDisplayPath(path: String): String
}

/**
 * Whether a single directory entry name is valid: non-empty, contains no path separator, and is not `.` or `..`.
 * Blocks inputs such as `../` that would escape the current directory; used by create/rename in file browsing.
 */
fun isValidFileEntryName(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isNotEmpty() &&
        !trimmed.contains('/') &&
        !trimmed.contains('\\') &&
        trimmed != "." &&
        trimmed != ".."
}
