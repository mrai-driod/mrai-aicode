package com.aicode.feature.workspace.domain.remote

class RemoteFileRejectedException(message: String) : Exception(message)

interface RemoteSyncClient {
    suspend fun connect(host: String, port: Int, username: String, auth: RemoteAuth)
    suspend fun disconnect()
    suspend fun listFiles(remotePath: String): List<RemoteFileInfo>
    suspend fun downloadFile(remotePath: String, localPath: String)
    suspend fun uploadFile(localPath: String, remotePath: String)
    suspend fun createDirectory(remotePath: String)
    suspend fun delete(remotePath: String)
    suspend fun isConnected(): Boolean

    /** Atomic reconnect: disconnects the old connection and establishes a new one. Used by the sync engine for probing/failure reconnection,
     *  to prevent other coroutines from operating on a half-connected state between disconnect and connect. */
    suspend fun reconnect(host: String, port: Int, username: String, auth: RemoteAuth)

    /** Lightweight probe: whether the connection is still usable. By default it reuses the isConnected flag; network protocols may override it with a real round trip (e.g. SFTP stat / FTP NOOP),
     *  to detect disconnects that isConnected cannot, such as TCP half-open connections. */
    suspend fun ping(): Boolean = isConnected()
}

data class RemoteFileInfo(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)

sealed class RemoteAuth {
    data class Password(val password: String): RemoteAuth()
    data class PrivateKey(val privateKeyPath: String, val passphrase: String? = null): RemoteAuth()
}
