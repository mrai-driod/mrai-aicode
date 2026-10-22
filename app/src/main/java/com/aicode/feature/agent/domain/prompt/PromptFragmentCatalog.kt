package com.aicode.feature.agent.domain.prompt

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.workspace.domain.ProjectAicodeRoot
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Fragment source, in priority order from high to low: project > global > local > built-in. */
enum class PromptFragmentSource { PROJECT, GLOBAL, LOCAL, BUILTIN }

/** The fragment that ultimately takes effect for a number. */
data class PromptFragment(
    val number: Int,
    val title: String,
    val source: PromptFragmentSource,
    val content: String,
    /** The file of the effective layer; null when the source is built-in. */
    val file: File?,
    /** The `<!-- ... -->` comment at the start of the body, used as the list summary. */
    val description: String
) {
    val stableReorderKey: String
        get() = "$title:${content.hashCode()}"

    val body: String
        get() = content.replace(LEADING_COMMENT, "")

    companion object {
        private val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")

        fun composeContent(description: String, body: String): String =
            if (description.isBlank()) body else "<!-- ${description.trim().replace("-->", "-- >")} -->\n$body"
    }

    /** Whether the effective layer is writable (project/global are writable, local/built-in are read-only). */
    val editable: Boolean
        get() = source == PromptFragmentSource.PROJECT || source == PromptFragmentSource.GLOBAL
}

/**
 * Four-level source resolution for prompt fragments: for the same number, only the highest-priority layer takes effect.
 *
 * - Project: workspace `.aicode/prompts.custom/` (for remote, the private directory via [ProjectAicodeRoot])
 * - Global: `<aicodeDir>/prompts.custom/`
 * - Local: `<aicodeDir>/prompts/` (the copy extracted from built-in at startup)
 * - Built-in: `assets/prompts/`
 *
 * The number is the identity (the first two digits of the file name) and decides the fragment's order in the system prompt.
 */
