package com.aicode.feature.settings.presentation.component

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Surface
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.SegmentedTabs
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.aicode.core.ui.AppTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.domain.container.ContainerProfile
import com.aicode.feature.agent.domain.container.RootfsSource
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.DEFAULT_REMOTE_WORKSPACE_ROOT
import com.aicode.feature.settings.presentation.ContainerResetUiState
import com.aicode.feature.workspace.domain.model.RemoteConnection
import com.aicode.feature.workspace.domain.model.RemoteProtocol
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Server
import compose.icons.feathericons.Trash2
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Container image secondary page: all containers (including the built-in Alpine) are managed uniformly — single-select switching in the list, edit, reset, swipe-to-delete;
 * when all are deleted, the empty state can restore the built-in Alpine with one tap. The built-in Alpine is automatically written to the list on first launch and receives no special treatment.
 *
 * When a profile is selected, the global execution mode is switched in sync according to its [ContainerProfile.mode] — local images use the PRoot container,
 * remote SSH images use SSH exec/SFTP.
 */
@Composable
internal fun ContainerSection(
    profiles: List<ContainerProfile>,
    activeProfileId: String,
    defaultContainerId: String = ContainerProfile.BUILTIN_ID,
    osMap: Map<String, String> = emptyMap(),
    showAddSheetExternal: Boolean = false,
    onDismissAddSheet: () -> Unit = {},
    onSelect: (String) -> Unit,
    onSetDefaultContainer: (String) -> Unit = {},
    onSaveCustom: (ContainerProfile) -> Unit,
    onEditCustom: (ContainerProfile) -> Unit,
    onDeleteProfile: (ContainerProfile) -> Unit,
    onSwitchConfirmed: () -> Unit = {},
    onResetProfile: (ContainerProfile) -> Unit = {},
    /** Non-null means a reset is in progress; the UI shows a non-cancellable progress dialog until deletion completes. */
    resetState: ContainerResetUiState? = null,
    onRestoreBuiltin: () -> Unit = {},
    remoteConnections: List<RemoteConnection> = emptyList()
) {
    val context = LocalContext.current
    var showAddSheetInternal by remember { mutableStateOf(false) }
    val showAddSheet = showAddSheetInternal || showAddSheetExternal
    var editingProfile by remember { mutableStateOf<ContainerProfile?>(null) }
    var deletingProfile by remember { mutableStateOf<ContainerProfile?>(null) }
    var pendingSwitch by remember { mutableStateOf<ContainerProfile?>(null) }
    var pendingReset by remember { mutableStateOf<ContainerProfile?>(null) }

    if (profiles.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.container_empty),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.container_empty_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.lg)
            )
            Button(onClick = onRestoreBuiltin) {
                Text(stringResource(R.string.container_restore_builtin))
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
        ) {
            SettingsGroup {
                profiles.forEachIndexed { index, profile ->
                    if (index > 0) {
                        SettingsDivider()
                    }
                    ContainerRow(
                        profile = profile,
                        active = profile.id == activeProfileId,
                        defaultContainerId = defaultContainerId,
                        osId = osMap[profile.id],
                        subtitle = profileSubtitle(context, profile, remoteConnections),
                        onSelect = { if (profile.id != activeProfileId) pendingSwitch = profile },
                        onEdit = { editingProfile = profile },
                        onDelete = { deletingProfile = profile }
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        ProfileEditSheet(
            initial = null,
            defaultContainerId = defaultContainerId,
            remoteConnections = remoteConnections,
            onDismiss = {
                showAddSheetInternal = false
                onDismissAddSheet()
            },
            onConfirm = { profile, setDefault ->
                val id = "custom-${System.currentTimeMillis()}"
                val final = if (profile.mode == ExecutionMode.REMOTE_SSH) {
                    profile.copy(id = id, name = profile.name.ifBlank { context.getString(R.string.container_remote_ssh) })
                } else {
                    profile.copy(id = id, name = profile.name.ifBlank { context.getString(R.string.container_custom_image) })
                }
                onSaveCustom(final)
                if (setDefault) onSetDefaultContainer(final.id)
                showAddSheetInternal = false
                onDismissAddSheet()
            }
        )
    }

    editingProfile?.let { editing ->
        ProfileEditSheet(
            initial = editing,
            defaultContainerId = defaultContainerId,
            remoteConnections = remoteConnections,
            onDismiss = { editingProfile = null },
            onReset = { pendingReset = editing },
            onConfirm = { profile, setDefault ->
                onEditCustom(profile.copy(id = editing.id))
                // Only a local image can be the default container; when turning off default, if it was originally the default, fall back to the built-in Alpine.
                if (profile.mode == ExecutionMode.LOCAL_PROOT) {
                    if (setDefault) {
                        onSetDefaultContainer(editing.id)
                    } else if (editing.id == defaultContainerId) {
                        onSetDefaultContainer(ContainerProfile.BUILTIN_ID)
                    }
                }
                editingProfile = null
            }
        )
    }

    deletingProfile?.let { deleting ->
        AlertDialog(
            onDismissRequest = { deletingProfile = null },
            title = { Text(stringResource(R.string.container_delete_config)) },
            text = { Text(stringResource(R.string.container_delete_confirm, deleting.name, deleteHint(context, deleting))) },
            confirmButton = {
                TextButton(onClick = {
                    deleteImageCopy(deleting)
                    onDeleteProfile(deleting)
                    deletingProfile = null
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { deletingProfile = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    pendingSwitch?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text(stringResource(R.string.container_switch_image)) },
            text = { Text(stringResource(R.string.container_switch_confirm, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onSwitchConfirmed()
                    onSelect(target.id)
                    pendingSwitch = null
                }) { Text(stringResource(R.string.common_switch)) }
            },
            dismissButton = { TextButton(onClick = { pendingSwitch = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    pendingReset?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingReset = null },
            title = { Text(stringResource(R.string.container_reset_title)) },
            text = { Text(stringResource(R.string.container_reset_confirm, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onResetProfile(target)
                    pendingReset = null
                    if (editingProfile?.id == target.id) editingProfile = null
                }) { Text(stringResource(R.string.container_reset)) }
            },
            dismissButton = { TextButton(onClick = { pendingReset = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    // Deleting a large container takes tens of seconds: before deletion completes, occupy the UI with a non-cancellable progress dialog to avoid the user thinking it is stuck or tapping repeatedly.
    resetState?.let { state ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.container_resetting_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Spacing.md))
                    Text(stringResource(R.string.container_resetting_message, state.name, state.deleted))
                }
            },
            confirmButton = {}
        )
    }
}

/** Additional note for the delete confirmation: built-in can be restored, custom local clears the rootfs, remote SSH has no additional note. */
private fun deleteHint(context: Context, profile: ContainerProfile): String = when {
    profile.isBuiltin -> context.getString(R.string.container_builtin_restorable)
    profile.mode == ExecutionMode.REMOTE_SSH -> ""
    else -> context.getString(R.string.container_rootfs_will_be_cleared)
}

/** System logo mapping: a recognized system returns the corresponding monochrome icon, unknown/unrecognized returns null (the caller falls back to a generic icon). */
@Composable
private fun osLogo(osId: String?): Painter? = when (osId) {
    "alpine" -> painterResource(R.drawable.logo_alpine)
    "centos" -> painterResource(R.drawable.logo_centos)
    "ubuntu" -> painterResource(R.drawable.logo_ubuntu)
    "debian" -> painterResource(R.drawable.logo_debian)
    else -> null
}

/**
 * Single container row: a white row inside a group, with an icon block on the left + name (with a badge for built-in) / subtitle, a selection checkmark + edit/reset buttons on the right, swipe-to-delete.
 * Tapping the row body switches the image (shows a confirmation when not selected).
 */
@Composable
private fun ContainerRow(
    profile: ContainerProfile,
    active: Boolean,
    defaultContainerId: String = ContainerProfile.BUILTIN_ID,
    osId: String?,
    subtitle: String,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val light = settingsLightMode()

    SwipeToDeleteRow(
        onDelete = onDelete,
        onClick = onSelect
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left icon block
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    )
            ) {
                val osIcon = osLogo(osId)
                if (osIcon != null) {
                    Icon(
                        painter = osIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(22.dp)
                            .align(Alignment.Center)
                    )
                } else {
                    Icon(
                        imageVector = if (profile.mode == ExecutionMode.REMOTE_SSH) FeatherIcons.Server else FeatherIcons.HardDrive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(22.dp)
                            .align(Alignment.Center)
                    )
                }
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            // Middle: name (built-in badge) + subtitle
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (profile.isBuiltin) {
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        SourceBadge(
                            text = stringResource(R.string.container_builtin_badge),
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (profile.mode == ExecutionMode.REMOTE_SSH) {
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        SourceBadge(
                            text = stringResource(R.string.container_ssh_badge),
                            color = MaterialTheme.colorScheme.secondary
                        )
                    } else {
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        SourceBadge(
                            text = stringResource(R.string.container_custom_badge),
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    if (profile.id == defaultContainerId) {
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        SourceBadge(
                            text = stringResource(R.string.container_default_badge),
                            color = MaterialTheme.semanticColors.warning
                        )
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // Selection checkmark
            if (active) {
                Spacer(modifier = Modifier.width(Spacing.sm))
                Icon(
                    imageVector = FeatherIcons.Check,
                    contentDescription = stringResource(R.string.container_active),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Edit entry (all containers): pencil at the end of the row, tap to enter the edit dialog (tapping the row body switches)
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = FeatherIcons.Edit3,
                    contentDescription = stringResource(R.string.common_edit),
                    tint = MaterialTheme.semanticColors.subtleText,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** Source badge: small text in a capsule with a light theme-color background; different colors are passed in by source type. */
@Composable
private fun SourceBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(Radius.pill))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

/** Image list item subtitle: local images (built-in/custom) uniformly show the effective shell, without distinguishing the source; remote SSH shows the channel name. */
private fun profileSubtitle(context: Context, profile: ContainerProfile, connections: List<RemoteConnection>): String {
    if (profile.mode == ExecutionMode.REMOTE_SSH) {
        val ssh = profile.rootfsSource as? RootfsSource.RemoteSsh
        val connName = ssh?.connectionId?.let { cid -> connections.firstOrNull { it.id == cid }?.name }
        return connName ?: context.getString(R.string.container_channel_deleted)
    }
    val shellDesc = profile.shellPath?.ifBlank { null } ?: "/bin/sh"
    return context.getString(R.string.container_shell_desc, shellDesc)
}

/**
 * ModalBottomSheet for adding/editing an image: top tabs switch between local image / remote SSH.
 * Local image branch: name, shell path, extra bindings, extra arguments, select a tar.gz file.
 * Remote SSH branch: name, dropdown to select an SFTP channel configured in the workspace, remote workspace path.
 * When editing the built-in Alpine, the image source stays built-in (optionally import a new file to overwrite; after importing it becomes a custom container).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditSheet(
    initial: ContainerProfile?,
    defaultContainerId: String = ContainerProfile.BUILTIN_ID,
    remoteConnections: List<RemoteConnection>,
    onDismiss: () -> Unit,
    onReset: (() -> Unit)? = null,
    onConfirm: (ContainerProfile, Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val flingFix = rememberSheetFlingFix(sheetState)
    val localScrollState = rememberScrollState()
    val remoteScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    // Only SFTP channels are suitable for SSH exec (FTP/LOCAL do not use sshj)
    val sshConnections = remoteConnections.filter { it.protocol == RemoteProtocol.SFTP }
    // When editing the built-in Alpine, keep the Asset source (optionally import to overwrite)
    val initialAsset = initial?.rootfsSource as? RootfsSource.Asset

    var mode by remember { mutableStateOf(initial?.mode ?: ExecutionMode.LOCAL_PROOT) }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    // Whether to set as the default container (only effective for local images; the default container is the runtime container for local MCP and other services in remote mode)
    var setAsDefault by remember { mutableStateOf(initial?.id == defaultContainerId) }
    // Local image fields
    var shellPath by remember { mutableStateOf(initial?.shellPath ?: "/bin/sh") }
    val bindingsList = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(initial?.extraBindings?.map { b ->
                val parts = b.split(":", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else b to ""
            } ?: emptyList())
        }
    }
    val argsList = remember {
        mutableStateListOf<String>().apply { addAll(initial?.extraArgs ?: emptyList()) }
    }
    val envList = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(initial?.env?.toList() ?: emptyList())
        }
    }
    val initialUri = (initial?.rootfsSource as? RootfsSource.LocalFile)?.uri
    var pickedUri by remember { mutableStateOf(initialUri) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(pickedUri) {
        pickedName = pickedUri?.let { queryImageDisplayName(context, it) }
    }
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) pickedUri = uri.toString() }
    // Remote SSH fields
    val initialSsh = (initial?.rootfsSource as? RootfsSource.RemoteSsh)
    var selectedConnId by remember { mutableStateOf(initialSsh?.connectionId ?: sshConnections.firstOrNull()?.id ?: "") }
    var remotePath by remember { mutableStateOf(initialSsh?.remoteWorkspacePath ?: "") }
    var connExpanded by remember { mutableStateOf(false) }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0.dp) },
        dialogMaxWidth = 600.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = screenHeight * 0.85f)
                .imePadding()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top: centered title + reset in the top-right corner (edit state)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.size(36.dp))
                Text(
                    text = if (initial == null) stringResource(R.string.container_add_image) else stringResource(R.string.container_edit_image),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                if (initial != null && onReset != null) {
                    IconButton(
                        onClick = onReset,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            FeatherIcons.RefreshCw,
                            contentDescription = stringResource(R.string.container_reset),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(36.dp))
                }
            }

            // Local image / remote SSH switch: shares the same slider segmented control as the sidebar
            val modes = listOf(ExecutionMode.LOCAL_PROOT, ExecutionMode.REMOTE_SSH)
            SegmentedTabs(
                selected = modes.indexOf(mode).coerceAtLeast(0),
                labels = listOf(
                    stringResource(R.string.container_local_image),
                    stringResource(R.string.container_remote_ssh)
                ),
                onSelect = { mode = modes[it] }
            )

            // Middle scrollable form area: fills the remaining height via weight(1f), preventing height jumps when switching between tabs
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(flingFix)
            ) {
                if (mode == ExecutionMode.LOCAL_PROOT) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(localScrollState),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ContainerField(
                            value = name,
                            onValueChange = { name = it },
                            label = stringResource(R.string.common_name)
                        )

                        ContainerField(
                            value = shellPath,
                            onValueChange = { shellPath = it },
                            label = stringResource(R.string.container_shell_path)
                        )

                        MountListEditor(
                            title = stringResource(R.string.container_extra_bindings),
                            items = bindingsList,
                            emptyText = stringResource(R.string.container_no_bindings),
                            addText = stringResource(R.string.container_add_binding)
                        )

                        StringListEditor(
                            title = stringResource(R.string.container_extra_proot_args),
                            items = argsList,
                            itemLabel = stringResource(R.string.container_arg_value),
                            itemHint = "-k",
                            emptyText = stringResource(R.string.container_no_args),
                            addText = stringResource(R.string.container_add_arg)
                        )

                        PairListEditor(
                            title = stringResource(R.string.container_env_vars),
                            items = envList,
                            keyLabel = stringResource(R.string.container_env_name),
                            keyHint = "MY_VAR",
                            valueLabel = stringResource(R.string.container_env_value),
                            valueHint = "value",
                            emptyText = stringResource(R.string.container_no_env_vars),
                            addText = stringResource(R.string.container_add_env_var)
                        )

                        // The built-in image is fixed to the built-in source and does not support import overwrite, so file selection is hidden
                        if (initial?.rootfsSource !is RootfsSource.Asset) {
                            Spacer(modifier = Modifier.size(Spacing.xs))
                            Surface(
                                onClick = { pickLauncher.launch(arrayOf("*/*")) },
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        FeatherIcons.HardDrive,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = pickedName
                                            ?: if (pickedUri != null) stringResource(R.string.container_file_selected)
                                            else stringResource(R.string.container_select_image_file),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // Set as default container: the runtime container for local MCP and other services in remote workspace mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.container_set_as_default),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.container_default_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            AppSwitch(
                                checked = setAsDefault,
                                onCheckedChange = { setAsDefault = it }
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(remoteScrollState),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ContainerField(
                            value = name,
                            onValueChange = { name = it },
                            label = stringResource(R.string.common_name)
                        )

                        ExposedDropdownMenuBox(
                            expanded = connExpanded,
                            onExpandedChange = { connExpanded = !connExpanded }
                        ) {
                            val selectedName = sshConnections.firstOrNull { it.id == selectedConnId }?.name
                                ?: stringResource(R.string.container_select_ssh_channel)
                            ContainerField(
                                value = selectedName,
                                onValueChange = {},
                                readOnly = true,
                                label = stringResource(R.string.container_ssh_channel),
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = connExpanded) },
                                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            )
                            ExposedDropdownMenu(
                                expanded = connExpanded,
                                onDismissRequest = { connExpanded = false }
                            ) {
                                if (sshConnections.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.container_no_sftp_channel)) },
                                        onClick = { connExpanded = false }
                                    )
                                } else {
                                    sshConnections.forEach { conn ->
                                        DropdownMenuItem(
                                            text = { Text("${conn.name} (${conn.host}:${conn.port})") },
                                            onClick = {
                                                selectedConnId = conn.id
                                                if (remotePath.isBlank()) {
                                                    remotePath = DEFAULT_REMOTE_WORKSPACE_ROOT
                                                }
                                                connExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        ContainerField(
                            value = remotePath,
                            onValueChange = { remotePath = it },
                            label = stringResource(R.string.container_remote_workspace_path),
                            placeholder = DEFAULT_REMOTE_WORKSPACE_ROOT
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.size(Spacing.xs))
            Button(
                onClick = {
                    importing = true
                    scope.launch {
                        val finalUri = pickedUri?.let { uri ->
                            withContext(Dispatchers.IO) { copyImageToPrivate(context, uri, initial?.id) }
                                ?: pickedUri
                        } ?: pickedUri
                        importing = false
                        val profile = buildProfile(
                            initial = initial,
                            mode = mode,
                            name = name,
                            shellPath = shellPath,
                            bindings = bindingsList
                                .filter { it.first.isNotBlank() && it.second.isNotBlank() }
                                .map { (local, container) -> "${local.trim()}:${container.trim()}" },
                            args = argsList.map { it.trim() }.filter { it.isNotEmpty() },
                            env = envList
                                .map { it.first.trim() to it.second }
                                .filter { it.first.isNotEmpty() }
                                .toMap(),
                            pickedUri = finalUri,
                            selectedConnId = selectedConnId,
                            remotePath = remotePath
                        )
                        if (profile != null) {
                            onConfirm(profile, setAsDefault && profile.mode == ExecutionMode.LOCAL_PROOT)
                        }
                    }
                },
                enabled = !importing && canConfirm(mode, pickedUri, initialAsset, selectedConnId, sshConnections),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(FeatherIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(
                        when {
                            importing -> R.string.container_importing
                            initial == null -> R.string.common_add
                            else -> R.string.common_save
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

/** String list editor: title + per-item cards (input field + delete) + an "Add" button at the bottom, styled to match the parameter editing in the MCP dialog. */
@Composable
private fun StringListEditor(
    title: String,
    items: SnapshotStateList<String>,
    itemLabel: String,
    itemHint: String?,
    emptyText: String,
    addText: String
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface
    )
    if (items.isEmpty()) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    } else {
        items.forEachIndexed { index, value ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ContainerField(
                        value = value,
                        onValueChange = { items[index] = it },
                        label = itemLabel,
                        placeholder = itemHint,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = { items.removeAt(index) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                FeatherIcons.Trash2,
                                contentDescription = stringResource(R.string.common_delete),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
    Row {
        Surface(
            onClick = { items.add("") },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    FeatherIcons.Plus,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = addText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Mount editor: each item card has two input fields, "local directory + container directory", and a delete button; on save they are joined as `local:container`. */
@Composable
private fun MountListEditor(
    title: String,
    items: SnapshotStateList<Pair<String, String>>,
    emptyText: String,
    addText: String
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface
    )
    if (items.isEmpty()) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    } else {
        items.forEachIndexed { index, (local, container) ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ContainerField(
                        value = local,
                        onValueChange = { items[index] = it to container },
                        label = stringResource(R.string.container_mount_local),
                        placeholder = "/sdcard",
                    )
                    ContainerField(
                        value = container,
                        onValueChange = { items[index] = local to it },
                        label = stringResource(R.string.container_mount_container),
                        placeholder = "/mnt/sdcard",
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = { items.removeAt(index) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                FeatherIcons.Trash2,
                                contentDescription = stringResource(R.string.common_delete),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
    Row {
        Surface(
            onClick = { items.add("" to "") },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    FeatherIcons.Plus,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = addText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Key-value list editor: each item card contains "variable name + variable value" and a delete button, styled to match the environment variable editing in the MCP dialog. */
@Composable
private fun PairListEditor(
    title: String,
    items: SnapshotStateList<Pair<String, String>>,
    keyLabel: String,
    keyHint: String?,
    valueLabel: String,
    valueHint: String?,
    emptyText: String,
    addText: String
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface
    )
    if (items.isEmpty()) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    } else {
        items.forEachIndexed { index, (k, v) ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ContainerField(
                        value = k,
                        onValueChange = { items[index] = it to v },
                        label = keyLabel,
                        placeholder = keyHint,
                    )
                    ContainerField(
                        value = v,
                        onValueChange = { items[index] = k to it },
                        label = valueLabel,
                        placeholder = valueHint,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = { items.removeAt(index) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                FeatherIcons.Trash2,
                                contentDescription = stringResource(R.string.common_delete),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
    Row {
        Surface(
            onClick = { items.add("" to "") },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    FeatherIcons.Plus,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = addText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Constructs a ContainerProfile from the form state; returns null if validation fails (the button is already disabled, this is a fallback).
 * Local image: if a file is selected → an imported custom container (isBuiltin=false); if no file is selected and it was originally built-in → keep the built-in Asset source (isBuiltin=true).
 */
private fun buildProfile(
    initial: ContainerProfile?,
    mode: ExecutionMode,
    name: String,
    shellPath: String,
    bindings: List<String>,
    args: List<String>,
    env: Map<String, String>,
    pickedUri: String?,
    selectedConnId: String,
    remotePath: String
): ContainerProfile? {
    return when (mode) {
        ExecutionMode.LOCAL_PROOT -> {
            val assetSource = initial?.rootfsSource as? RootfsSource.Asset
            val rootfsSource = if (pickedUri != null) RootfsSource.LocalFile(pickedUri) else assetSource ?: return null
            ContainerProfile(
                id = "", // Overwritten by the caller
                name = name,
                rootfsSource = rootfsSource,
                shellPath = shellPath.ifBlank { null },
                extraBindings = bindings,
                // Locally imported images inject proot arguments by default; they follow the same path as user-added ones (extraArgs), with a consistent joining position.
                extraArgs = if (args.isEmpty() && rootfsSource is RootfsSource.LocalFile)
                    ContainerProfile.DEFAULT_PROOT_ARGS else args,
                env = env,
                isBuiltin = rootfsSource is RootfsSource.Asset,
                mode = ExecutionMode.LOCAL_PROOT
            )
        }

        ExecutionMode.REMOTE_SSH -> {
            if (selectedConnId.isBlank()) return null
            ContainerProfile(
                id = "", // Overwritten by the caller
                name = name,
                rootfsSource = RootfsSource.RemoteSsh(selectedConnId, remotePath),
                shellPath = null,
                isBuiltin = false,
                mode = ExecutionMode.REMOTE_SSH
            )
        }

        // Physical-device ROOT is not an image type; this branch will not appear in the edit dialog
        ExecutionMode.DEVICE_ROOT -> null
    }
}

/** Conditions for the save button to be enabled: a local image requires a file to be selected (when editing built-in, keeping built-in can also save), remote SSH requires a channel to be selected. */
private fun canConfirm(
    mode: ExecutionMode,
    pickedUri: String?,
    initialAsset: RootfsSource.Asset?,
    selectedConnId: String,
    sshConnections: List<RemoteConnection>
): Boolean = when (mode) {
    ExecutionMode.LOCAL_PROOT -> pickedUri != null || initialAsset != null
    ExecutionMode.REMOTE_SSH -> sshConnections.isNotEmpty() && selectedConnId.isNotBlank()
    // Physical-device ROOT is not an image type; this branch will not appear in the edit dialog
    ExecutionMode.DEVICE_ROOT -> false
}

/**
 * Copies the selected image file to the App private directory (rootfs_images/) to save a copy, and returns the copy's file uri;
 * if it is already a file uri (private copy or historical data), returns the original value without copying; on copy failure returns null (the caller falls back to the original uri).
 */
private fun copyImageToPrivate(context: Context, uriString: String, profileId: String?): String? {
    if (uriString.startsWith("file://")) return uriString
    val uri = Uri.parse(uriString)
    return runCatching {
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.let { ".$it" } ?: ""
        val destDir = File(context.filesDir, "rootfs_images").apply { mkdirs() }
        val dest = File(destDir, "import_${profileId ?: System.currentTimeMillis()}$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        Uri.fromFile(dest).toString()
    }.getOrNull()
}

/**
 * When deleting a profile, cleans up its private-directory image copy (only cleans file uri copies under rootfs_images/).
 * Skips copies with the `download_` prefix — those are image files downloaded on the "Download Image" page, and deleting a container should not also clear them,
 * otherwise the download page record would remain but the file would be gone; manually imported copies (import_*) are still cleaned up as before.
 */
private fun deleteImageCopy(profile: ContainerProfile) {
    val src = profile.rootfsSource as? RootfsSource.LocalFile ?: return
    if (!src.uri.startsWith("file://")) return
    runCatching {
        val file = File(Uri.parse(src.uri).path ?: return)
        if (file.parentFile?.name == "rootfs_images" && !file.name.startsWith("download_")) file.delete()
    }
}

/** Queries the display name of the selected image file: for a content uri uses OpenableColumns, for a file uri falls back to the last path segment. */
private fun queryImageDisplayName(context: Context, uriString: String): String? {
    val uri = Uri.parse(uriString)
    if (uri.scheme == "content") {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
    }
    return uri.lastPathSegment
}

/**
 * Unified input field style for this file: rounded corners + custom colors + full width.
 * Historically there were 19 whole-block copies; extracted into this component to eliminate duplication; scenarios needing readOnly/placeholder/trailingIcon also go through here.
 */
/** Uniformly uses the global AppTextField component. */
@Composable
private fun ContainerField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        singleLine = singleLine,
        readOnly = readOnly,
        trailingIcon = trailingIcon,
        modifier = modifier.fillMaxWidth()
    )
}
