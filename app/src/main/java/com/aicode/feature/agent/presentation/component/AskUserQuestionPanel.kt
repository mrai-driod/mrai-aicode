package com.aicode.feature.agent.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.tool.question.PendingUserQuestion
import com.aicode.feature.agent.domain.tool.question.QuestionItem
import com.aicode.feature.agent.domain.tool.question.SingleAnswer
import com.aicode.feature.agent.domain.tool.question.UserQuestionAnswer
import androidx.compose.ui.res.stringResource
import com.aicode.R
import com.aicode.core.ui.ExpandableChevronIcon
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp


/** Internal sentinel for the "Other" option in the selected set: uses a control-character prefix the AI can never emit, avoiding collisions with preset option labels. */
private const val OTHER_SENTINEL = "\u0000__other__"

/**
 * Panel where the AI asks the user questions: shows 1-4 structured questions, each with 2-4 preset options + an "Other" free-text option.
 *
 * Style matches [ToolPermissionPanel]: an inline Surface, no AlertDialog.
 *
 * @param question the question request awaiting an answer.
 * @param onConfirm called with the answers after the user taps Confirm.
 * @param onSkip the user taps "Add details", returning empty answers - meaning the user wants to add an explanation rather than choose among the preset options.
 */
@Composable
fun AskUserQuestionPanel(
    question: PendingUserQuestion,
    onConfirm: (UserQuestionAnswer) -> Unit,
    onSkip: () -> Unit,
    forceCollapse: Boolean = false
) {
    // The set of selected labels for each question
    val selectedMap = remember(question.id) {
        mutableStateMapOf<Int, MutableList<String>>().apply {
            question.questions.forEachIndexed { idx, _ -> this[idx] = mutableStateListOf() }
        }
    }
    // The "Other" free text for each question
    val customTexts = remember(question.id) {
        mutableStateMapOf<Int, String>().apply {
            question.questions.forEachIndexed { idx, _ -> this[idx] = "" }
        }
    }
    // Panel collapsed state: expanded by default; with many questions the long panel can be collapsed so it does not block the AI output
    var expanded by remember(question.id) { mutableStateOf(true) }
    // Collapse this panel in the same frame when the balance panel expands, avoiding the two stacking up and pushing the input box open
    val effectiveExpanded = expanded && !forceCollapse

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.md),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md)
        ) {
            // Panel title: tap to collapse/expand
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.ask_confirm_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "askUserQuestion",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(Spacing.xs))
                ExpandableChevronIcon(
                    expanded = expanded,
                    contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 18.dp
                )
            }

            AnimatedVisibility(
                visible = effectiveExpanded,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(180))
            ) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Spacer(Modifier.height(Spacing.md))

                    // Render each question in turn
                    question.questions.forEachIndexed { idx, q ->
                        if (idx > 0) Spacer(Modifier.height(Spacing.md))
                        QuestionCard(
                            item = q,
                            selected = selectedMap[idx] ?: mutableListOf(),
                            customText = customTexts[idx] ?: "",
                            onSelectionChanged = { newSelection ->
                                selectedMap[idx] = newSelection.toMutableList() as MutableList<String>
                            },
                            onCustomTextChanged = { customTexts[idx] = it }
                        )
                    }

                    Spacer(Modifier.height(Spacing.md))

                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        AgentActionButton(
                            text = stringResource(R.string.ask_supplement),
                            onClick = onSkip,
                            modifier = Modifier.weight(1f),
                            tone = AgentActionTone.Neutral
                        )
                        AgentActionButton(
                            text = stringResource(R.string.ask_confirm),
                            onClick = {
                                val answers = question.questions.mapIndexed { i, q ->
                                    val sel = selectedMap[i] ?: emptyList<String>()
                                    val custom = customTexts[i]?.takeIf { it.isNotBlank() && OTHER_SENTINEL in sel }
                                    SingleAnswer(
                                        question = q.question,
                                        selected = sel.filter { it != OTHER_SENTINEL },
                                        customText = custom
                                    )
                                }
                                onConfirm(UserQuestionAnswer(answers))
                            },
                            modifier = Modifier.weight(1f),
                            tone = AgentActionTone.Success
                        )
                    }
                }
            }
        }
    }
}

/**
 * Card area for a single question.
 */
@Composable
private fun QuestionCard(
    
    item: QuestionItem,
    selected: List<String>,
    customText: String,
    onSelectionChanged: (List<String>) -> Unit,
    onCustomTextChanged: (String) -> Unit
) {
    Column {
        // Title row: header tag + question text
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.header.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(Radius.xs)
                ) {
                    Text(
                        text = item.header,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(
                text = item.question,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(Spacing.sm))

        // Option list: preset options + an appended "Other" free-input item (marked internally with the sentinel, avoiding conflicts with a same-named option passed in by the AI)
        val allOptions = item.options.map { it.label } + OTHER_SENTINEL

        allOptions.forEachIndexed { optIdx, label ->
            val isOther = label == OTHER_SENTINEL
            val isSelected = label in selected
            val description = if (!isOther) {
                item.options.getOrNull(optIdx)?.description ?: ""
            } else {
                stringResource(R.string.ask_custom_answer)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.xs))
                    .clickable {
                        val newSelection = if (item.multiSelect) {
                            if (isSelected) selected - label else selected + label
                        } else {
                            listOf(label)
                        }
                        onSelectionChanged(newSelection)
                    }
                    .padding(vertical = Spacing.xs, horizontal = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.multiSelect) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { checked ->
                            val newSelection = if (checked) selected + label else selected - label
                            onSelectionChanged(newSelection)
                        },
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onSelectionChanged(listOf(label)) },
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isOther) stringResource(R.string.common_other) else label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                    if (description.isNotBlank()) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // When "Other" is selected, expand the text input box
            if (isOther && isSelected) {
                Spacer(Modifier.height(Spacing.xs))
                TextField(
                    value = customText,
                    onValueChange = onCustomTextChanged,
                    placeholder = { Text(stringResource(R.string.ask_input_hint), style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 28.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(Radius.sm)
                )
            }
        }
    }


}
