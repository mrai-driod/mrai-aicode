package com.aicode.feature.workspace.domain

import com.aicode.feature.agent.domain.device.DeviceRootEngine
import java.io.File

/**
 * Script generation and output parsing for [DeviceRootFileAccess] directory listing (pure functions, no Android dependencies, unit-testable).
 *
 * Why rewritten: the old implementation was "one su fork + one stat fork per item" -- a 100-item directory meant about 100 process creations,
 * and expanding the file tree on a large directory took several seconds. Now a single `find -printf` returns all attributes, reducing process creations from N+1 to 1.
 *
 * Output protocol (records separated by `\0`, fields by `\t`): `type \t size \t mtime \t mode \t name`.
 * `\0` is chosen because file names can contain tabs or even newlines (the old implementation split by line and by tab and would parse such names incorrectly;
 * this was a known limitation and is fixed here as well); name is the last segment, and any tabs inside it are rejoined by the parsing side.
 *
 * Fallback: `find -printf` depends on toybox/GNU find (toybox since Android 7 supports it). On the rare build that does not support it,
 * the command exits non-zero and the caller falls back to [legacyScript] (the old per-item stat semantics); behaviour is unchanged, just back on the slow path.
 */
internal object DeviceRootListing {

    /** find version of the script: on cd failure, keep the old convention of exit 2 (NoSuchFileException). */
    fun findScript(real: String): String =
        "cd ${DeviceRootEngine.shellQuote(real)} 2>/dev/null || exit 2; " +
            "find -L . -mindepth 1 -maxdepth 1 -printf '%y\\t%s\\t%T@\\t%m\\t%f\\0'"

    /** Legacy script (fallback): after cd, iterate the globs and stat each item for size|time|permissions. */
    fun legacyScript(real: String): String =
        "cd ${DeviceRootEngine.shellQuote(real)} 2>/dev/null || exit 2; " +
            "for f in * .[!.]* ..?*; do " +
            "[ -e \"\$f\" ] || [ -L \"\$f\" ] || continue; " +
            "if [ -d \"\$f\" ]; then t=d; elif [ -f \"\$f\" ]; then t=f; else t=o; fi; " +
            "sm=\$(stat -c '%s|%Y|%A' \"\$f\" 2>/dev/null); " +
            "printf '%s\\t%s\\t%s\\n' \"\$t\" \"\${sm:-0|0|-}\" \"\$f\"; " +
            "done"

    /**
     * Parse the output of [findScript]. [real] is the directory's real device absolute path, used to fill in [FileEntry.localFile].
     * Invalid records (too few fields, empty name) are skipped; missing statistics fields get 0 and permissions get "rwx" (consistent with the old implementation's default).
     */
    fun parseFindOutput(output: String, real: String): List<FileEntry> {
        val out = ArrayList<FileEntry>()
        for (record in output.split('\u0000')) {
            if (record.isEmpty()) continue
            val p1 = record.indexOf('\t')
            val p2 = if (p1 >= 0) record.indexOf('\t', p1 + 1) else -1
            val p3 = if (p2 >= 0) record.indexOf('\t', p2 + 1) else -1
            val p4 = if (p3 >= 0) record.indexOf('\t', p3 + 1) else -1
            if (p1 < 0 || p2 < 0 || p3 < 0 || p4 < 0) continue
            val type = record.substring(0, p1)
            val size = record.substring(p1 + 1, p2).toLongOrNull() ?: 0L
            // %T@ looks like "1729165800.0123456789": take only whole seconds (millisecond precision is sufficient for the file tree and file details)
            val mtime = record.substring(p2 + 1, p3).substringBefore('.').toLongOrNull() ?: 0L
            val mode = record.substring(p3 + 1, p4)
            val name = record.substring(p4 + 1)
            if (name.isEmpty()) continue
            out += FileEntry(
                name = name,
                isDirectory = type == "d",
                size = size,
                lastModified = mtime * 1000,
                localFile = File(real, name),
                permissions = permissionsFromOctal(mode)
            )
        }
        return out
    }

    /**
     * Parse the output of [legacyScript] (old format: `type \t size|mtime|perm \t name`, line-separated).
     * name may contain tabs (the old implementation rejoined them with subList); the same semantics are kept here.
     */
    fun parseLegacyOutput(output: String, real: String): List<FileEntry> {
        return output.lineSequence()
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                val name = parts.subList(2, parts.size).joinToString("\t")
                if (name.isEmpty()) return@mapNotNull null
                val meta = parts[1].split('|')
                FileEntry(
                    name = name,
                    isDirectory = parts[0] == "d",
                    size = meta.getOrNull(0)?.toLongOrNull() ?: 0L,
                    lastModified = (meta.getOrNull(1)?.toLongOrNull() ?: 0L) * 1000,
                    localFile = File(real, name),
                    permissions = meta.getOrNull(2)?.takeIf { it.length >= 4 }?.substring(1, 4) ?: "rwx"
                )
            }
            .toList()
    }

    /**
     * Octal permission string from `stat`/`find` (e.g. "644", "40755") -> owner rwx triplet (e.g. "rw-").
     * Only the highest digit of the last three digits (owner) is taken, consistent with the old implementation's semantics of taking the first three characters after `stat -c %A`'s `-rwx`;
     * setuid/sticky and group/other bits are meaningless for display in the file tree and file details, so they are not presented.
     * Returns "rwx" when unparseable (from root's perspective, readable/writable/executable by default).
     */
    fun permissionsFromOctal(octal: String): String {
        val digits = octal.filter { it.isDigit() }.takeLast(3)
        if (digits.length < 3 || digits.any { it !in '0'..'7' }) return "rwx"
        val v = digits[0] - '0'
        return buildString {
            append(if (v and 4 != 0) 'r' else '-')
            append(if (v and 2 != 0) 'w' else '-')
            append(if (v and 1 != 0) 'x' else '-')
        }
    }
}
