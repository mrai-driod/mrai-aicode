package com.aicode.feature.agent.domain.container

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import com.aicode.feature.credentials.domain.model.GitCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.DisconnectListener
import net.schmizz.sshj.userauth.UserAuthException
import com.hierynomus.sshj.common.KeyDecryptionFailedException
import net.schmizz.sshj.userauth.password.PasswordUtils
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RemoteSshConnection"

/**
 * Shared SSH connection manager: holds two independent sshj [SSHClient]s - an exec channel for [RemoteSshEngine]
 * to run commands, and an SFTP channel for [RemoteSftpFileAccess] to read and write files. The two are isolated,
 * so an SFTP buffer overflow cannot take down the command channel.
 *
 * The connection configuration (host/port/username/auth) is passed in by the caller at [connect] time. After a
 * disconnect, the next [connect] re-establishes it; the SFTP channel is created lazily on demand (see [sftp]).
 * Operations on the exec side are serialized ([mutex]); the SFTP side is serialized by
 * [RemoteSftpFileAccess] (sshj's SFTPClient is not thread-safe).
 */
@Singleton
class RemoteSshConnection @Inject constructor(
    private val hostKeyStore: SshHostKeyStore,
    private val hostKeyVerifier: SshHostKeyVerifier,
    private val privateKeyStore: SshPrivateKeyStore,
    private val containerInstaller: ContainerInstaller
) {

    @Volatile
    private var sshClient: SSHClient? = null

    /**
     * Independent SFTP channel: it and exec each use their own SSH transport. sshj's SFTP has an intermittent buffer
     * overflow (hierynomus/sshj#461); sharing a transport means a crash also takes down Bash/the terminal, while
     * keeping them separate limits the impact to the file channel, and a crash is covered by
     * [invalidateSftp] plus a rebuild on the next [sftp].
     */
    @Volatile
    private var sftpSshClient: SSHClient? = null

    @Volatile
    private var sftpClient: SFTPClient? = null

    private val mutex = Mutex()
    private val sftpLock = Mutex()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    /** Connection state flow, for the UI indicator and for workspace initialization waiting until the connection is ready. */
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Callback after a successful reconnect, so the workspace can reload. Registered by [startSupervisor]. */
    private var onReconnected: (suspend () -> Unit)? = null
    private var supervisorJob: Job? = null

    /** The real home path on the remote server (looked up from $HOME and cached after a successful connection), for file tools to expand ~. */
    @Volatile
    var remoteHome: String? = null
        private set

    /** Snapshot of the current connection configuration, used for reconnects and path mapping. */
    @Volatile
    var config: RemoteConnectionConfig? = null
        private set

    suspend fun connect(config: RemoteConnectionConfig) = mutex.withLock {
        if (isConnected() && this.config == config) return@withLock
        disconnectInternal()
        this.config = config
        _connectionState.value = ConnectionState.CONNECTING
        try {
            withContext(Dispatchers.IO) {
                val client = newSshClient(config)
                sshClient = client
                // Look up the real remote home (root is /root, a regular user is /home/xxx), for file tools to expand ~
                runCatching {
                    val s = client.startSession()
                    val cmd = s.exec("echo \$HOME")
                    remoteHome = java.io.BufferedReader(java.io.InputStreamReader(cmd.inputStream))
                        .readText().trim().ifEmpty { null }
                    s.close()
                }
                // Register a transport disconnect listener: push the state immediately on disconnect instead of waiting for the supervisor to poll
                client.transport.setDisconnectListener { reason, info ->
                    FileLogger.w(TAG, "SSH connection disconnected: $reason, $info")
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
                FileLogger.i(TAG, "SSH connected to ${config.host}:${config.port} as ${config.username}")
            }
            _connectionState.value = ConnectionState.CONNECTED
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.FAILED
            throw e
        }
    }

    fun removeHostKey(host: String, port: Int) {
        hostKeyStore.remove(host, port)
    }

    fun savedHostKeys(): Map<String, String> = hostKeyStore.entries()
    /** No-arg connect: reconnects using the last saved config. */
    suspend fun connect() {
        val cfg = config ?: throw IllegalStateException("SSH connection not configured")
        connect(cfg)
    }

    suspend fun disconnect() = mutex.withLock {
        disconnectInternal()
    }

    private fun disconnectInternal() {
        closeSftpInternal()
        runCatching { sshClient?.disconnect() }
        sshClient = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /** Closes and clears the independent SFTP channel; the next [sftp] rebuilds it from the current config. */
    private fun closeSftpInternal() {
        runCatching { sftpClient?.close() }
        runCatching { sftpSshClient?.disconnect() }
        sftpClient = null
        sftpSshClient = null
    }

    /** Creates one SSH transport and completes authentication and keep-alive (exec and SFTP each create one). */
    private fun newSshClient(config: RemoteConnectionConfig): SSHClient = SSHClient().apply {
        addHostKeyVerifier(hostKeyVerifier)
        connect(config.host, config.port)
        when (val auth = config.auth) {
            is RemoteAuth.Password -> authPassword(config.username, auth.password)
            is RemoteAuth.PrivateKey -> {
                val pem = privateKeyStore.readPem(auth.privateKeyPath)
                val passwordFinder = auth.passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                authPublickey(config.username, loadKeys(pem, null, passwordFinder))
            }
        }
        // Enable the SSH heartbeat keep-alive to prevent idle timeout disconnects
        runCatching {
            connection.keepAlive?.let {
                it.setKeepAliveInterval(30)
                it.start()
            }
        }
    }

    fun isConnected(): Boolean =
        sshClient?.isConnected == true && sshClient?.isAuthenticated == true

    /** Opens an exec session to run a command. The caller is responsible for closing the returned Command. */
    fun startExecSession(command: String): Session.Command {
        val client = sshClient ?: throw IllegalStateException("SSH not connected")
        val session = client.startSession()
        val cmd = session.exec(command)
        // Send EOF to the remote process's stdin immediately: every caller only reads output. Without closing it,
        // remote programs that decide their behaviour by whether stdin is readable (such as rg with no path argument) treat the channel as input to be read and wait until the command times out.
        runCatching { cmd.outputStream.close() }
        return cmd
    }

    /**
     * Opens an exec session but **keeps stdin writable**, so large content can be transferred via stdin (avoiding command-line arguments exceeding the remote ARG_MAX).
     * The caller is responsible for writing to and closing [Session.Command.outputStream], reading the output, and closing the session.
     */
    fun startExecSessionWithStdin(command: String): Session.Command {
        val client = sshClient ?: throw IllegalStateException("SSH not connected")
        return client.startSession().exec(command)
    }

    /** Opens a new Session, for the caller to allocate a PTY and start a shell. The caller is responsible for closing the Session. */
    fun startShellSession(): Session {
        val client = sshClient ?: throw IllegalStateException("SSH not connected")
        return client.startSession()
    }

    /**
     * Gets the independent SFTP client (lazily creates a second SSH connection, isolated from exec). Callers must not close it:
     * it is managed centrally by [disconnect]. When the connection is invalid (not connected / not authenticated) it is discarded and rebuilt, which covers self-healing after an SFTP channel crash.
     */
    suspend fun sftp(): SFTPClient = sftpLock.withLock {
        val cfg = config ?: throw IllegalStateException("SSH not configured")
        sftpClient
            ?.takeIf { sftpSshClient?.isConnected == true && sftpSshClient?.isAuthenticated == true }
            ?.let { return@withLock it }
        closeSftpInternal()
        val client = withContext(Dispatchers.IO) { newSshClient(cfg) }
        sftpSshClient = client
        val sftp = withContext(Dispatchers.IO) { client.newSFTPClient() }
        sftpClient = sftp
        sftp
    }

    /** Discards the current SFTP channel (called after a channel error); the next [sftp] rebuilds it. */
    suspend fun invalidateSftp() = sftpLock.withLock { closeSftpInternal() }

    /** If configured but not connected, tries to reconnect once immediately. Returns whether it is connected in the end. Triggered proactively when the App returns to the foreground. */
    suspend fun tryReconnectIfDisconnected(): Boolean {
        val cfg = config ?: return false
        if (isConnected()) return true
        _connectionState.value = ConnectionState.CONNECTING
        return runCatching { connect(cfg) }
            .onSuccess {
                FileLogger.i(TAG, "SSH reconnected successfully (foreground trigger)")
                runCatching { onReconnected?.invoke() }
            }
            .onFailure {
                FileLogger.w(TAG, "SSH reconnect failed (foreground trigger)", it)
                _connectionState.value = ConnectionState.FAILED
            }
            .isSuccess
    }

    /**
     * Starts the connection supervisor coroutine: in remote mode it periodically probes liveness, automatically
     * reconnects when the connection drops (with exponential backoff), fires the [onReconnected] callback after a
     * successful reconnect (so the workspace can reload), and maintains [connectionState].
     * Idempotent: repeated calls do not start multiple supervisors. Only takes effect in remote mode. */
    fun startSupervisor(scope: CoroutineScope, onReconnected: suspend () -> Unit) {
        this.onReconnected = onReconnected
        if (supervisorJob?.isActive == true) return
        supervisorJob = scope.launch {
            var backoffMs = 5000L
            val maxBackoffMs = 30000L
            while (isActive) {
                delay(backoffMs)
                val cfg = config ?: continue
                if (isConnected()) {
                    backoffMs = 15000 // Connection is healthy; lengthen the probe interval (fallback only, disconnects are reported immediately by the DisconnectListener)
                    continue
                }
                // Connection is down; try to reconnect
                tryReconnectIfDisconnected()
                // Exponential backoff after a failed reconnect
                if (!isConnected()) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
                } else {
                    backoffMs = 15000
                }
            }
        }
    }

    /**
     * Points the ~/workspace symlink at the remote path of the currently selected workspace, so that when the AI
     * uses ~/workspace/... paths, Bash commands (pwd, etc.) can directly reach the correct workspace directory.
     * Should be called after a workspace is selected/initialized.
     *
     * Skipped when `~/workspace` is already a real directory (the case where the workspace root was configured as
     * `~/workspace`): in that case `ln` would create the link **inside** that directory, forming a self-referential
     * loop and breaking the whole workspace; when skipped, the command path falls back to the real path.
     */
    suspend fun updateWorkspaceSymlink(workspacePath: String) {
        val client = sshClient ?: return
        val ws = workspacePath.trimEnd('/')
        if (ws.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val session = client.startSession()
                val cmd = session.exec(
                    "if [ -d ~/workspace ] && [ ! -L ~/workspace ]; then echo skip; " +
                        "else ln -sfn '$ws' ~/workspace 2>/dev/null; echo done; fi"
                )
                val out = java.io.BufferedReader(java.io.InputStreamReader(cmd.inputStream)).readText().trim()
                session.close()
                if (out == "skip") {
                    FileLogger.w(TAG, "~/workspace is already a real directory, skipping the symlink (the workspace root may be configured as ~/workspace)")
                }
            }.onFailure { FileLogger.w(TAG, "Failed to update the workspace symlink: $ws", it) }
        }
    }

    /**
     * Syncs the built-in documentation to the remote ~/.aicode/docs/, so in remote mode the AI can consult the
     * settings documentation under ~/.aicode/docs/ just as in local mode. Fully overwritten after every
     * connect/reconnect, so the remote docs update along with App upgrades. The docs are plain text and can be
     * written with exec + printf, with no need for SFTP.
     *
     * @param docs path relative to docs/ (may contain subdirectories, such as guide/terminal.md) -> file content.
     */
    suspend fun uploadDocs(docs: Map<String, String>) {
        if (docs.isEmpty()) return
        val client = sshClient ?: return
        val home = remoteHome ?: return
        val destDir = home.trimEnd('/') + "/.aicode/docs"
        withContext(Dispatchers.IO) {
            runCatching {
                // First create all subdirectories and delete the old .md files: re-categorizing docs between versions changes paths, and overwriting without cleaning would leave stale files behind
                val subDirs = docs.keys.mapNotNull { key ->
                    key.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
                }.distinct()
                val mkdirArgs = (listOf(destDir) + subDirs.map { "$destDir/$it" }).joinToString(" ") { "'$it'" }
                val prepSession = client.startSession()
                prepSession.exec("mkdir -p $mkdirArgs; find '$destDir' -type f -name '*.md' -delete").join()
                prepSession.close()
                for ((name, content) in docs) {
                    val dest = "$destDir/$name"
                    val escaped = content.replace("\\", "\\\\").replace("'", "'\\\"'\"'")
                    val session = client.startSession()
                    session.exec("printf %s '$escaped' > '$dest'").join()
                    session.close()
                }
                FileLogger.i(TAG, "Synced ${docs.size} documents to remote $destDir")
            }.onFailure { FileLogger.w(TAG, "Failed to sync documents to remote", it) }
        }
    }

    /**
     * Extracts App built-in resources (sub-agent definitions, panel example scripts, etc.) to the remote `~/.aicode/<subdir>/`.
     * Only fills in files missing on the server and **does not overwrite existing ones** (preserving user edits); scripts are given the executable bit.
     * Called after connect/reconnect, like [uploadDocs]: in remote mode the global agents/scripts are authoritative on the remote side,
     * and the built-in defaults need to exist remotely to work "out of the box". Binary-safe (base64 transfer).
     */
    suspend fun uploadBuiltinFiles(
        destSubdir: String,
        files: Map<String, ByteArray>,
        executable: Boolean = false
    ) {
        if (files.isEmpty()) return
        val client = sshClient ?: return
        val home = remoteHome ?: return
        val destDir = home.trimEnd('/') + "/.aicode/" + destSubdir.trim('/')
        withContext(Dispatchers.IO) {
            runCatching {
                val mk = client.startSession()
                mk.exec("mkdir -p '$destDir'").join()
                mk.close()
                for ((name, bytes) in files) {
                    val dest = "$destDir/$name"
                    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    val chmod = if (executable) " chmod +x '$dest';" else ""
                    val session = client.startSession()
                    session.exec("if [ ! -e '$dest' ]; then printf %s '$b64' | base64 -d > '$dest';$chmod fi").join()
                    session.close()
                }
                FileLogger.i(TAG, "Extracted ${files.size} built-in files to remote $destDir")
            }.onFailure { FileLogger.w(TAG, "Failed to extract built-in files to remote: $destSubdir", it) }
        }
    }

    /**
     * Injects the App's git credentials into the remote server (only called when the user has enabled "auto-inject"):
     * writes `~/.aicode/git-credentials` (store format) + `~/.aicode/gitconfig` + `~/.aicode/gitconfig.credential`.
     *
     * How it takes effect: [RemoteSshEngine] injects `GIT_CONFIG_GLOBAL=$HOME/.aicode/gitconfig` when running commands, in which
     * `[include] path=~/.gitconfig` preserves the server user's own global config (name/signature, etc.), and `[includeIf "gitdir:<workspace root>/"]`
     * scopes the store helper to repositories under the workspace root only, so it affects neither the user running git manually on the server nor repositories in other directories.
     *
     * @param credentials the credentials to inject (one per host).
     * @param workspaceRoot the remote workspace root path (the directory the AI's ~/workspace maps to), scoping the injection.
     */
    suspend fun uploadGitCredentialConfig(credentials: List<GitCredential>, workspaceRoot: String) {
        val client = sshClient ?: return
        val home = remoteHome ?: return
        val aicodeDir = home.trimEnd('/') + "/.aicode"
        val wsRoot = workspaceRoot.trimEnd('/').ifEmpty { return }
        withContext(Dispatchers.IO) {
            runCatching {
                val mkdirSession = client.startSession()
                mkdirSession.exec("mkdir -p '$aicodeDir'").join()
                mkdirSession.close()

                val creds = credentials.joinToString("\n") { c ->
                    "https://${enc(c.username)}:${enc(c.token)}@${c.host}"
                }.let { if (it.isNotEmpty()) "$it\n" else "" }
                writeRemoteFile(client, "$aicodeDir/git-credentials", encodeCreds(creds))

                // Upload the credential helper script (extracted locally by ContainerInstaller) and configure it as the only helper;
                // there is no App on the server, so AICODE_CRED_NO_PROMPT makes a miss exit empty immediately instead of waiting for a prompt dialog.
                containerInstaller.extractCredentialHelper()
                val helperScript = java.io.File(containerInstaller.aicodeDir, "git-credential-aicode").readText()
                writeRemoteFile(client, "$aicodeDir/git-credential-aicode", helperScript)
                val chmodSession = client.startSession()
                chmodSession.exec("chmod +x '$aicodeDir/git-credential-aicode'").join()
                chmodSession.close()

                val gitconfig = buildString {
                    append("[include]\n")
                    append("    path = ~/.gitconfig\n")
                    append("[includeIf \"gitdir:$wsRoot/\"]\n")
                    append("    path = ~/.aicode/gitconfig.credential\n")
                }
                writeRemoteFile(client, "$aicodeDir/gitconfig", gitconfig)

                val credentialConfig = buildString {
                    append("[credential]\n")
                    append("    helper = !AICODE_CRED_NO_PROMPT=1 $aicodeDir/git-credential-aicode\n")
                }
                writeRemoteFile(client, "$aicodeDir/gitconfig.credential", credentialConfig)
                FileLogger.i(TAG, "Injected git credentials to remote $aicodeDir (scoped to $wsRoot/)")
            }.onFailure { FileLogger.w(TAG, "Failed to inject git credentials to remote", it) }
        }
    }

    /**
     * Revokes the remote injection: deletes the three App-managed config/credential files.
     * When `GIT_CONFIG_GLOBAL` points at a non-existent file git silently skips it, so the injection stops taking effect and the server user's own config is unaffected.
     */
    suspend fun removeGitCredentialConfig() {
        val client = sshClient ?: return
        val home = remoteHome ?: return
        val aicodeDir = home.trimEnd('/') + "/.aicode"
        withContext(Dispatchers.IO) {
            runCatching {
                val session = client.startSession()
                session.exec("rm -f '$aicodeDir/gitconfig' '$aicodeDir/gitconfig.credential' '$aicodeDir/git-credentials' '$aicodeDir/git-credential-aicode' 2>/dev/null; echo done").join()
                session.close()
                FileLogger.i(TAG, "Revoked the remote git credential injection")
            }.onFailure { FileLogger.w(TAG, "Failed to revoke the remote git credential injection", it) }
        }
    }

    /** Writes a text file to the remote side via exec + printf (content is the raw text; single quotes/backslashes are escaped internally). */
    private suspend fun writeRemoteFile(client: SSHClient, dest: String, content: String) {
        if (content.isEmpty()) {
            // Empty content truncates directly, avoiding the quoting ambiguity of printf %s ''
            val session = client.startSession()
            session.exec("printf '' > '$dest'").join()
            session.close()
            return
        }
        val escaped = content.replace("\\", "\\\\").replace("'", "'\\\"'\"'")
        val session = client.startSession()
        session.exec("printf %s '$escaped' > '$dest'").join()
        session.close()
    }

    private fun enc(part: String): String = java.net.URLEncoder.encode(part, "UTF-8")

    /** Encoding consistent with the app side: base64, then reversed as a whole (this is obfuscation, not encryption). */
    private fun encodeCreds(plain: String): String =
        android.util.Base64.encodeToString(plain.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP).reversed()
}

