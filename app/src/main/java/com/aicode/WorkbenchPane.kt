package com.aicode

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.aicode.feature.credentials.presentation.CredentialViewModel
import com.aicode.feature.credentials.presentation.component.CredentialScreen
import com.aicode.feature.editor.presentation.CodeEditorScreen
import com.aicode.feature.browser.presentation.BrowserScreen
import com.aicode.feature.browser.presentation.BrowserViewModel
import com.aicode.feature.git.presentation.GitViewModel
import com.aicode.feature.git.presentation.component.GitScreen
import com.aicode.feature.terminal.presentation.TerminalViewModel
import com.aicode.feature.terminal.presentation.component.TerminalScreen

/** The content currently hosted in the large-screen right pane (workbench). */
internal enum class WorkbenchPaneKind { NONE, EDITOR, TERMINAL, GIT, BROWSER }

/**
 * The content of the right pane shown alongside chat on large screens.
 *
 * The pages themselves are standalone "parameters + callbacks" components; here we simply wire their back action to "close the right pane";
 * in narrow windows these pages still use the full-screen NavHost routes, and both paths share the same UI code.
 *
 * The ViewModels are attached to the chat page's NavBackStackEntry, so switching the right pane between its content types does not destroy them -
 * the terminal session itself is owned by the singleton TerminalSessionManager and is unrelated to this scope.
 */
@Composable
internal fun WorkbenchPaneContent(
    kind: WorkbenchPaneKind,
    editorPath: String,
    editorLine: Int,
    onClose: () -> Unit,
    onOpenFile: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        when (kind) {
            WorkbenchPaneKind.NONE -> Unit

            WorkbenchPaneKind.EDITOR -> CodeEditorScreen(
                path = editorPath,
                initialLine = editorLine,
                onBack = onClose,
                embedded = true
            )

            WorkbenchPaneKind.TERMINAL -> {
                val terminalViewModel: TerminalViewModel = hiltViewModel()
                TerminalScreen(
                    viewModel = terminalViewModel,
                    onNavigateBack = onClose,
                    embedded = true
                )
            }

            WorkbenchPaneKind.GIT -> {
                // The credentials page pushes one level inside the right pane instead of using a full-screen route, otherwise on large screens the layout would suddenly jump from two panes to full screen.
                var showCredentials by rememberSaveable { mutableStateOf(false) }
                if (showCredentials) {
                    val credentialViewModel: CredentialViewModel = hiltViewModel()
                    CredentialScreen(
                        viewModel = credentialViewModel,
                        onNavigateBack = { showCredentials = false }
                    )
                } else {
                    val gitViewModel: GitViewModel = hiltViewModel()
                    GitScreen(
                        viewModel = gitViewModel,
                        onNavigateToCredentials = { showCredentials = true },
                        onNavigateBack = onClose,
                        onOpenFile = onOpenFile,
                        embedded = true
                    )
                }
            }
            WorkbenchPaneKind.BROWSER -> {
                val browserViewModel: BrowserViewModel = hiltViewModel()
                BrowserScreen(
                    browserManager = browserViewModel.browserManager,
                    onNavigateBack = onClose,
                    embedded = true
                )
            }
        }
    }
}
