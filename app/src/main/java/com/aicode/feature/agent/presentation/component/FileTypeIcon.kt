package com.aicode.feature.agent.presentation.component

import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.FeatherIcons
import compose.icons.feathericons.Code
import compose.icons.feathericons.Database
import compose.icons.feathericons.File
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Image
import compose.icons.feathericons.Music
import compose.icons.feathericons.Package
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Video

/**
 * Monochrome linear icon for a file-browse row; picks a suitable Feather linear icon based on the file's meaning and extension.
 * Matches the app-wide minimalist linear look and is tinted with the theme colour when rendered.
 */
fun fileTypeIconFor(name: String): ImageVector {
    val lower = name.lowercase()
    if (lower in SPECIAL_TERMINAL_FILES) return FeatherIcons.Terminal
    if (lower in SPECIAL_TEXT_FILES || lower.startsWith(".env")) return FeatherIcons.FileText

    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in CODE_EXTS -> FeatherIcons.Code
        in TERMINAL_EXTS -> FeatherIcons.Terminal
        in TEXT_AND_CONFIG_EXTS -> FeatherIcons.FileText
        in IMAGE_EXTS -> FeatherIcons.Image
        in VIDEO_EXTS -> FeatherIcons.Video
        in AUDIO_EXTS -> FeatherIcons.Music
        in ARCHIVE_EXTS -> FeatherIcons.Package
        in DATABASE_EXTS -> FeatherIcons.Database
        else -> FeatherIcons.File
    }
}

/** Source code file extensions. */
private val CODE_EXTS = setOf(
    "kt", "kts", "java", "py", "pyw", "pyi",
    "js", "mjs", "cjs", "jsx", "ts", "mts", "cts", "tsx",
    "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "hxx",
    "cs", "go", "rs", "php", "rb", "swift", "dart", "lua",
    "scala", "groovy", "vue", "svelte", "astro",
    "html", "htm", "xhtml", "css", "scss", "sass", "less", "styl",
    "r", "pl", "pm", "tcl", "hs", "fs", "fsx", "clj", "cljs",
    "ex", "exs", "erl", "v", "zig", "nim", "d", "jl"
)

/** Script and command-line file extensions. */
private val TERMINAL_EXTS = setOf(
    "sh", "bash", "zsh", "ksh", "csh", "fish", "bat", "cmd", "ps1", "awk", "sed"
)

/** Common extensionless script or build files. */
private val SPECIAL_TERMINAL_FILES = setOf(
    "dockerfile", "containerfile", "makefile", "rakefile", "vagrantfile", "justfile"
)

/** Documents, plain text and assorted config files (json, yaml, xml, properties, env, etc., all presented uniformly as text). */
private val TEXT_AND_CONFIG_EXTS = setOf(
    "md", "markdown", "mdown", "txt", "rst", "asciidoc", "adoc",
    "doc", "docx", "rtf", "log", "csv", "tsv", "pdf",
    "json", "jsonc", "json5", "yaml", "yml", "toml", "xml",
    "ini", "cfg", "conf", "properties", "gradle", "pro", "plist",
    "lock", "editorconfig"
)

/** Common standalone text or config-rule file names. */
private val SPECIAL_TEXT_FILES = setOf(
    ".gitignore", ".gitattributes", ".gitmodules", ".dockerignore",
    ".npmrc", ".prettierrc", ".eslintrc", ".clang-format",
    "license", "notice", "authors", "contributors", "changelog"
)

/** Image formats. */
private val IMAGE_EXTS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico",
    "heic", "heif", "avif", "tif", "tiff"
)

/** Video media formats. */
private val VIDEO_EXTS = setOf(
    "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm"
)

/** Audio formats. */
private val AUDIO_EXTS = setOf(
    "mp3", "wav", "flac", "aac", "ogg", "m4a", "wma"
)

/** Archives and install packages. */
private val ARCHIVE_EXTS = setOf(
    "zip", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "7z", "rar",
    "apk", "apks", "xapk", "aab", "jar", "war", "ear", "deb", "rpm", "ipa", "dmg", "iso"
)

/** Database files. */
private val DATABASE_EXTS = setOf(
    "sql", "db", "sqlite", "sqlite3", "mdb"
)