@Singleton
class PromptFragmentCatalog @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerInstaller: ContainerInstaller,
    private val projectAicodeRoot: ProjectAicodeRoot
) {

    private val globalDir: File get() = File(containerInstaller.aicodeDir, CUSTOM_DIR)
    private val localDir: File get() = File(containerInstaller.aicodeDir, LOCAL_DIR)

    /** The project-layer directory for a workspace; returns null when there is no workspace. */
    private fun projectDir(projectRoot: String?): File? =
        projectRoot?.takeIf { it.isNotBlank() }
            ?.let { File(projectAicodeRoot.forPath(it), CUSTOM_DIR) }

    /** The write layer for edits/additions: writes to the project layer when there is a workspace, otherwise the global layer. */
    fun writableDir(projectRoot: String?): File = projectDir(projectRoot) ?: globalDir

    /** Lists all effective fragments in ascending numeric order. */
    fun list(projectRoot: String?): List<PromptFragment> {
        val builtin = builtinFiles()
        val project = numberedFragments(projectDir(projectRoot))
        val global = numberedFragments(globalDir)
        val numbers = LinkedHashSet<Int>().apply {
            addAll(builtin.keys)
            addAll(project.keys)
            addAll(global.keys)
        }
        val all = numbers.sorted().mapNotNull { resolve(it, builtin, project, global) }
        // When "fully disable built-in prompts" is on, the list keeps only custom sources, consistent with the injection result.
        return if (isBuiltinDisabled()) {
            all.filter {
                it.source == PromptFragmentSource.PROJECT || it.source == PromptFragmentSource.GLOBAL
            }
        } else {
            all
        }
    }

    fun fragment(number: Int, projectRoot: String?): PromptFragment? = resolve(
        number,
        builtinFiles(),
        numberedFragments(projectDir(projectRoot)),
        numberedFragments(globalDir)
    )

    /** Static baseline body: all effective fragments with their leading comments removed, concatenated by number. */
    fun renderStatic(projectRoot: String?): String =
        list(projectRoot)
            .mapNotNull { it.content.replace(LEADING_COMMENT, "").trim().takeIf { text -> text.isNotEmpty() } }
            .joinToString("\n\n")

    /** "Custom fragments only" mode: takes only the custom fragment bodies from the project and global layers. */
    fun renderCustomOnly(projectRoot: String?): String =
        list(projectRoot)
            .filter { it.source == PromptFragmentSource.PROJECT || it.source == PromptFragmentSource.GLOBAL }
            .mapNotNull { it.content.replace(LEADING_COMMENT, "").trim().takeIf { text -> text.isNotEmpty() } }
            .joinToString("\n\n")

    /** Whether the "fully disable built-in prompts" marker exists (global layer). */
    fun isBuiltinDisabled(): Boolean = PromptFragmentResolver.isBuiltinDisabled(globalDir)

    /**
     * Saves the override for a number.
     *
     * - [target] specifies the write layer (project/global); null means write to the layer currently in effect for this number (falling back to a writable layer if it is read-only).
     * - [previousNumber] when editing changed the number, pass the original number so the old number's override can be cleared.
     * - Only one copy per number: before writing, clear old files with the same number from each writable layer.
     */
    fun saveOverride(
        number: Int,
        title: String,
        content: String,
        projectRoot: String?,
        target: PromptFragmentSource? = null,
        previousNumber: Int? = null
    ): Boolean {
        val project = projectDir(projectRoot)
        val dir = when (target) {
            PromptFragmentSource.PROJECT -> project ?: globalDir
            PromptFragmentSource.GLOBAL -> globalDir
            else -> fragment(number, projectRoot)?.takeIf { it.editable }?.file?.parentFile
                ?: writableDir(projectRoot)
        }
        return try {
            if (previousNumber != null && previousNumber != number) {
                deleteOverridesFor(previousNumber, project)
                deleteOverridesFor(previousNumber, globalDir)
            }
            if (!dir.exists()) dir.mkdirs()
            deleteOverridesFor(number, dir)
            if (project != null && project != dir) deleteOverridesFor(number, project)
            if (globalDir != dir) deleteOverridesFor(number, globalDir)
            File(dir, "%02d-%s.md".format(number, sanitizeTitle(title))).writeText(content)
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to write prompt override: $number", e)
            false
        }
    }

    /**
     * Renumbers and persists in the order after dragging: the set of numbers stays the same; each fragment's content is mapped onto these numbers in turn,
     * thereby changing the injection order (smaller numbers come earlier). Written to a writable layer.
     */
    fun reorder(reordered: List<PromptFragment>, projectRoot: String?): Boolean {
        if (reordered.isEmpty()) return false
        val project = projectDir(projectRoot)
        val dir = writableDir(projectRoot)
        val numbers = reordered.map { it.number }.sorted()
        return try {
            if (!dir.exists()) dir.mkdirs()
            numbers.forEach { number ->
                deleteOverridesFor(number, dir)
                if (project != null && project != dir) deleteOverridesFor(number, project)
                if (globalDir != dir) deleteOverridesFor(number, globalDir)
            }
            reordered.forEachIndexed { index, fragment ->
                File(dir, "%02d-%s.md".format(numbers[index], sanitizeTitle(fragment.title)))
                    .writeText(fragment.content)
            }
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to reorder prompts", e)
            false
        }
    }

    /** Deletes the override for a number (only when the effective layer is writable); after deletion it automatically falls back to the next layer. */
    fun deleteOverride(number: Int, projectRoot: String?): Boolean {
        val file = fragment(number, projectRoot)?.takeIf { it.editable }?.file ?: return false
        return file.delete()
    }

    /** Toggles the "fully disable built-in prompts" marker (global layer). */
    fun setBuiltinDisabled(disabled: Boolean): Boolean {
        return try {
            val marker = File(globalDir, PromptFragmentResolver.DISABLE_BUILTIN_FILE)
            if (disabled) {
                if (!globalDir.exists()) globalDir.mkdirs()
                marker.writeText("")
            } else if (marker.isFile) {
                marker.delete()
            }
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to toggle disabling built-in prompts", e)
            false
        }
    }

    private fun resolve(
        number: Int,
        builtin: Map<Int, String>,
        project: Map<Int, File>,
        global: Map<Int, File>
    ): PromptFragment? {
        project[number]?.let { return readFragment(it, number, PromptFragmentSource.PROJECT) }
        global[number]?.let { return readFragment(it, number, PromptFragmentSource.GLOBAL) }
        builtin[number]?.let { name ->
            File(localDir, name).takeIf { it.isFile }?.let {
                return readFragment(it, number, PromptFragmentSource.LOCAL)
            }
            readAsset(name)?.let { content ->
                return PromptFragment(
                    number,
                    titleOf(name),
                    PromptFragmentSource.BUILTIN,
                    content,
                    null,
                    extractDescription(content)
                )
            }
        }
        return null
    }

    private fun readFragment(file: File, number: Int, source: PromptFragmentSource): PromptFragment? =
        readText(file)?.let { content ->
            PromptFragment(number, titleOf(file.name), source, content, file, extractDescription(content))
        }

    /** Top-level `<two-digits>-<name>.md` files in the directory, deduplicated by number (for the same number, the lexicographically first). */
    private fun numberedFragments(dir: File?): Map<Int, File> {
        val files = dir?.listFiles() ?: return emptyMap()
        val byNumber = LinkedHashMap<Int, File>()
        files.filter { it.isFile }.sortedBy { it.name }.forEach { file ->
            PromptFragmentResolver.parseNumber(file.name)?.let { byNumber.putIfAbsent(it, file) }
        }
        return byNumber
    }

    /** Numbered md files at the top level of `assets/prompts`: number -> file name. */
    private fun builtinFiles(): Map<Int, String> {
        val names = runCatching { context.assets.list(ASSET_DIR)?.toList() }.getOrNull() ?: return emptyMap()
        val byNumber = LinkedHashMap<Int, String>()
        names.filter { it.endsWith(".md") }.sorted().forEach { name ->
            PromptFragmentResolver.parseNumber(name)?.let { byNumber.putIfAbsent(it, name) }
        }
        return byNumber
    }

    private fun readAsset(name: String): String? = runCatching {
        context.assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun readText(file: File): String? = runCatching { file.readText() }.getOrNull()

    private fun deleteOverridesFor(number: Int, dir: File?): Boolean {
        val files = dir?.listFiles { file ->
            file.isFile && PromptFragmentResolver.parseNumber(file.name) == number
        } ?: return false
        var deleted = false
        files.forEach { deleted = it.delete() || deleted }
        return deleted
    }

    private fun titleOf(fileName: String): String {
        val base = fileName.removeSuffix(".md")
        return base.substringAfter('-', missingDelimiterValue = base)
    }

    /** The `<!-- ... -->` comment at the start of the body is the summary (built-in fragments all carry one line). */
    private fun extractDescription(content: String): String =
        DESCRIPTION.find(content)?.groupValues?.get(1)?.trim().orEmpty()

    private fun sanitizeTitle(title: String): String =
        title.trim().replace(Regex("[^A-Za-z0-9._\\u4e00-\\u9fa5-]"), "-").take(40).ifBlank { "fragment" }

    private companion object {
        const val TAG = "PromptFragmentCatalog"
        const val ASSET_DIR = "prompts"
        const val CUSTOM_DIR = "prompts.custom"
        const val LOCAL_DIR = "prompts"
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
        val DESCRIPTION = Regex("^\\s*<!--\\s*(.*?)\\s*-->", RegexOption.DOT_MATCHES_ALL)
    }
}
