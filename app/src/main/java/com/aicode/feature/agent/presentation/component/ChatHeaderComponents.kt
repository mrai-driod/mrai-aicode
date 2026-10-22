package com.aicode.feature.agent.presentation.component

import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.onboarding.domain.OnboardingStep
import com.aicode.feature.onboarding.presentation.onboardingTarget
import com.aicode.feature.settings.presentation.component.ModelLogoIcon
import compose.icons.FeatherIcons
import compose.icons.feathericons.GitBranch
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Menu
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Terminal

/** Title bar content height (excluding the status bar): 52dp is enough for one line of title + one line of model name and 48dp touch icons. */
internal const val CHAT_HEADER_HEIGHT_DP = 52

/** Extra height when remote mode adds a "connection status + token stats" row. */
internal const val CHAT_HEADER_CONNECTION_ROW_DP = 28

/** Reserved message-list height for the collapsed to-do overlay below the title bar: about 40dp for the single-line summary + 8dp spacing from the message stream. */
internal const val CHAT_TODO_BAR_RESERVE_DP = 48

/**
 * Top bar: a floating glass layer.
 *
 * Key point: **it no longer occupies layout height**. The caller overlays it on top of the chat content with a Box align(TopCenter),
 * and the message list only reserves first-screen space via contentPadding, so when scrolling the content passes behind the glass -
 * that is what "letting content extend behind the title bar" really means, rather than merely shrinking the padding.
 *
 * The height is fixed at [CHAT_HEADER_HEIGHT_DP] (plus [CHAT_HEADER_CONNECTION_ROW_DP] in remote mode),
 * and does not change as messages scroll, so there is no layout jumping; the status bar is handled by statusBarsPadding,
 * so content never presses against the status-bar icons. Text colours come from the glass's adaptive luminance, staying readable despite the transparency.
 */
@Composable
internal fun ChatHeader(
    sessionTitle: String,
    modelName: String?,
    inputTokens: Int,
    outputTokens: Int,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onNavigateToTerminal: () -> Unit,
    onNavigateToGit: () -> Unit,
    onNavigateToBrowser: () -> Unit = {},
    currentMode: AgentMode,
    onToggleMode: (AgentMode) -> Unit,
    connectionState: com.aicode.feature.agent.domain.container.ConnectionState? = null,
    showMenuButton: Boolean = true,
    terminalActive: Boolean = false,
    gitActive: Boolean = false,
    browserActive: Boolean = false,
    modifier: Modifier = Modifier,
    /** Glass background sampling layer; when null, degrades to an opaque top bar. */
    glassBackdrop: com.styropyr0.prismal.sources.PrismalGlassLayer? = null,
    /** Background luminance (0..1), provided by rememberGlassLuminance. */
    glassLuminance: () -> Float = { 0.5f },
    /** Foreground colour on the glass: adapts to the background luminance so the title and model name stay readable. */
    glassContentColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified
) {
    val hasGlass = glassBackdrop != null
    val titleColor = if (hasGlass) glassContentColor else MaterialTheme.colorScheme.onBackground
    val subtitleColor = if (hasGlass) {
        glassContentColor.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val headerHeight = (CHAT_HEADER_HEIGHT_DP + if (connectionState != null) CHAT_HEADER_CONNECTION_ROW_DP else 0).dp
    val headerModifier = modifier
        .fillMaxWidth()
        .statusBarsPadding()
        .height(headerHeight)
        .padding(horizontal = Spacing.xs)

    val headerContent: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CHAT_HEADER_HEIGHT_DP.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // On large screens with a permanent drawer, hide the hamburger button: the drawer is already on the left, and another toggle would only confuse.
                if (showMenuButton) {
                    IconButton(
                        onClick = onOpenDrawer,
                        modifier = Modifier.onboardingTarget(OnboardingStep.OPEN_SIDEBAR)
                    ) {
                        Icon(
                            FeatherIcons.Menu,
                            contentDescription = stringResource(R.string.chat_open_sidebar),
                            tint = subtitleColor)
                    }
                } else {
                    Spacer(modifier = Modifier.width(Spacing.sm))
                }
                // Title + model name are squeezed into two lines of text in the same row, avoiding the top bar being stretched into three sections.
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = sessionTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = titleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        if (!modelName.isNullOrBlank()) {
                            ModelLogoIcon(modelName = modelName, size = 14.dp)
                        }
                        Text(
                            text = modelName?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.chat_no_model_selected),
                            style = MaterialTheme.typography.bodySmall,
                            color = subtitleColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                IconButton(onClick = onNewChat) {
                    Icon(
                        FeatherIcons.Plus,
                        contentDescription = stringResource(R.string.chat_new_session),
                        tint = subtitleColor)
                }
                WorkbenchIconButton(
                    icon = FeatherIcons.GitBranch,
                    contentDescription = stringResource(R.string.chat_open_git),
                    active = gitActive,
                    onClick = onNavigateToGit
                )
                WorkbenchIconButton(
                    icon = FeatherIcons.Terminal,
                    contentDescription = stringResource(R.string.chat_open_terminal),
                    active = terminalActive,
                    onClick = onNavigateToTerminal
                )
            }
            // Remote mode: connection status + token stats; only occupies this row for remote sessions
            if (connectionState != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CHAT_HEADER_CONNECTION_ROW_DP.dp)
                        .padding(horizontal = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ConnectionIndicator(state = connectionState)
                    TokenStats(
                        inputTokens = inputTokens,
                        outputTokens = outputTokens
                    )
                }
            }
        }
    }

    if (glassBackdrop != null) {
        com.aicode.core.ui.GlassSurface(
            backdrop = glassBackdrop,
            modifier = headerModifier,
            cornerRadius = 20.dp,
            blurRadius = 10.dp,
            tintAlpha = 0.10f,
            luminance = glassLuminance,
            content = headerContent
        )
    } else {
        Box(
            modifier = headerModifier.background(MaterialTheme.colorScheme.background),
            content = headerContent
        )
    }
}

