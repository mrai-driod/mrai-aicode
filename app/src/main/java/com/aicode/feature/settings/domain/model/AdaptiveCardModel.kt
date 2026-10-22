package com.aicode.feature.settings.domain.model

/**
 * Live context environment variables passed to the panel script.
 */
data class DashboardContext(
    val model: String = "",
    val workspacePath: String = "",
    val workspaceName: String = "",
    val sessionId: String = "",
    val lastInputTokens: Int = 0,
    val lastOutputTokens: Int = 0,
    val lastCachedTokens: Int = 0,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val modelContextTokens: Int = 0,
    val modelMaxInputTokens: Int = 0,
    val modelMaxOutputTokens: Int = 0,
    val modelInputCostUsdPerM: Double = 0.0,
    val modelOutputCostUsdPerM: Double = 0.0,
    val modelCacheReadCostUsdPerM: Double = 0.0,
    val modelSupportsTools: Boolean = false,
    val modelSupportsVision: Boolean = false,
    val modelSupportsReasoning: Boolean = false,
    val messageCount: Int = 0,
    val agentState: String = "",
    val sessionMode: String = "",
    val reasoningEffort: String = "",
    val refreshReason: String = ""
)

/**
 * Adaptive Card root object.
 */
data class AdaptiveCardRoot(
    val version: String = "1.5",
    val compact: AdaptiveCardElement? = null,
    val body: List<AdaptiveCardElement> = emptyList()
)

/**
 * Semantic color definition, supporting standard semantic colors or a custom hexadecimal color.
 */
sealed interface CardColor {
    data object Default : CardColor
    data object Subtle : CardColor
    data object Accent : CardColor
    data object Good : CardColor
    data object Warning : CardColor
    data object Attention : CardColor
    data class Custom(val hex: String) : CardColor

    companion object {
        fun fromString(str: String?): CardColor {
            if (str.isNullOrBlank()) return Default
            return when (str.trim().lowercase()) {
                "default" -> Default
                "subtle" -> Subtle
                "accent" -> Accent
                "good" -> Good
                "warning" -> Warning
                "attention", "error", "danger" -> Attention
                else -> if (str.startsWith("#")) Custom(str) else Default
            }
        }
    }
}

/**
 * Container background style.
 */
enum class ContainerStyle {
    DEFAULT,
    SUBTLE,
    EMPHASIS,
    GOOD,
    WARNING,
    ATTENTION,
    ACCENT;

    companion object {
        fun fromString(str: String?): ContainerStyle {
            return when (str?.trim()?.lowercase()) {
                "subtle" -> SUBTLE
                "emphasis" -> EMPHASIS
                "good" -> GOOD
                "warning" -> WARNING
                "attention", "error", "danger" -> ATTENTION
                "accent" -> ACCENT
                else -> DEFAULT
            }
        }
    }
}

/**
 * Text size.
 */
enum class TextSize {
    MICRO,       // 10sp
    SMALL,       // 12sp
    DEFAULT,     // 14sp
    MEDIUM,      // 16sp
    LARGE,       // 18sp
    EXTRA_LARGE; // 22sp

    companion object {
        fun fromString(str: String?): TextSize {
            return when (str?.trim()?.lowercase()) {
                "micro" -> MICRO
                "small" -> SMALL
                "medium" -> MEDIUM
                "large" -> LARGE
                "extralarge", "extra_large", "xl" -> EXTRA_LARGE
                else -> DEFAULT
            }
        }
    }
}

/**
 * Text weight.
 */
enum class TextWeight {
    LIGHTER,
    DEFAULT,
    BOLDER;

    companion object {
        fun fromString(str: String?): TextWeight {
            return when (str?.trim()?.lowercase()) {
                "lighter", "light" -> LIGHTER
                "bolder", "bold", "semibold" -> BOLDER
                else -> DEFAULT
            }
        }
    }
}

/**
 * Spacing size.
 */
enum class SpacingSize {
    NONE,
    SMALL,
    MEDIUM,
    LARGE;

    companion object {
        fun fromString(str: String?): SpacingSize {
            return when (str?.trim()?.lowercase()) {
                "none" -> NONE
                "small", "sm" -> SMALL
                "large", "lg" -> LARGE
                else -> MEDIUM
            }
        }
    }
}

/**
 * Padding definition (dp).
 */
