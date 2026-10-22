package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.prompt.PromptFragment
import com.aicode.feature.agent.domain.prompt.PromptFragmentCatalog
import com.aicode.feature.agent.domain.prompt.PromptFragmentSource
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Prompts page state: lists the effective fragments by number (with source), plus the built-in toggle. */
data class PromptsUiState(
    val fragments: List<PromptFragment> = emptyList(),
    val builtinDisabled: Boolean = false,
    /** When no workspace is selected the project layer is not writable; edits/creates fall back to the global layer. */
    val hasWorkspace: Boolean = false,
    val loading: Boolean = true
)

/**
 * Prompts page state: list of effective fragments from the four-level sources (project > global > local > built-in) + built-in toggle.
 *
 * All reads/writes are disk IO and run on the IO thread.
 */
@HiltViewModel
class PromptsViewModel @Inject constructor(
    private val catalog: PromptFragmentCatalog,
    private val workspaceRepository: WorkspaceRepository
) : ViewModel() {

    private val _state = MutableStateFlow(PromptsUiState())
    val state: StateFlow<PromptsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            val loaded = withContext(Dispatchers.IO) {
                PromptsUiState(
                    fragments = catalog.list(projectRoot),
                    builtinDisabled = catalog.isBuiltinDisabled(),
                    hasWorkspace = projectRoot.isNotBlank(),
                    loading = false
                )
            }
            _state.value = loaded
        }
    }

    /** Saves the override for a number (same entry point for create and edit): writes to the selected scope layer; when an edit changes the number, clears the old number. */
    fun saveFragment(
        number: Int,
        title: String,
        scope: PromptFragmentSource,
        content: String,
        previousNumber: Int? = null
    ) {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            withContext(Dispatchers.IO) {
                catalog.saveOverride(
                    number,
                    title,
                    content,
                    projectRoot,
                    target = scope,
                    previousNumber = previousNumber
                )
            }
            refresh()
        }
    }

    /** Deletes the override for a number, automatically falling back to the next layer. */
    fun deleteFragment(number: Int) {
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            withContext(Dispatchers.IO) { catalog.deleteOverride(number, projectRoot) }
            refresh()
        }
    }

    /** Drag reorder: renumbers in the new order and persists to disk, changing the injection order. Optimistically updates local state to avoid list jumping from a re-read. */
    fun reorderFragments(reordered: List<PromptFragment>) {
        val numbers = reordered.map { it.number }.sorted()
        val renumbered = reordered.mapIndexed { index, fragment -> fragment.copy(number = numbers[index]) }
        _state.update { it.copy(fragments = renumbered) }
        viewModelScope.launch {
            val projectRoot = workspaceRepository.currentPath()
            val refreshed = withContext(Dispatchers.IO) {
                if (catalog.reorder(renumbered, projectRoot)) catalog.list(projectRoot) else null
            }
            if (refreshed != null) {
                _state.update { it.copy(fragments = refreshed) }
            } else {
                refresh()
            }
        }
    }

    fun setBuiltinDisabled(disabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { catalog.setBuiltinDisabled(disabled) }
            refresh()
        }
    }
}
