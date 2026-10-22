package com.aicode.feature.workspace.domain.model

enum class RemoteProtocol {
    SFTP,
    FTP
}

/** Live connection health of a remote mount (written back by SyncEngine probing/reconnection). */
enum class SyncConnectionState {
    CONNECTED,
    RECONNECTING,
    DISCONNECTED
}

data class RemoteConnection(
    val id: String,
    val name: String,
    val protocol: RemoteProtocol,
    val host: String,
    val port: Int,
    val username: String,
    val password: String = "",
    /** 'password' or 'key', corresponding to [RemoteConnectionEntity.authType]. */
    val authType: String = "password",
    /** Password (authType=password) or private-key file path (authType=key). */
    val authData: String = ""
)

data class RemoteMount(
    val id: String,
    val connectionId: String,
    val remotePath: String,
    val localMountPath: String,
    val isActive: Boolean = false,
    val autoConnect: Boolean = true,
    /** Live connection health; null means this mount is not active. */
    val connectionState: SyncConnectionState? = null,
    // Provide a convenient reference to the underlying connection when used in UI
    val connection: RemoteConnection? = null
)
