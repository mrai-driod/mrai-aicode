package com.aicode.feature.agent.domain.permission

import android.content.Context
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 高危拦截规则的持久化与内置预设。
 *
 * 存于 App 私有目录（`filesDir/aicode/high_risk_rules.json`）——容器内/真机工作区内的 AI 均无法篡改，
 * 契合授权层「规则绝不落盘到工作区」的安全原则（与全局授权规则同位置）。
 *
 * 首次启动写入全部预设规则；预设带版本号，后续版本新增预设会在加载时按 id 增量合并
 * （用户已有的开关状态与修改保留）。用户可增删改、单条开关，也可一键恢复预设。
 */
@Singleton
class HighRiskRulesRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "HighRiskRules"
        const val RULES_FILE = "high_risk_rules.json"
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    }

    @Serializable
    private data class HighRiskRuleFile(
        val presetVersion: Int = 0,
        val rules: List<HighRiskRule> = emptyList()
    )

    private val file: File
        get() = File(File(context.filesDir, "aicode"), RULES_FILE)

    private val state = MutableStateFlow<List<HighRiskRule>?>(null)
    private val mutex = Mutex()

    /** 规则流，供设置页订阅；首次订阅触发懒加载。 */
    val rulesFlow: Flow<List<HighRiskRule>> = flow {
        ensureLoaded()
        emitAll(state.filterNotNull())
    }

    /** 一次性读取当前规则（权限评估时用）。 */
    suspend fun getRulesOnce(): List<HighRiskRule> {
        ensureLoaded()
        return state.value ?: emptyList()
    }

    /** 新增一条用户规则（id 由调用方生成，建议 `user-<timestamp>`）。 */
    suspend fun addRule(rule: HighRiskRule) = edit { list ->
        if (list.none { it.id == rule.id }) list.add(rule)
    }

    /** 按 id 更新规则（编辑内容/开关）。 */
    suspend fun updateRule(rule: HighRiskRule) = edit { list ->
        val idx = list.indexOfFirst { it.id == rule.id }
        if (idx >= 0) list[idx] = rule
    }

    suspend fun removeRule(id: String) = edit { list ->
        list.removeAll { it.id == id }
    }

    /** 恢复预设：内置预设全量重置为默认（启用），用户自建规则保留。 */
    suspend fun resetToPresets() = edit { list ->
        list.removeAll { it.builtin }
        list.addAll(HighRiskRulePresets.RULES)
        FileLogger.i(TAG, "已恢复高危拦截规则预设")
    }

    // ── 内部 ──────────────────────────────────────────────────

    private suspend fun ensureLoaded() {
        if (state.value != null) return
        mutex.withLock {
            if (state.value != null) return
            state.value = withContext(Dispatchers.IO) { loadOrSeed() }
        }
    }

    /** 文件不存在写入预设；存在则按版本增量合并新预设（保留用户改动）。 */
    private fun loadOrSeed(): List<HighRiskRule> {
        if (!file.isFile) {
            val seed = HighRiskRulePresets.RULES
            writeToFile(HighRiskRuleFile(HighRiskRulePresets.VERSION, seed))
            return seed
        }
        val parsed = runCatching {
            JSON.decodeFromString<HighRiskRuleFile>(file.readText())
        }.getOrElse {
            FileLogger.w(TAG, "读取 ${file.path} 失败: ${it.message}，回退预设")
            return HighRiskRulePresets.RULES
        }
        if (parsed.presetVersion >= HighRiskRulePresets.VERSION) return parsed.rules
        // 版本迁移：1) 剔除已废弃预设；2) 调整过 pattern 的预设按新定义覆盖（保留用户自改的开关状态）；
        // 3) 再补入用户列表里不存在的新预设。用户自建规则不受影响。
        val removed = HighRiskRulePresets.REMOVED_IDS
        val updated = parsed.rules
            .filter { it.id !in removed }
            .map { old -> HighRiskRulePresets.UPDATES[old.id]?.copy(enabled = old.enabled) ?: old }
        val existingIds = updated.map { it.id }.toSet()
        val merged = updated + HighRiskRulePresets.RULES.filter { it.id !in existingIds }
        writeToFile(HighRiskRuleFile(HighRiskRulePresets.VERSION, merged))
        return merged
    }

    private suspend fun edit(mutate: (MutableList<HighRiskRule>) -> Unit) {
        ensureLoaded()
        mutex.withLock {
            val list = (state.value ?: emptyList()).toMutableList()
            mutate(list)
            withContext(Dispatchers.IO) {
                writeToFile(HighRiskRuleFile(HighRiskRulePresets.VERSION, list))
            }
            state.value = list
        }
    }

    private fun writeToFile(content: HighRiskRuleFile) {
        file.parentFile?.mkdirs()
        file.writeText(JSON.encodeToString(content))
    }
}