/** Remote SSH connection state, for the UI indicator and for workspace initialization timing decisions. */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}

/** Translates an SSH connection exception into a friendly message, for the tool layer to echo to the AI/user. */
fun friendlySshError(e: Throwable): String {
    val msg = e.message.orEmpty()
    return when {
        e is KeyDecryptionFailedException ->
            "SSH authentication failed: the private key passphrase is incorrect or the private key format is not supported ($msg)"
        e is UserAuthException ->
            "SSH authentication failed: the username, password, or key was not accepted by the server ($msg)"
        "ECONNREFUSED" in msg || "Connection refused" in msg ->
            "Cannot connect to the SSH server (connection refused); please make sure the remote server is running and the port is correct"
        "UnknownHost" in msg || "unknown host" in msg ->
            "Cannot connect to the SSH server (unknown host); please check the host address"
        "Auth fail" in msg || "authentication" in msg ->
            "SSH authentication failed; please check the username and password"
        "Network is unreachable" in msg ->
            "Network is unreachable; please check the network connection"
        "SocketTimeout" in msg || "timed out" in msg ->
            "Connecting to the SSH server timed out; please check the network or server status"
        "SSH not connected" in msg ->
            "SSH is not connected; please wait for the connection to recover or check the configuration in Settings"
        else -> "SSH connection failed: ${e.message ?: "unknown error"}"
    }
}

/** Remote SSH connection configuration. */
data class RemoteConnectionConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val auth: RemoteAuth,
    /** Workspace root path on the remote server (the AI's ~/workspace maps to this path). */
    val remoteWorkspacePath: String
)
