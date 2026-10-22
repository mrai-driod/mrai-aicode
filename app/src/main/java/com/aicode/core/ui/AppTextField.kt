package com.aicode.core.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Radius
import com.aicode.core.theme.semanticColors

/**
 * Globally unified App text field color specification:
 * - Light mode: themed card color background + soft outer border + focused primary color highlight;
 * - Dark mode: dark surface base color (surface/surfaceVariant) + outline + focused primary color glow.
 */
@Composable
fun appTextFieldColors(
    isLight: Boolean = MaterialTheme.colorScheme.background.luminance() > 0.5f,
    unfocusedContainerColor: Color = MaterialTheme.semanticColors.cardSurface,
    focusedContainerColor: Color = if (isLight) MaterialTheme.semanticColors.cardSurface
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
): TextFieldColors {
    return OutlinedTextFieldDefaults.colors(
        unfocusedBorderColor = if (isLight) MaterialTheme.colorScheme.outlineVariant
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedContainerColor = unfocusedContainerColor,
        focusedContainerColor = focusedContainerColor,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        cursorColor = MaterialTheme.colorScheme.primary,
        focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
        unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        focusedTrailingIconColor = MaterialTheme.colorScheme.primary,
        unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        // Disabled state reuses the unfocused base color: M3 by default sets the disabled container color to transparent, so when enabled changes the background jumps between colored and transparent,
        // making the text field look like it is flickering. Disabled semantics are conveyed by the lighter border and text.
        disabledContainerColor = unfocusedContainerColor,
        disabledBorderColor = if (isLight) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    )
}

/**
 * Text field colors inside dialogs (containers such as AlertDialog that carry their own themed background): the container is fully transparent, so the dialog's own color shows straight through.
 *
 * The dialog background varies with the theme, and putting the card white background used on normal pages into such a dialog would look like a piece of white paper pasted on.
 */
@Composable
fun dialogTextFieldColors(): TextFieldColors = appTextFieldColors(
    unfocusedContainerColor = Color.Transparent,
    focusedContainerColor = Color.Transparent
)

/**
 * Globally unified basic text field component:
 * Uses a 12dp rounded-corner card feel, automatically adapts to light pure white / dark deep blue backgrounds, and supports leading/trailing icons and full keyboard configuration.
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    label: String? = null,
    placeholder: String? = null,
    labelComposable: (@Composable () -> Unit)? = null,
    placeholderComposable: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    shape: Shape = RoundedCornerShape(Radius.mdLarge),
    colors: TextFieldColors = appTextFieldColors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    val actualLabel: (@Composable () -> Unit)? = labelComposable ?: label?.let { { Text(it) } }
    val actualPlaceholder: (@Composable () -> Unit)? = placeholderComposable ?: placeholder?.let { { Text(it) } }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = actualLabel,
        placeholder = actualPlaceholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = shape,
        colors = colors
    )
}