/**
 * 内置预设高危规则（[HighRiskRulePresets.VERSION] 递增触发对存量用户的增量合并）。
 *
 * 收录原则（v2 收紧口径后）：只拦「不可逆、影响系统状态、可外泄数据」三类里**后果重**的操作——
 * 磁盘擦除/格式化、系统开关机与属性、包与权限管理、SIGKILL、防火墙、管道执行、SSH/SCP 外传、
 * Git 历史破坏、公开发布、系统包卸载、账户与定时任务。
 *
 * v2 起**不拦**（日常开发高频、后果可控）：普通 `rm 文件`、`rm -f`、递归以外的一切删除、
 * `start/stop/su/sudo/doas`、`pkill/killall`、`nc/rsync/sftp`、语言包卸载
 * （npm/pip/yarn/pnpm/gem/cargo uninstall）、`git clean/restore/checkout --` 等
 * 工作区级回滚——这些在 RULE_BASED 挡位下改走「单次放行」而非强制拦截。
 */
object HighRiskRulePresets {

    const val VERSION = 2

    private const val CAT_DELETE = "Deletion & erasure"
    private const val CAT_DISK = "Disk & partitions"
    private const val CAT_SYSTEM = "System control"
    private const val CAT_PACKAGE = "Packages & permission management"
    private const val CAT_PROCESS = "Process management"
    private const val CAT_NETWORK = "Network & firewall"
    private const val CAT_EXFIL = "Download, execution & data exfiltration"
    private const val CAT_GIT = "Destructive Git operations"
    private const val CAT_PUBLISH = "Publishing & package changes"
    private const val CAT_ACCOUNT = "Accounts & scheduled tasks"

    private fun p(id: String, pattern: String, category: String, desc: String) =
        HighRiskRule(id = "preset.$id", pattern = pattern, matchType = HighRiskMatchType.PREFIX, category = category, description = desc)

    private fun r(id: String, pattern: String, category: String, desc: String) =
        HighRiskRule(id = "preset.$id", pattern = pattern, matchType = HighRiskMatchType.REGEX, category = category, description = desc)

    /**
     * 已从预设中除名、迁移时要从存量用户配置里清掉的 id（v1 → v2）。
     * 这些规则误伤面大或属日常开发高频操作，拦截收益低于打扰成本。
     */
    val REMOVED_IDS: Set<String> = setOf(
        "preset.start", "preset.stop",
        "preset.su", "preset.sudo", "preset.doas",
        "preset.nc", "preset.sftp", "preset.rsync",
        "preset.truncate",
        "preset.killall", "preset.pkill",
        "preset.git-clean", "preset.git-restore", "preset.git-checkout-discard",
        "preset.pip-uninstall", "preset.npm-uninstall", "preset.yarn-remove",
        "preset.pnpm-remove", "preset.gem-uninstall", "preset.cargo-uninstall"
    )

    /** v1 → v2 调整了定义（保留 id、迁移时覆盖 pattern/matchType，用户开关状态保留）。 */
    val UPDATES: Map<String, HighRiskRule> = mapOf(
        // v1 的 "rm" 拦所有删除；v2 只拦递归删除（-r/-R/--recursive 任一递归形态），
        // 普通 `rm 文件` 与 `rm -f 文件` 交回单次放行。
        "preset.rm" to r("rm", "^rm\\s+(-[a-zA-Z]*[rR][a-zA-Z]*\\s|--recursive(\\s|$))", CAT_DELETE, "Recursive directory deletion (rm -r/-R/--recursive)")
    )