data class CardPadding(
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0
) {
    val isZero: Boolean get() = top == 0 && right == 0 && bottom == 0 && left == 0

    companion object {
        val Zero = CardPadding()
        fun all(value: Int) = CardPadding(value, value, value, value)
        fun symmetric(vertical: Int = 0, horizontal: Int = 0) = CardPadding(vertical, horizontal, vertical, horizontal)
    }
}

/**
 * Column width mode.
 */
sealed interface ColumnWidth {
    data object Auto : ColumnWidth
    data object Stretch : ColumnWidth
    data class Weighted(val weight: Float) : ColumnWidth
    data class Fixed(val dp: Int) : ColumnWidth

    companion object {
        fun fromString(str: String?): ColumnWidth {
            if (str.isNullOrBlank()) return Stretch
            val trimmed = str.trim().lowercase()
            return when (trimmed) {
                "auto" -> Auto
                "stretch" -> Stretch
                else -> {
                    if (trimmed.endsWith("dp") || trimmed.endsWith("px")) {
                        val num = trimmed.removeSuffix("dp").removeSuffix("px").trim().toIntOrNull()
                        if (num != null && num > 0) Fixed(num) else Stretch
                    } else {
                        val weight = trimmed.toFloatOrNull()
                        if (weight != null && weight > 0f) Weighted(weight) else Stretch
                    }
                }
            }
        }
    }
}

/**
 * Key-value detail item.
 */
data class FactItem(
    val title: String,
    val value: String
)

/**
 * Base interface for Adaptive Card elements.
 */
sealed interface AdaptiveCardElement

/**
 * Multi-column grid container.
 */
data class ColumnSetElement(
    val columns: List<ColumnElement> = emptyList(),
    val spacing: SpacingSize = SpacingSize.MEDIUM,
    val gapDp: Int? = null,
    val padding: CardPadding? = null,
    val margin: CardPadding? = null,
    val minHeightDp: Int? = null,
    val maxHeightDp: Int? = null,
    val horizontalAlignment: String? = null
) : AdaptiveCardElement

/**
 * A single column.
 */
data class ColumnElement(
    val width: ColumnWidth = ColumnWidth.Stretch,
    val separator: Boolean = false,
    val spacing: SpacingSize = SpacingSize.NONE,
    val gapDp: Int? = null,
    val padding: CardPadding? = null,
    val minHeightDp: Int? = null,
    val maxHeightDp: Int? = null,
    val minWidthDp: Int? = null,
    val maxWidthDp: Int? = null,
    val verticalContentAlignment: String? = null,
    val horizontalAlignment: String? = null,
    val items: List<AdaptiveCardElement> = emptyList()
) : AdaptiveCardElement

/**
 * Generic block-level container.
 */
data class ContainerElement(
    val style: ContainerStyle = ContainerStyle.DEFAULT,
    val bleed: Boolean = false,
    val spacing: SpacingSize = SpacingSize.NONE,
    val gapDp: Int? = null,
    val padding: CardPadding? = null,
    val margin: CardPadding? = null,
    val minHeightDp: Int? = null,
    val maxHeightDp: Int? = null,
    val minWidthDp: Int? = null,
    val maxWidthDp: Int? = null,
    val cornerRadiusDp: Int? = null,
    val items: List<AdaptiveCardElement> = emptyList()
) : AdaptiveCardElement

/**
 * Single-row horizontal container (often used for the compact collapsed state).
 */
data class RowElement(
    val spacing: SpacingSize = SpacingSize.SMALL,
    val gapDp: Int? = null,
    val padding: CardPadding? = null,
    val margin: CardPadding? = null,
    val minHeightDp: Int? = null,
    val maxHeightDp: Int? = null,
    val verticalAlignment: String? = null,
    val itemWeights: List<Float?> = emptyList(),
    val items: List<AdaptiveCardElement> = emptyList()
) : AdaptiveCardElement

/**
 * Text element.
 */
data class TextBlockElement(
    val text: String,
    val size: TextSize = TextSize.DEFAULT,
    val fontSizeSp: Float? = null,
    val lineHeightSp: Float? = null,
    val weight: TextWeight = TextWeight.DEFAULT,
    val color: CardColor = CardColor.Default,
    val isSubtle: Boolean = false,
    val maxLines: Int? = null,
    val verticalAlignment: String? = null,
    val horizontalAlignment: String? = null,
    val padding: CardPadding? = null,
    val margin: CardPadding? = null
) : AdaptiveCardElement

