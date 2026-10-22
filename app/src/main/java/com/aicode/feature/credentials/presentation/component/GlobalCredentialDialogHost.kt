package com.aicode.feature.credentials.presentation.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.feature.credentials.data.CredentialRequestBridge

/**
 * Global credential dialog host: subscribes to [CredentialRequestBridge.request] and renders [CredentialPromptDialog] whenever it is non-null.
 *
 * Mounted on the root Composable (covering all pages), so when command-line git lacking credentials (AI Bash / interactive terminal) triggers the dialog,
 * it can appear whether the user is currently on the chat page / terminal page / Git page——credential detection is uniformly handled by the custom credential helper inside the container,
 * regardless of which page the user is on. User confirms → [CredentialRequestBridge.respond], cancels → [CredentialRequestBridge.cancel].
 */
@Composable
fun GlobalCredentialDialogHost(bridge: CredentialRequestBridge) {
    val request by bridge.request.collectAsStateWithLifecycle()
    request?.let { req ->
        CredentialPromptDialog(
            host = req.host,
            onConfirm = { username, token ->
                bridge.respond(req.requestId, req.host, username, token)
            },
            onDismiss = { bridge.cancel(req.requestId) }
        )
    }
}