    val RULES: List<HighRiskRule> = listOf(
        // ── 删除与擦除 ──
        r("rm", "^rm\\s+(-[a-zA-Z]*[rR][a-zA-Z]*\\s|--recursive(\\s|$))", CAT_DELETE, "Recursive directory deletion (rm -r/-R/--recursive)"),
        p("shred", "shred", CAT_DELETE, "Securely erases files; unrecoverable"),

        // ── 磁盘与分区 ──
        p("dd", "dd", CAT_DISK, "Reads/writes disk/device images directly; can destroy data"),
        r("mkfs", "mkfs(\\.[a-z0-9]+)?(\\s|$)", CAT_DISK, "Formats a filesystem"),
        p("mkswap", "mkswap", CAT_DISK, "Creates a swap partition"),
        p("wipefs", "wipefs", CAT_DISK, "Erases device filesystem signatures"),
        p("fdisk", "fdisk", CAT_DISK, "Modifies the disk partition table"),
        p("sfdisk", "sfdisk", CAT_DISK, "Scripted modification of the disk partition table"),
        p("parted", "parted", CAT_DISK, "Modifies disk partitions"),
        p("blkdiscard", "blkdiscard", CAT_DISK, "Discards all data on a block device"),
        r("dev-write", ">\\s*/dev/(sd|mmcblk|nvme|block)", CAT_DISK, "Redirect writes directly to a block device"),

        // ── 系统控制 ──
        p("reboot", "reboot", CAT_SYSTEM, "Reboots the device"),
        p("shutdown", "shutdown", CAT_SYSTEM, "Shuts down"),
        p("poweroff", "poweroff", CAT_SYSTEM, "Powers off"),
        p("halt", "halt", CAT_SYSTEM, "Halts the system"),
        p("setenforce", "setenforce", CAT_SYSTEM, "Disables SELinux enforcing mode, weakening system security"),
        p("mount", "mount", CAT_SYSTEM, "Mounts a filesystem"),
        p("umount", "umount", CAT_SYSTEM, "Unmounts a filesystem"),
        p("setprop", "setprop", CAT_SYSTEM, "Modifies Android system properties"),
        p("svc", "svc", CAT_SYSTEM, "Toggles WiFi/mobile data/power"),
        p("settings-put", "settings put", CAT_SYSTEM, "Modifies global system settings"),
        p("device-config-put", "device_config put", CAT_SYSTEM, "Modifies device configuration parameters"),

        // ── 包与权限管理 ──
        p("pm-uninstall", "pm uninstall", CAT_PACKAGE, "Uninstalls an app"),
        p("pm-clear", "pm clear", CAT_PACKAGE, "Clears app data"),
        p("pm-disable", "pm disable", CAT_PACKAGE, "Disables an app"),
        p("pm-grant", "pm grant", CAT_PACKAGE, "Grants app permissions"),
        p("pm-revoke", "pm revoke", CAT_PACKAGE, "Revokes app permissions"),
        p("appops-set", "appops set", CAT_PACKAGE, "Modifies app ops permissions"),
        r("chmod-recursive", "chmod\\s+(-[a-zA-Z0-9]*R|--recursive)", CAT_PACKAGE, "Recursively changes file permissions"),
        r("chown-recursive", "chown\\s+(-[a-zA-Z0-9]*R|--recursive)", CAT_PACKAGE, "Recursively changes file ownership"),

        // ── 进程管理 ──
        r("kill-9", "kill\\s+(-[a-zA-Z]*9|-SIGKILL)\\b", CAT_PROCESS, "Force-kills a process (SIGKILL)"),

        // ── 网络与防火墙 ──
        p("iptables", "iptables", CAT_NETWORK, "Modifies firewall rules"),
        p("ip6tables", "ip6tables", CAT_NETWORK, "Modifies IPv6 firewall rules"),
        p("nft", "nft", CAT_NETWORK, "Modifies the nftables firewall"),
        p("tc", "tc", CAT_NETWORK, "Modifies traffic control rules"),

        // ── 下载执行与数据外发 ──
        r("pipe-to-shell", "\\|\\s*(sudo\\s+)?(ba|z|fi|da)?sh\\b", CAT_EXFIL, "Pipes downloaded content directly into a shell (curl/wget … | sh)"),
        p("ssh", "ssh", CAT_EXFIL, "Connects to an external host; can exfiltrate data"),
        p("scp", "scp", CAT_EXFIL, "Copies files to an external host"),

        // ── Git 破坏性操作 ──
        r("git-push-force", "git\\s+push\\s+.*(-f\\b|--force)", CAT_GIT, "Force push; overwrites remote commit history"),
        p("git-reset-hard", "git reset --hard", CAT_GIT, "Hard reset; discards all working-tree and staged changes"),
        p("git-branch-force-delete", "git branch -D", CAT_GIT, "Force-deletes a branch (including unmerged commits)"),
        p("git-filter-branch", "git filter-branch", CAT_GIT, "Rewrites the entire commit history"),

        // ── 发布与包管理变更 ──
        p("npm-publish", "npm publish", CAT_PUBLISH, "Publishes an npm package publicly; cannot be undone"),
        p("yarn-publish", "yarn publish", CAT_PUBLISH, "Publishes an npm package publicly; cannot be undone"),
        p("pnpm-publish", "pnpm publish", CAT_PUBLISH, "Publishes an npm package publicly; cannot be undone"),
        p("cargo-publish", "cargo publish", CAT_PUBLISH, "Publishes a crate publicly; cannot be undone"),
        p("gem-push", "gem push", CAT_PUBLISH, "Publishes a gem publicly"),
        p("twine-upload", "twine upload", CAT_PUBLISH, "Uploads and publishes a Python package"),
        p("docker-push", "docker push", CAT_PUBLISH, "Pushes an image to a remote registry"),
        r("apt-remove", "apt(-get)?\\s+(remove|purge|autoremove)", CAT_PUBLISH, "Uninstalls a system package"),
        p("apk-del", "apk del", CAT_PUBLISH, "Uninstalls a package"),
        r("dnf-remove", "(dnf|yum)\\s+(remove|erase|autoremove)", CAT_PUBLISH, "Uninstalls a system package"),
        p("pacman-remove", "pacman -R", CAT_PUBLISH, "Uninstalls a package"),

        // ── 账户与定时任务 ──
        p("crontab", "crontab", CAT_ACCOUNT, "Modifies scheduled tasks (-r clears all directly)"),
        p("passwd", "passwd", CAT_ACCOUNT, "Changes an account password"),
        p("useradd", "useradd", CAT_ACCOUNT, "Creates a system account"),
        p("userdel", "userdel", CAT_ACCOUNT, "Deletes a system account"),
        p("usermod", "usermod", CAT_ACCOUNT, "Modifies a system account")
    )
}
