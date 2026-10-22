package com.aicode.feature.agent.domain.container

import com.aicode.core.security.KeystoreCipher
import com.aicode.feature.settings.data.repository.ContainerSettingsRepository
import com.aicode.feature.settings.data.repository.normalizeRemoteWorkspacePath
import com.aicode.feature.workspace.data.local.dao.RemoteConnectionDao
import com.aicode.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.aicode.feature.workspace.domain.remote.RemoteAuth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the remote connection configuration for the currently active container profile.
 *
 * Replaces the old execution_mode_prefs copy of the connection parameters: a change to the active profile, to a
 * profile's contents (editing the remote path or re-binding its channel), or to the connection table (changing
 * host/port/account/password) all trigger a re-resolve, producing a [RemoteConnectionConfig] that can be handed
 * directly to [RemoteSshConnection.connect], so connection edits take effect immediately with no need to switch
 * containers away and back.
 *
 * Authentication is resolved solely from the connection's own authType: password connections yield the password,
 * key connections yield the private key path and passphrase, consistent with the workspace-synced SFTP channel.
 */
@Singleton
class ActiveRemoteConnectionResolver @Inject constructor(
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val dao: RemoteConnectionDao,
    private val loginKeyStore: SshLoginKeyStore
) {
    /** The connection configuration resolved from the active profile; null for a local profile, a missing channel, or a deleted connection. */
    val activeConfigFlow: Flow<RemoteConnectionConfig?> = combine(
        containerSettingsRepository.activeProfileIdFlow,
        containerSettingsRepository.customProfilesFlow,
        dao.getAllConnections()
    ) { activeId, profiles, connections ->
        resolve(activeId, profiles, connections)
    }.distinctUntilChanged()

    private fun resolve(
        activeId: String,
        profiles: List<ContainerProfile>,
        connections: List<RemoteConnectionEntity>
    ): RemoteConnectionConfig? {
        val profile = profiles.firstOrNull { it.id == activeId }
            ?: ContainerProfile.BUILTIN_ALPINE.takeIf { it.id == activeId }
            ?: return null
        val ssh = profile.rootfsSource as? RootfsSource.RemoteSsh ?: return null
        val conn = connections.firstOrNull { it.id == ssh.connectionId } ?: return null
        val auth = if (conn.authType.equals("PASSWORD", ignoreCase = true)) {
            RemoteAuth.Password(KeystoreCipher.decryptString(conn.authData))
        } else {
            val passphrase = loginKeyStore.entries().firstOrNull { it.path == conn.authData }?.passphrase
                ?: conn.passphrase?.let { KeystoreCipher.decryptString(it) }
            RemoteAuth.PrivateKey(
                privateKeyPath = conn.authData,
                passphrase = passphrase
            )
        }
        return RemoteConnectionConfig(
            host = conn.host,
            port = conn.port,
            username = conn.username,
            auth = auth,
            remoteWorkspacePath = normalizeRemoteWorkspacePath(ssh.remoteWorkspacePath, conn.username)
        )
    }
}
