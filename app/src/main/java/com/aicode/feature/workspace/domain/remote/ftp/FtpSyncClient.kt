package com.aicode.feature.workspace.domain.remote.ftp

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import com.aicode.feature.workspace.domain.remote.RemoteFileInfo
import com.aicode.feature.workspace.domain.remote.RemoteFileRejectedException
import com.aicode.feature.workspace.domain.remote.RemoteSyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Duration

class FtpSyncClient : RemoteSyncClient {

    companion object {
        private const val TAG = "FtpSyncClient"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val CONTROL_READ_TIMEOUT_MS = 15_000
        private const val DATA_TIMEOUT_MS = 30_000
        // The built-in FTP server was measured to close the control connection after ~30s idle, so the keep-alive interval must be significantly smaller.
        private const val CONTROL_KEEPALIVE_SECONDS = 15L
        private const val CONTROL_KEEPALIVE_REPLY_SECONDS = 10L
    }

    private val ftpClient = FTPClient()

    /** FTPClient is not thread-safe: concurrent commands on the same instance would interleave control-connection replies, so all operations are serialized. */
    private val mutex = Mutex()
    private var isConnected = false

    override suspend fun connect(host: String, port: Int, username: String, auth: RemoteAuth) =
        withContext(Dispatchers.IO) { mutex.withLock { doConnect(host, port, username, auth) } }

    override suspend fun disconnect() =
        withContext(Dispatchers.IO) { mutex.withLock { doDisconnect() } }

    override suspend fun reconnect(host: String, port: Int, username: String, auth: RemoteAuth) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                doDisconnect()
                doConnect(host, port, username, auth)
            }
        }

    private fun doConnect(host: String, port: Int, username: String, auth: RemoteAuth) {
        if (auth !is RemoteAuth.Password) {
            throw IllegalArgumentException("FTP only supports Password authentication")
        }

        // Encoding must be set before connect: it affects encoding/decoding of control commands and paths (non-ASCII paths).
        ftpClient.setControlEncoding(StandardCharsets.UTF_8.name())
        ftpClient.setAutodetectUTF8(true)
        ftpClient.setConnectTimeout(CONNECT_TIMEOUT_MS)
        ftpClient.setDefaultTimeout(CONTROL_READ_TIMEOUT_MS)
        ftpClient.setDataTimeout(DATA_TIMEOUT_MS)
        // During transfers the data connection is busy while the control connection is idle; NOOP keep-alive prevents the server/router from disconnecting it as idle.
        ftpClient.setControlKeepAliveTimeout(Duration.ofSeconds(CONTROL_KEEPALIVE_SECONDS))
        ftpClient.setControlKeepAliveReplyTimeout(Duration.ofSeconds(CONTROL_KEEPALIVE_REPLY_SECONDS))

        ftpClient.connect(host, port)
        val reply = ftpClient.replyCode
        if (!FTPReply.isPositiveCompletion(reply)) {
            ftpClient.disconnect()
            throw IllegalStateException("FTP server refused connection.")
        }

        if (!ftpClient.login(username, auth.password)) {
            ftpClient.disconnect()
            throw IllegalStateException("FTP login failed")
        }

        ftpClient.enterLocalPassiveMode()
        ftpClient.setFileType(FTP.BINARY_FILE_TYPE)
        FileLogger.i(TAG, "FTP control channel encoding=${ftpClient.controlEncoding}, UTF8 feature=${ftpClient.hasFeature("UTF8")}")
        isConnected = true
    }

    private fun doDisconnect() {
        if (ftpClient.isConnected) {
            // The connection may already be half-open and logout/disconnect may themselves fail; ignore and continue.
            runCatching { ftpClient.logout() }
            runCatching { ftpClient.disconnect() }
        }
        isConnected = false
    }

    override suspend fun listFiles(remotePath: String): List<RemoteFileInfo> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val files = ftpClient.listFiles(remotePath) ?: emptyArray()
                files.map {
                    RemoteFileInfo(
                        name = it.name,
                        isDirectory = it.isDirectory,
                        size = it.size,
                        lastModified = it.timestamp.timeInMillis
                    )
                }
            }
        }

    override suspend fun downloadFile(remotePath: String, localPath: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val localFile = File(localPath)
                localFile.parentFile?.mkdirs()
                FileOutputStream(localFile).use { fos ->
                    val success = ftpClient.retrieveFile(remotePath, fos)
                    if (!success) {
                        val detail = "FTP download failed: $remotePath, reply=${ftpClient.replyCode} ${ftpClient.replyString.trim()}"
                        if (FTPReply.isNegativePermanent(ftpClient.replyCode)) throw RemoteFileRejectedException(detail)
                        throw IOException(detail)
                    }
                }
            }
        }

    override suspend fun uploadFile(localPath: String, remotePath: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val localFile = File(localPath)
                if (!localFile.exists()) return@withLock

                val remoteDir = remotePath.substringBeforeLast("/", "")
                if (remoteDir.isNotEmpty() && remoteDir != remotePath) {
                    makeDirectories(remoteDir)
                }

                FileInputStream(localFile).use { fis ->
                    val success = ftpClient.storeFile(remotePath, fis)
                    if (!success) {
                        val detail = "FTP upload failed: $remotePath, reply=${ftpClient.replyCode} ${ftpClient.replyString.trim()}"
                        if (FTPReply.isNegativePermanent(ftpClient.replyCode)) throw RemoteFileRejectedException(detail)
                        throw IOException(detail)
                    }
                }
            }
        }

    override suspend fun createDirectory(remotePath: String) =
        withContext(Dispatchers.IO) { mutex.withLock { makeDirectories(remotePath) } }

    /** Creates directories level by level (FTP MKD only creates one level); failures due to already existing are ignored. */
    private fun makeDirectories(path: String) {
        val absolute = path.startsWith("/")
        var current = ""
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            current = when {
                current.isEmpty() -> if (absolute) "/$part" else part
                else -> "$current/$part"
            }
            runCatching { ftpClient.makeDirectory(current) }
        }
    }

    override suspend fun delete(remotePath: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // Try deleting as a file first; if that fails, delete as a directory
                if (!ftpClient.deleteFile(remotePath)) {
                    ftpClient.removeDirectory(remotePath)
                }
            }
        }

    override suspend fun isConnected(): Boolean = isConnected && ftpClient.isConnected

    override suspend fun ping(): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!ftpClient.isConnected) {
                    FileLogger.w(TAG, "FTP probe: ftpClient.isConnected=false")
                    return@withLock false
                }
                try {
                    val ok = ftpClient.sendNoOp()
                    if (!ok) FileLogger.w(TAG, "FTP probe NOOP did not get a successful reply: reply=${ftpClient.replyCode}")
                    ok
                } catch (e: IOException) {
                    FileLogger.w(TAG, "FTP probe failed: ${e.javaClass.simpleName}: ${e.message}")
                    false
                }
            }
        }
}
