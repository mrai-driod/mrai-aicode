package com.aicode.feature.terminal.domain.font

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.res.ResourcesCompat
import com.aicode.R
import com.aicode.core.util.FileLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** An available terminal font: absolute file path + display name (file name without extension). */
data class TerminalFont(
    val path: String,
    val displayName: String
)

/**
 * Terminal custom font management: imported ttf/otf files are stored in the app's private directory, loaded by path and cached as Typeface.
 * Does not use Hilt; both the settings panel and terminal rendering sides call it directly with a Context.
 */
object TerminalFontManager {

    /** Pseudo-path for the built-in font. Not a real file path; anywhere that checks whether fontPath exists must allow it through first. */
    const val BUILTIN_PATH = "builtin:jetbrains-mono-nl"

    private const val DIR_NAME = "terminal_fonts"
    private val SUPPORTED_EXTENSIONS = setOf("ttf", "otf", "ttc")

    private val typefaceCache = ConcurrentHashMap<String, Typeface>()

    fun fontsDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    fun listFonts(context: Context): List<TerminalFont> =
        fontsDir(context).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
            ?.sortedBy { it.name.lowercase() }
            ?.map { TerminalFont(path = it.absolutePath, displayName = it.nameWithoutExtension) }
            ?: emptyList()

    /** Copies a font file to the private directory and verifies it can be parsed; returns null on failure. */
    fun importFont(context: Context, uri: Uri): TerminalFont? {
        val fileName = queryDisplayName(context, uri) ?: return null
        if (File(fileName).extension.lowercase() !in SUPPORTED_EXTENSIONS) return null

        val target = File(fontsDir(context), fileName)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null

            // An invalid font file will throw here, avoiding leaving a bad file in the directory
            Typeface.createFromFile(target)
            typefaceCache.remove(target.absolutePath)
            TerminalFont(path = target.absolutePath, displayName = target.nameWithoutExtension)
        } catch (e: Exception) {
            FileLogger.e("TerminalFont", "Failed to import font: $fileName", e)
            target.delete()
            null
        }
    }

    fun deleteFont(path: String): Boolean {
        typefaceCache.remove(path)
        return File(path).delete()
    }

    /** Load a font; returns null when the path is empty or the file is corrupted, letting the caller fall back to the system monospace font. */
    fun loadTypeface(context: Context, path: String): Typeface? {
        if (path.isBlank()) return null
        if (path == BUILTIN_PATH) {
            typefaceCache[BUILTIN_PATH]?.let { return it }
            return runCatching { ResourcesCompat.getFont(context, R.font.jetbrains_mono_nl) }
                .onFailure { FileLogger.e("TerminalFont", "Failed to load built-in font", it) }
                .getOrNull()
                ?.also { typefaceCache[BUILTIN_PATH] = it }
        }
        typefaceCache[path]?.let { return it }
        val file = File(path)
        if (!file.isFile) return null
        return try {
            Typeface.createFromFile(file).also { typefaceCache[path] = it }
        } catch (e: Exception) {
            FileLogger.e("TerminalFont", "Failed to load font: $path", e)
            null
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return sanitize(name)
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { sanitize(it) }
    }

    private fun sanitize(name: String): String = name.replace(Regex("[/\\\\:\\s]+"), "_")
}