/**
 * Progress bar element.
 */
data class ProgressBarElement(
    val value: Float = 0f,
    val color: CardColor = CardColor.Accent,
    val trackColor: String? = null,
    val heightDp: Int = 6,
    val animated: Boolean = true,
    val cornerRadiusDp: Int? = null,
    val text: String? = null,
    val showPercent: Boolean = false,
    val textColor: CardColor? = null,
    val margin: CardPadding? = null
) : AdaptiveCardElement

/**
 * Combined metric block (title + large value + unit + progress bar + subtext).
 */
data class MetricElement(
    val label: String = "",
    val value: String = "",
    val unit: String = "",
    val subText: String = "",
    val percent: Float? = null,
    val color: CardColor = CardColor.Default,
    val trend: String? = null
) : AdaptiveCardElement

/**
 * Pill badge.
 */
data class BadgeElement(
    val text: String,
    val style: ContainerStyle = ContainerStyle.DEFAULT,
    val icon: String? = null
) : AdaptiveCardElement

/**
 * Small status dot.
 */
data class StatusDotElement(
    val color: CardColor = CardColor.Good,
    val sizeDp: Int = 6
) : AdaptiveCardElement

/**
 * Key-value detail list.
 */
data class FactSetElement(
    val facts: List<FactItem> = emptyList()
) : AdaptiveCardElement

/**
 * Divider.
 */
data class DividerElement(
    val spacing: SpacingSize = SpacingSize.MEDIUM
) : AdaptiveCardElement

/**
 * Explicit blank placeholder element.
 */
data class SpacerElement(
    val heightDp: Int? = null,
    val widthDp: Int? = null,
    val weight: Float? = null
) : AdaptiveCardElement

/**
 * Wrapping flow layout container.
 */
data class FlowRowElement(
    val items: List<AdaptiveCardElement> = emptyList(),
    val gapDp: Int? = null,
    val verticalGapDp: Int? = null,
    val padding: CardPadding? = null,
    val margin: CardPadding? = null,
    val minHeightDp: Int? = null,
    val maxHeightDp: Int? = null
) : AdaptiveCardElement

/**
 * Horizontal scrolling container.
 */
data class ScrollRowElement(
    val items: List<AdaptiveCardElement> = emptyList(),
    val gapDp: Int? = null,
    val padding: CardPadding? = null,
    val margin: CardPadding? = null,
    val minHeightDp: Int? = null,
    val maxHeightDp: Int? = null
) : AdaptiveCardElement

/**
 * Icon element. icon uses the built-in icon name mapping.
 */
data class ImageElement(
    val icon: String? = null,
    val sizeDp: Int = 16,
    val color: CardColor = CardColor.Default,
    val padding: CardPadding? = null,
    val margin: CardPadding? = null
) : AdaptiveCardElement

/**
 * A single tab.
 */
data class TabElement(
    val label: String,
    val icon: String? = null,
    val badge: String? = null,
    val color: CardColor? = null,
    val items: List<AdaptiveCardElement> = emptyList()
) : AdaptiveCardElement

/**
 * Tab container that shows multiple groups of content in limited space by switching tabs.
 */
data class TabSetElement(
    val tabs: List<TabElement> = emptyList(),
    val tabPosition: String? = null,
    val tabStyle: String? = null,
    val indicatorColor: CardColor? = null,
    val tabBackgroundColor: CardColor? = null,
    val tabContentColor: CardColor? = null,
    val cornerRadiusDp: Int? = null,
    val padding: CardPadding? = null,
    val margin: CardPadding? = null
) : AdaptiveCardElement

/**
 * Clickable button element that can be placed in any container. Replaces the old root-level actions.
 */
data class ActionButtonElement(
    val title: String,
    val actionType: String = "openUrl",
    val url: String? = null,
    val value: String? = null,
    val icon: String? = null,
    val style: ContainerStyle = ContainerStyle.DEFAULT,
    val color: CardColor = CardColor.Accent,
    val padding: CardPadding? = null,
    val margin: CardPadding? = null
) : AdaptiveCardElement

/**
 * Action operation.
 */
sealed interface AdaptiveCardAction {
    val title: String

    data class OpenUrl(
        override val title: String,
        val url: String,
        val icon: String? = null
    ) : AdaptiveCardAction

    data class CopyToClipboard(
        override val title: String,
        val value: String
    ) : AdaptiveCardAction
}
