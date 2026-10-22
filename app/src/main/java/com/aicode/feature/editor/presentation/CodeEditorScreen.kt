package com.aicode.feature.editor.presentation

import android.graphics.Typeface
import android.view.ViewGroup
import android.util.TypedValue
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.editor.data.EditorSettings
import com.aicode.feature.editor.domain.TextMateSetup
import com.aicode.feature.editor.presentation.component.MarkdownPreviewWebView
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ChevronLeft
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Code
import compose.icons.feathericons.Eye
import compose.icons.feathericons.Save
import compose.icons.feathericons.Settings
import compose.icons.feathericons.X
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.text.UndoManager
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlin.math.abs

/**
 * Standalone full-screen code editing page. Supports syntax highlighting, undo/redo,
 * a bottom symbol shortcut bar, and saving.
 * Saving overwrites the file directly, with no external concurrent-write detection --
 * AI tools or the terminal may modify the same file at the same time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorScreen(
    path: String,
    onBack: () -> Unit,
    initialLine: Int = 0,
    embedded: Boolean = false,
    viewModel: CodeEditorViewModel = hiltViewModel()
) {
    LaunchedEffect(path) { viewModel.load(path) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val editorRef = remember { mutableStateOf<CodeEditor?>(null) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    // The saved (or just loaded) content. The dirty flag is determined by the difference from it, so undoing back to the original can reset it automatically.
    val baselineText = remember { mutableStateOf("") }
    var pendingSaveText by remember { mutableStateOf("") }
    var pendingExit by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var previewMode by remember { mutableStateOf(false) }
    var cursorLine by remember { mutableStateOf(1) }
    var cursorColumn by remember { mutableStateOf(1) }
    val isMarkdown = remember(path) {
        path.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")
    }
    var editorBackground by remember { mutableStateOf<Color?>(null) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()

    // Resets editing state when switching files, to avoid the previous file's undo/dirty state carrying over to the new file.
    LaunchedEffect(path) {
        canUndo = false
        canRedo = false
        dirty = false
        pendingExit = false
        cursorLine = 1
        cursorColumn = 1
        previewMode = false
    }

    val context = LocalContext.current
    val savedText = stringResource(R.string.editor_save_success)
    val saveFailedText = stringResource(R.string.editor_save_failed)
    LaunchedEffect(Unit) {
        viewModel.saveEvents.collect { result ->
            when (result) {
                is SaveResult.Success -> {
                    baselineText.value = pendingSaveText
                    dirty = false
                    Toast.makeText(context, savedText, Toast.LENGTH_SHORT).show()
                    if (pendingExit) {
                        pendingExit = false
                        onBack()
                    }
                }
                is SaveResult.Error -> {
                    pendingExit = false
                    Toast.makeText(
                        context,
                        result.detail ?: saveFailedText,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun requestSave() {
        val editor = editorRef.value ?: return
        pendingSaveText = editor.text.toString()
        viewModel.save(pendingSaveText)
    }

    fun handleBack() {
        if (dirty) showUnsavedDialog = true else onBack()
    }

    BackHandler(enabled = !showSettings && !previewMode) { handleBack() }
    BackHandler(enabled = showSettings) { showSettings = false }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = {},
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    ),
                    navigationIcon = {
                        IconButton(onClick = { handleBack() }) {
                            Icon(
                                if (embedded) FeatherIcons.X else FeatherIcons.ArrowLeft,
                                contentDescription = stringResource(
                                    if (embedded) R.string.common_close else R.string.common_back
                                )
                            )
                        }
                    },
                    actions = {
                        val editable = state is EditorUiState.Success
                        if (isMarkdown && editable) {
                            IconButton(onClick = { previewMode = !previewMode }) {
                                Icon(
                                    if (previewMode) FeatherIcons.Code else FeatherIcons.Eye,
                                    contentDescription = stringResource(
                                        if (previewMode) R.string.editor_md_show_source
                                        else R.string.editor_md_preview
                                    )
                                )
                            }
                        }
                        IconButton(
                            onClick = { editorRef.value?.undo() },
                            enabled = editable && canUndo
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                contentDescription = stringResource(R.string.editor_undo)
                            )
                        }
                        IconButton(
                            onClick = { editorRef.value?.redo() },
                            enabled = editable && canRedo
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Redo,
                                contentDescription = stringResource(R.string.editor_redo)
                            )
                        }
                        IconButton(
                            onClick = { requestSave() },
                            enabled = editable && dirty && !saving
                        ) {
                            if (saving) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    FeatherIcons.Save,
                                    contentDescription = stringResource(R.string.common_save)
                                )
                            }
                        }
                        IconButton(
                            onClick = { showSettings = true },
                            enabled = editable
                        ) {
                            Icon(
                                FeatherIcons.Settings,
                                contentDescription = stringResource(R.string.editor_settings)
                            )
                        }
                    }
                )
                HorizontalDivider(thickness = 0.5.dp)
                FileTitleBar(
                    fileName = path.substringAfterLast('/'),
                    dirty = dirty,
                    line = cursorLine,
                    column = cursorColumn
                )
                HorizontalDivider()
            }
        },
        bottomBar = {
            if (state is EditorUiState.Success && !previewMode) {
                EditorSymbolBar(
                    backgroundColor = editorBackground ?: MaterialTheme.colorScheme.background,
                    onInsert = { editorRef.value?.commitText(it) },
                    onIndent = { editorRef.value?.commitText("\t") },
                    onMoveLeft = { editorRef.value?.let { moveCursorHorizontally(it, forward = false) } },
                    onMoveRight = { editorRef.value?.let { moveCursorHorizontally(it, forward = true) } }
                )
            }
        }
    ) { padding ->
        val content = Modifier
            .fillMaxSize()
            .padding(padding)
        when (val s = state) {
            is EditorUiState.Loading -> CenterBox(content) { CircularProgressIndicator() }
            is EditorUiState.Success -> Box(modifier = content) {
                EditorSurface(
                    state = s,
                    modifier = Modifier.fillMaxSize(),
                    editorRef = editorRef,
                    settings = settings,
                    baselineText = baselineText,
                    initialLine = initialLine,
                    onBackgroundResolved = { editorBackground = it },
                    onCursorChanged = { l, c ->
                        cursorLine = l
                        cursorColumn = c
                    },
                    onContentChanged = { undo, redo, changed ->
                        canUndo = undo
                        canRedo = redo
                        dirty = changed
                    }
                )
                if (previewMode) {
                    // The preview overlays on top of the editor (the editor stays in the composition and is not destroyed, so switching back to source does not lose state).
                    // When entering preview, snapshot the editor's current text to ensure unsaved edits are reflected.
                    val previewText = remember(previewMode) {
                        editorRef.value?.text?.toString() ?: s.content
                    }
                    MarkdownPreviewWebView(
                        text = previewText,
                        path = path,
                        onExitPreview = { previewMode = false },
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    )
                }
            }
            is EditorUiState.TooLarge -> CenterBox(content) {
                HintText(
                    stringResource(
                        R.string.editor_file_too_large,
                        s.sizeBytes / 1024 / 1024
                    )
                )
            }
            is EditorUiState.Binary -> CenterBox(content) {
                HintText(stringResource(R.string.editor_binary_unsupported))
            }
            is EditorUiState.Error -> CenterBox(content) {
                HintText(s.detail ?: stringResource(R.string.editor_load_failed))
            }
        }
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(stringResource(R.string.editor_unsaved_title)) },
            text = { Text(stringResource(R.string.editor_unsaved_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    pendingExit = true
                    requestSave()
                }) { Text(stringResource(R.string.editor_save_and_exit)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    onBack()
                }) { Text(stringResource(R.string.editor_dont_save)) }
            }
        )
    }

    if (showSettings) {
        EditorSettingsScreen(onBack = { showSettings = false })
    }
    }
}

/** Small file name bar below the navigation bar: file name on the left (with an asterisk prefix when unsaved), line:column and file encoding on the right. */
@Composable
private fun FileTitleBar(fileName: String, dirty: Boolean, line: Int, column: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .height(FILE_TITLE_BAR_HEIGHT)
            .padding(horizontal = FILE_TITLE_HORIZONTAL_PADDING),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dirty) {
            Text(
                text = "*",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 2.dp)
            )
        }
        Text(
            text = fileName,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$line:$column",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.sm)
        )
        Text(
            text = FILE_ENCODING,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.md)
        )
    }
}

