package com.aicode.feature.settings.presentation.component

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.feature.settings.presentation.UpdateCheckUiState

/**
 * Update check result dialog (rendered by the global host, shared by automatic/manual checks).
 * The new-version dialog directly lists the changelog from the current version to the latest version, scrollable.
 */
@Composable
internal fun UpdateCheckDialog(
    state: UpdateCheckUiState,
    currentVersion: String,
    onDismiss: () -> Unit,
    onOpenRelease: (tag: String) -> Unit
) {
    when (state) {
        UpdateCheckUiState.Checking -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.about_check_update)) },
            text = { Text(stringResource(R.string.about_checking_update)) },
            confirmButton = {}
        )
        UpdateCheckUiState.UpToDate -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.about_up_to_date)) },
            text = { Text(stringResource(R.string.about_up_to_date_detail, currentVersion)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_got_it)) } }
        )
        is UpdateCheckUiState.NewVersion -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.about_new_version_found)) },
            text = {
                Text(
                    text = state.changelog,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .heightIn(max = 320.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { onOpenRelease(state.latestTag) }) {
                    Text(stringResource(R.string.about_download))
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.about_later)) } }
        )
        is UpdateCheckUiState.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.about_check_failed)) },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.about_ok)) } }
        )
        UpdateCheckUiState.Idle -> {}
    }
}

/** Opens the browser with an implicit Intent, catching exceptions to avoid a crash. */
internal fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

/**
 * The GitHub Release page URL for the specified version tag; falls back to the latest stable release page when the tag is empty.
 *
 * GitHub's `releases/latest` only resolves to the latest **stable** release (pre-releases are not counted); the latest channel
 * would show an old stable version when an RC is detected, so it must go directly to the corresponding version page by tag.
 */
internal fun githubReleaseUrl(tag: String?): String = when {
    tag.isNullOrBlank() -> "$GITHUB_RELEASES_URL/latest"
    else -> "$GITHUB_RELEASES_URL/tag/${Uri.encode(tag)}"
}

private const val GITHUB_RELEASES_URL = "https://github.com/mrai-driod/mrai-aicode/releases"
