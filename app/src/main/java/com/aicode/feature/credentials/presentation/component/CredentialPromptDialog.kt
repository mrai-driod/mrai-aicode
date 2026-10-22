package com.aicode.feature.credentials.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.dialogTextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.aicode.core.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff
import androidx.compose.ui.res.stringResource
import com.aicode.R

/**
 * Login dialog shown when pull/push lacks https credentials. host is read-only and prefilled, the user fills in username/token; confirming calls back to fill it in,
 * cancelling calls back so the upper layer gives up. This is the unified dialog for git lacking credentials on all three surfaces (UI / AI Bash / interactive terminal)——
 * both the UI path and command-line requests detected by [com.aicode.feature.credentials.data.CredentialRequestBridge] reuse this component.
 *
 * @param host Remote host (read-only prefill, from the host field of the git credential protocol)
 * @param onConfirm Returns the username/token the user filled in (already trimmed); the upper layer persists it + feeds it back to the git helper based on this. Clickable only when both text fields are non-empty.
 * @param onDismiss Cancel: the upper layer writes a cancel response, the helper exits non-zero so git reports an authentication failure (consistent with the no-credentials behaviour).
 * @param usernameHint git may already include a prefilled username in the request (such as u in the URL); when non-empty it is used as the initial value; usually empty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialPromptDialog(
    host: String,
    onConfirm: (username: String, token: String) -> Unit,
    onDismiss: () -> Unit,
    usernameHint: String? = null
) {
    var username by remember(host, usernameHint) { mutableStateOf(usernameHint ?: "") }
    var token by remember(host) { mutableStateOf("") }
    var tokenVisible by remember(host) { mutableStateOf(false) }
    val canSave = username.trim().isNotBlank() && token.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.credential_prompt_title, host)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                AppTextField(
                    value = host,
                    onValueChange = { /* host comes from the remote, read-only */ },
                    label = stringResource(R.string.credential_remote_host),
                    singleLine = true,
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogTextFieldColors()
                )
                AppTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = stringResource(R.string.common_username),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogTextFieldColors()
                )
                AppTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = stringResource(R.string.credential_token),
                    singleLine = true,
                    visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { tokenVisible = !tokenVisible }) {
                            Icon(
                                if (tokenVisible) FeatherIcons.EyeOff else FeatherIcons.Eye,
                                contentDescription = if (tokenVisible) stringResource(R.string.common_hide) else stringResource(R.string.common_show)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogTextFieldColors()
                )
                Text(
                    text = stringResource(R.string.credential_prompt_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (canSave) onConfirm(username.trim(), token) },
                enabled = canSave
            ) { Text(stringResource(R.string.credential_save_and_retry)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}