@Composable
private fun EditorSurface(
    state: EditorUiState.Success,
    modifier: Modifier,
    editorRef: MutableState<CodeEditor?>,
    settings: EditorSettings,
    baselineText: MutableState<String>,
    initialLine: Int = 0,
    onBackgroundResolved: (Color) -> Unit,
    onCursorChanged: (line: Int, column: Int) -> Unit,
    onContentChanged: (canUndo: Boolean, canRedo: Boolean, dirty: Boolean) -> Unit
) {
    // Stays consistent with the Compose theme actually rendered, rather than following the system setting -- the theme can be switched separately within the app.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val themeSurface = MaterialTheme.colorScheme.surface
    // sora's grammar analysis runs on a background thread, so the first frame is inevitably not yet colored. Use a single fade-in to cover this window,
    // turning the "plain text first, then suddenly colored" jump into a gradual content reveal.
    var revealed by remember(state.content) { mutableStateOf(false) }
    LaunchedEffect(state.content) { revealed = true }
    val contentAlpha by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(durationMillis = HIGHLIGHT_REVEAL_MS),
        label = "editor-reveal"
    )

    // After the user has scrolled themselves, no longer automatically jump to the target line. The View is created only once, so the flag must be held in
    // instance-stable State (the factory closure captures the first instance), and is explicitly reset by the effect below when the file changes.
    val scrolledByUser = remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier.graphicsLayer { alpha = contentAlpha },
        factory = { ctx ->
            TextMateSetup.applyTheme(dark)
            // By default sora merges continuous input within 8 seconds into a single undo record, so one undo would swallow an entire stretch of input.
            UndoManager.setMergeTimeLimit(UNDO_MERGE_WINDOW_MS)
            CodeEditor(ctx).apply {
                // sora's getScrollMaxY() skips verticalExtraSpaceFactor when LayoutParams is null or the height is WRAP_CONTENT,
                // so the extra scroll space at the bottom (the half-screen blank space set on the line below) would be lost.
                // Compose's AndroidView uses the default WRAP_CONTENT when adding child Views, so it must be changed explicitly.
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isEditable = true
                typefaceText = Typeface.MONOSPACE
                setTextSize(settings.fontSizeSp.toFloat())
                setWordwrap(settings.wordWrap)
                // Disables the cursor movement animation: when switching line/column, the current line highlight disappears in place and appears at the target position, without sliding line by line.
                isCursorAnimationEnabled = false
                // Reserves a fixed small margin to the left of line numbers, to keep digits from touching the edge.
                setLineNumberMarginLeft(spToPx(ctx, LINE_NUMBER_MARGIN_SP))
                // After scrolling to the bottom, can still scroll up a bit further: the extra viewport space is half a screen (sora's default value, explicitly pinned).
                verticalExtraSpaceFactor = VERTICAL_EXTRA_SPACE_FACTOR
                isBlockLineEnabled = settings.showIndentGuide
                nonPrintablePaintingFlags =
                    nonPrintableFlags(settings.showWhitespace, settings.showWrapArrow)
                colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                applyThemedBackground(this, themeSurface)
                applyLineNumberBackground(this, dark)
                state.scopeName?.let { scope ->
                    setEditorLanguage(TextMateLanguage.create(scope, false))
                }
                // Call setText before subscribing: the initial set does not count toward the dirty flag; only subsequent user edits trigger it.
                setText(state.content)
                val editorView = this
                subscribeAlways(ContentChangeEvent::class.java) {
                    // UndoManager changes the text first, then moves the stack pointer, so reading canUndo/canRedo in the same frame
                    // inside the event callback returns the old values from before the move (after undo the redo button does not light up, after undoing to the bottom the undo button still lights up); deferring by one frame makes it accurate.
                    editorView.post {
                        val baseline = baselineText.value
                        val text = editorView.text
                        onContentChanged(
                            editorView.canUndo(),
                            editorView.canRedo(),
                            text.length != baseline.length || text.toString() != baseline
                        )
                    }
                }
                subscribeAlways(SelectionChangeEvent::class.java) {
                    onCursorChanged(cursor.leftLine + 1, cursor.leftColumn + 1)
                }
                subscribeAlways(ScrollEvent::class.java) { event ->
                    // After a size change, sora performs a scroll clamp via touchHandler, and the event cause is likewise
                    // CAUSE_USER_DRAG but with identical start and end coordinates (y=0->0). Right after the editor opens it fires several such
                    // zero-displacement events in a row; looking only at the cause would treat them as "user has manually scrolled" and give up on line-number positioning.
                    val moved = event.startY != event.endY || event.startX != event.endX
                    if (moved &&
                        (event.cause == ScrollEvent.CAUSE_USER_DRAG ||
                            event.cause == ScrollEvent.CAUSE_USER_FLING)
                    ) {
                        scrolledByUser.value = true
                    }
                }
                editorRef.value = this
            }
        },
        onRelease = {
            editorRef.value = null
            it.release()
        }
    )

    LaunchedEffect(state.content) { baselineText.value = state.content }

    // When the light/dark theme switches, reset the color scheme instead of rebuilding the editor -- preserving unsaved content and the undo stack.
    val editor = editorRef.value
    LaunchedEffect(dark, themeSurface, editor) {
        editor ?: return@LaunchedEffect
        TextMateSetup.applyTheme(dark)
        editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
        applyThemedBackground(editor, themeSurface)
        applyLineNumberBackground(editor, dark)
        editor.invalidate()
        onBackgroundResolved(Color(editor.colorScheme.getColor(EditorColorScheme.WHOLE_BACKGROUND)))
    }
    LaunchedEffect(settings.fontSizeSp, editor) {
        editor?.setTextSize(settings.fontSizeSp.toFloat())
    }
    LaunchedEffect(settings.wordWrap, editor) {
        editor?.setWordwrap(settings.wordWrap)
    }
    // When opened from a chat-area link with a line number, jump to the target line and scroll it into the visible area (line numbers are 1-based, sora is 0-based).
    //
    // Must wait for the editor to complete its first layout: ensurePositionVisible calculates the scroll amount from getHeight(), while onSizeChanged
    // clamps the scroll offset to the new size and, with word wrap, also rebuilds the entire layout -- a jump made while width/height are still 0 would be erased by the subsequent
    // first layout, manifesting as "stays at the start of the file and never jumped".
    //
    // Font size and word wrap are read from DataStore, and the real values often arrive later than the first frame; setTextSize / setWordwrap likewise rebuild the layout
    // and disturb the position, so they are also included in the key to reposition once; after the user has scrolled themselves (scrolledByUser), do not pull back to the target line again.
    LaunchedEffect(state.content, initialLine) { scrolledByUser.value = false }
    LaunchedEffect(editor, initialLine, state.content, settings.wordWrap, settings.fontSizeSp) {
        val editorView = editor ?: return@LaunchedEffect
        if (initialLine <= 0 || scrolledByUser.value) return@LaunchedEffect
        var frames = 0
        while ((editorView.width == 0 || editorView.height == 0) &&
            frames < LINE_JUMP_LAYOUT_WAIT_FRAMES
        ) {
            withFrameNanos { }
            frames++
        }
        if (editorView.width == 0 || editorView.height == 0 || scrolledByUser.value) {
            return@LaunchedEffect
        }
        val line = (initialLine - 1).coerceIn(0, (editorView.lineCount - 1).coerceAtLeast(0))
        // After positioning, verify that the target line really stopped at the expected position, and retry if it did not: layout rebuilds (late-arriving settings, grammar
        // analysis completion, word-wrap reflow) can wash away the position just set, so a single positioning attempt is not enough.
        var attempt = 0
        while (attempt < LINE_JUMP_MAX_ATTEMPTS && !scrolledByUser.value) {
            editorView.setSelection(line, 0)
            val desired = centerScrollOffset(editorView, line)
            if (desired != null) {
                scrollVerticallyTo(editorView, desired)
            } else {
                // When layout information is unavailable, fall back to the official "ensure visible only" behaviour, without animation to avoid being interrupted mid-way by a layout rebuild.
                editorView.ensurePositionVisible(line, 0, true)
            }
            repeat(LINE_JUMP_VERIFY_FRAMES) { withFrameNanos { } }
            val settled = if (desired != null) {
                abs(editorView.offsetY - desired) <= editorView.rowHeight
            } else {
                runCatching {
                    val layout = editorView.layout
                    line in layout.getLineNumberForRow(editorView.firstVisibleRow)..
                        layout.getLineNumberForRow(editorView.lastVisibleRow)
                }.getOrDefault(true)
            }
            if (settled) break
            attempt++
        }
    }
    LaunchedEffect(settings.showIndentGuide, editor) {
        editor?.isBlockLineEnabled = settings.showIndentGuide
    }
    LaunchedEffect(settings.showWhitespace, settings.showWrapArrow, editor) {
        editor?.nonPrintablePaintingFlags =
            nonPrintableFlags(settings.showWhitespace, settings.showWrapArrow)
    }
}

