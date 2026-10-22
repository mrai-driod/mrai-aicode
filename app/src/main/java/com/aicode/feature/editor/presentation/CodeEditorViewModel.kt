package com.aicode.feature.editor.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.feature.editor.data.EditorSettings
import com.aicode.feature.editor.data.EditorSettingsRepository
import com.aicode.feature.editor.domain.TextMateSetup
import com.aicode.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Editor page state. */
sealed interface EditorUiState {
    data object Loading : EditorUiState
    data class Success(val content: String, val scopeName: String?) : EditorUiState
    data class TooLarge(val sizeBytes: Long) : EditorUiState
    data object Binary : EditorUiState
    data class Error(val detail: String?) : EditorUiState
}

/** Save result, a one-shot event delivered via [CodeEditorViewModel.saveEvents]. */
sealed interface SaveResult {
    data object Success : SaveResult
    data class Error(val detail: String?) : SaveResult
}

@HiltViewModel
class CodeEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileAccess: FileAccessProvider,
    private val editorSettings: EditorSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.Loading)
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _saveEvents = Channel<SaveResult>(Channel.BUFFERED)
    val saveEvents = _saveEvents.receiveAsFlow()

    /** Whether a write to disk is in progress (in remote mode via SFTP, which takes longer; used for the UI loading spinner). */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    val settings: StateFlow<EditorSettings> = editorSettings.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorSettings())

    private var loadedPath: String? = null

    /** Calling repeatedly with the same path does not re-read from disk, so it is safe to call during Compose recomposition. */
    fun load(path: String) {
        if (loadedPath == path) return
        loadedPath = path
        _uiState.value = EditorUiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = runCatching {
                if (!fileAccess.exists(path) || !fileAccess.isFile(path)) {
                    return@runCatching EditorUiState.Error(null)
                }
                val size = fileAccess.fileSize(path)
                if (size > MAX_EDITABLE_BYTES) {
                    return@runCatching EditorUiState.TooLarge(size)
                }
                val bytes = fileAccess.readBytes(path)
                if (looksBinary(bytes)) {
                    return@runCatching EditorUiState.Binary
                }
                // Grammar bundle parsing is placed here, to ensure the registry is ready when the AndroidView factory creates the editor on the main thread.
                TextMateSetup.ensureInitialized(context)
                val scope = TextMateSetup.scopeNameFor(path)
                if (scope != null) {
                    // Warm up the grammar: constructing a grammar for the first time compiles many regexes; not doing it here would push it onto the main thread and delay the first coloring.
                    // Do not pass the object to the UI: Language is bound to the editor lifecycle, and editor.release() destroys it along with the editor;
                    // reusing a destroyed instance across rebuilds would cause problems -- here it only caches the compilation result into the registry.
                    runCatching { TextMateLanguage.create(scope, false).destroy() }
                }
                EditorUiState.Success(
                    content = bytes.toString(Charsets.UTF_8),
                    scopeName = scope
                )
            }.getOrElse { e ->
                FileLogger.w(TAG, "Failed to open file: $path", e)
                EditorUiState.Error(e.message)
            }
        }
    }

    /** Writes the editor's current content back to the file. The write happens on the IO thread, the result is notified via [saveEvents], and [saving] is set during it. */
    fun save(content: String) {
        val path = loadedPath ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _saving.value = true
            try {
                val result = runCatching { fileAccess.writeFile(path, content) }
                    .fold(
                        onSuccess = { SaveResult.Success },
                        onFailure = { e ->
                            FileLogger.w(TAG, "Failed to save file: $path", e)
                            SaveResult.Error(e.message)
                        }
                    )
                _saveEvents.send(result)
            } finally {
                _saving.value = false
            }
        }
    }

    private companion object {
        const val TAG = "CodeEditorViewModel"

        /** Fully read into memory; files larger than this are refused to avoid OOM and long stalls. */
        const val MAX_EDITABLE_BYTES = 2L * 1024 * 1024

        /** Number of sample bytes for binary sniffing: consistent with git, only looks at the leading portion. */
        const val BINARY_SNIFF_BYTES = 8000

        /** Binary determination: if a NUL byte appears within the leading sample segment it is treated as binary (text files do not contain NUL, so the false-positive rate is extremely low). */
        fun looksBinary(bytes: ByteArray): Boolean {
            val limit = minOf(bytes.size, BINARY_SNIFF_BYTES)
            for (i in 0 until limit) {
                if (bytes[i].toInt() == 0) return true
            }
            return false
        }
    }
}