/** Top-bar workbench entry button: highlighted when the large-screen right pane has the corresponding content open, otherwise you cannot tell whether a tap opens or closes it. */
@Composable
private fun WorkbenchIconButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .then(
                    if (active) {
                        Modifier
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun ConnectionIndicator(
    state: com.aicode.feature.agent.domain.container.ConnectionState
) {
    val (dotColor, text) = when (state) {
        com.aicode.feature.agent.domain.container.ConnectionState.CONNECTED ->
            MaterialTheme.colorScheme.primary to stringResource(R.string.chat_ssh_connected)
        com.aicode.feature.agent.domain.container.ConnectionState.CONNECTING ->
            MaterialTheme.colorScheme.tertiary to stringResource(R.string.chat_ssh_connecting)
        com.aicode.feature.agent.domain.container.ConnectionState.FAILED ->
            MaterialTheme.colorScheme.error to stringResource(R.string.chat_ssh_failed)
        com.aicode.feature.agent.domain.container.ConnectionState.DISCONNECTED ->
            MaterialTheme.colorScheme.outline to stringResource(R.string.chat_ssh_disconnected)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TokenStats(inputTokens: Int, outputTokens: Int) {
    val inStr = formatTokenCount(inputTokens.toLong())
    val outStr = formatTokenCount(outputTokens.toLong())
    Text(
        text = "↑$inStr ↓$outStr",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun RemoteConnectingPlaceholder(
    state: com.aicode.feature.agent.domain.container.ConnectionState
) {
    val text = when (state) {
        com.aicode.feature.agent.domain.container.ConnectionState.CONNECTING -> stringResource(R.string.chat_connecting_remote)
        com.aicode.feature.agent.domain.container.ConnectionState.FAILED -> stringResource(R.string.chat_remote_connect_failed)
        com.aicode.feature.agent.domain.container.ConnectionState.DISCONNECTED -> stringResource(R.string.chat_no_remote_connection)
        com.aicode.feature.agent.domain.container.ConnectionState.CONNECTED -> ""
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (state == com.aicode.feature.agent.domain.container.ConnectionState.CONNECTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun WelcomeState(bottomReserve: Dp, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier.padding(Spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        val targetOffset = minOf(-(maxHeight * 0.13f), -(bottomReserve / 2))
        val animatedOffset by animateDpAsState(
            targetValue = targetOffset,
            animationSpec = if (Build.VERSION.SDK_INT >= 30) snap() else tween(durationMillis = 220),
            label = "welcome-offset"
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.offset(y = animatedOffset)
        ) {
            Text(
                text = stringResource(R.string.chat_placeholder),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.chat_input_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}