@Composable
private fun EditorSymbolBar(
    backgroundColor: Color,
    onInsert: (String) -> Unit,
    onIndent: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit
) {
    Surface(
        color = backgroundColor,
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SymbolKey(onClick = onMoveLeft) {
                Icon(
                    FeatherIcons.ChevronLeft,
                    contentDescription = stringResource(R.string.editor_cursor_left)
                )
            }
            SymbolKey(onClick = onMoveRight) {
                Icon(
                    FeatherIcons.ChevronRight,
                    contentDescription = stringResource(R.string.editor_cursor_right)
                )
            }
            SymbolKey(onClick = onIndent) {
                Text(
                    text = stringResource(R.string.editor_indent),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            EDITOR_SYMBOLS.forEach { symbol ->
                SymbolKey(onClick = { onInsert(symbol) }) {
                    Text(
                        text = symbol,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun SymbolKey(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(Spacing.sm))
            .clickable(onClick = onClick)
            .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
            .padding(horizontal = Spacing.sm),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun CenterBox(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = Spacing.xl)
    )
}

/**
 * Blends the current theme's surface color into the editor's background color.
 *
 * The TextMate theme backgrounds (Dark+'s #1E1E1E, Light+'s pure white) follow VSCode's neutral tones;
 * using them as-is would make the editor stand out noticeably from the rest of the UI under a color-temperature theme. Retain ten percent of the original color to preserve the editing area's tone.
 */
private fun applyThemedBackground(editor: CodeEditor, themeSurface: Color) {
    val scheme = editor.colorScheme
    val base = Color(scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND))
    scheme.setColor(
        EditorColorScheme.WHOLE_BACKGROUND,
        lerp(base, themeSurface, EDITOR_THEME_TINT).toArgb()
    )
}

private fun applyLineNumberBackground(editor: CodeEditor, dark: Boolean) {
    val scheme = editor.colorScheme
    val base = Color(scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND))
    val target = if (dark) Color.White else Color.Black
    scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, lerp(base, target, 0.08f).toArgb())
}

/** Creates a slight brightness difference between the line-number gutter background and the editing area for easier distinction. Adapts based on the editor background color and follows the theme. */

/** Converts sp to pixels, for sora APIs that need px such as the line-number left margin. */
private fun spToPx(context: android.content.Context, sp: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)

/** Combines sora's non-printable character drawing flags from the "show whitespace" and "show wrap arrow" toggles. */
private fun nonPrintableFlags(showWhitespace: Boolean, showWrapArrow: Boolean): Int {
    var flags = 0
    if (showWhitespace) {
        flags = flags or CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or
            CodeEditor.FLAG_DRAW_WHITESPACE_INNER or
            CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING or
            CodeEditor.FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE or
            CodeEditor.FLAG_DRAW_LINE_SEPARATOR
    }
    if (showWrapArrow) {
        flags = flags or CodeEditor.FLAG_DRAW_SOFT_WRAP
    }
    return flags
}

/** Symbol bar direction keys: moves the cursor horizontally by one character, wrapping to the adjacent line when crossing the start/end of a line. */
private fun moveCursorHorizontally(editor: CodeEditor, forward: Boolean) {
    val cursor = editor.cursor
    val line = cursor.leftLine
    val column = cursor.leftColumn
    if (forward) {
        val lineLength = editor.text.getColumnCount(line)
        when {
            column < lineLength -> editor.setSelection(line, column + 1)
            line < editor.lineCount - 1 -> editor.setSelection(line + 1, 0)
        }
    } else {
        when {
            column > 0 -> editor.setSelection(line, column - 1)
            line > 0 -> editor.setSelection(line - 1, editor.text.getColumnCount(line - 1))
        }
    }
}

/** Common symbols for the bottom shortcut bar; tapping inserts at the cursor. */
private val EDITOR_SYMBOLS = listOf(
    "{", "}", "(", ")", "[", "]", "<", ">",
    "=", "+", "-", "*", "/", "\\",
    ";", ":", ",", ".", "_", "\"", "'", "`",
    "|", "&", "!", "?", "@", "#", "\$", "%"
)

/** Content fade-in duration: leaves a window for background grammar analysis, without making opening feel slow to the user. */
private const val HIGHLIGHT_REVEAL_MS = 200

/** Undo merge window: input separated by more than this is no longer merged into the previous undo record, so undo is stepwise. sora's default is 8000ms. */
private const val UNDO_MERGE_WINDOW_MS = 500L

/** Reserved margin to the left of line numbers (sp). */
private const val LINE_NUMBER_MARGIN_SP = 2f

/** File name bar height and horizontal padding. */
private val FILE_TITLE_BAR_HEIGHT = 16.dp
private val FILE_TITLE_HORIZONTAL_PADDING = 12.dp

/** File reads/writes uniformly use UTF-8; used for display on the right side of the bar. */
private const val FILE_ENCODING = "UTF-8"

/** Vertical extra viewport space factor (proportion of the editor height, range [0,1]), used for overscroll at the bottom. */
private const val VERTICAL_EXTRA_SPACE_FACTOR = 0.5f

/** Proportion by which the editor background color moves toward the theme surface color. */
private const val EDITOR_THEME_TINT = 0.9f

/** Maximum number of frames to wait for the editor to complete its first layout when opened with a line number, to avoid waiting forever if width/height never become ready. */
private const val LINE_JUMP_LAYOUT_WAIT_FRAMES = 30

/** Number of retries for verifying after positioning whether the target line stopped at the expected position, and the number of frames to wait before each verification. */
private const val LINE_JUMP_MAX_ATTEMPTS = 8
private const val LINE_JUMP_VERIFY_FRAMES = 3

/**
 * The vertical scroll amount needed to center the target line (already clamped to the scrollable range, naturally hugging the edge near the start/end of the file).
 * Returns null when the layout is not yet available, and the caller falls back to the official "ensure visible only" behaviour.
 */
private fun centerScrollOffset(editor: CodeEditor, line: Int): Int? = runCatching {
    // The [0] returned by getCharLayoutOffset is the bottom y of the row containing that line.
    val rowBottom = editor.layout.getCharLayoutOffset(line, 0)[0]
    val rowTop = rowBottom - editor.rowHeight
    (rowTop - (editor.height - editor.rowHeight) / 2f).toInt()
        .coerceIn(0, editor.scrollMaxY.coerceAtLeast(0))
}.getOrNull()

/** Lands on the specified vertical scroll amount without animation, using the same approach as the no-animation branch of sora's internal ensurePositionVisible. */
private fun scrollVerticallyTo(editor: CodeEditor, offsetY: Int) {
    val scroller = editor.scroller
    scroller.startScroll(editor.offsetX, editor.offsetY, 0, offsetY - editor.offsetY, 0)
    scroller.abortAnimation()
    editor.invalidate()
}
