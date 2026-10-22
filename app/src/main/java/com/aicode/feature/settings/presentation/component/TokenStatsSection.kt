package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.TokenStatsPalette
import com.aicode.core.theme.semanticColors
import com.aicode.core.util.formatCostUsd
import com.aicode.feature.agent.data.local.dao.CallSummary
import com.aicode.feature.agent.data.local.dao.DayCallStats
import com.aicode.feature.agent.data.local.dao.ModelCallStats
import com.aicode.feature.agent.data.local.dao.ProviderCallStats
import com.aicode.feature.agent.data.local.dao.RecentCallRecord
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aicode.feature.agent.presentation.component.formatTokenCount
import com.aicode.feature.settings.presentation.TokenStatsPeriod
import com.aicode.feature.settings.presentation.TokenStatsUiState
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.Fill
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.X
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

private val INPUT_COLOR = TokenStatsPalette.Input
private val OUTPUT_COLOR = TokenStatsPalette.Output
private val CACHE_COLOR = TokenStatsPalette.Cache
private val ERROR_COLOR = TokenStatsPalette.Error
private val CANCELLED_COLOR = TokenStatsPalette.Cancelled
private val SUCCESS_COLOR = Color(0xFF2E7D32)
private val WARNING_COLOR = Color(0xFFE65100)

private const val STATS_PAGE_SIZE = 5
private const val CALLS_PAGE_SIZE = 10

/** Token stats page: period and dimension filters + overview + trend chart + provider/model rankings + call details. Uses LazyColumn for virtualized rendering to eliminate first-frame jank. */
@Composable
internal fun TokenStatsSection(
    state: TokenStatsUiState,
    onSelectPeriod: (TokenStatsPeriod) -> Unit,
    onSelectPage: (Int) -> Unit,
    onSelectProviderPage: (Int) -> Unit = {},
    onSelectModelPage: (Int) -> Unit = {},
    onSelectFilterProvider: (String?) -> Unit = {},
    onSelectFilterModel: (String?) -> Unit = {},
    onClearFilters: () -> Unit = {}
) {
    val context = LocalContext.current
    val lazyListState = rememberLazyListState()

    LazyColumn(
        state = lazyListState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.lg),
        contentPadding = PaddingValues(top = Spacing.sm, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        // ── Period switcher ──
        item(key = "period_selector") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                TokenStatsPeriod.entries.forEach { period ->
                    FilterChip(
                        selected = state.period == period,
                        onClick = { onSelectPeriod(period) },
                        label = { Text(stringResource(period.labelRes), fontSize = 13.sp) },
                        shape = RoundedCornerShape(50),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.semanticColors.cardSurface,
                            labelColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = state.period == period,
                            borderColor = MaterialTheme.semanticColors.subtleBorder,
                            selectedBorderColor = Color.Transparent
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ── Filter bar (provider + model + clear) ──
        item(key = "filter_bar") {
            TokenStatsFilterBar(
                selectedProviderId = state.filterProviderId,
                selectedModel = state.filterModel,
                availableProviders = state.availableProviders,
                availableModels = state.availableModels,
                onSelectProvider = onSelectFilterProvider,
                onSelectModel = onSelectFilterModel,
                onClearFilters = onClearFilters
            )
        }

        // ── Summary cards (kept as placeholders when there is no data, values show -)──
        item(key = "summary_cards") {
            val summary = state.summary
            val hasData = summary != null && summary.calls > 0
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    SummaryCard(
                        label = stringResource(R.string.settings_token_stats_total),
                        value = if (hasData) formatTokenCount(summary.inputTokens + summary.outputTokens) else "-",
                        modifier = Modifier.weight(1f)
                    )
                    SummaryCard(
                        label = stringResource(R.string.settings_token_stats_requests),
                        value = if (hasData) summary.calls.toString() else "-",
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    SummaryCard(
                        label = stringResource(R.string.settings_token_stats_cost),
                        value = formatCostUsd(state.totalCostUsd),
                        modifier = Modifier.weight(1f)
                    )
                    SummaryCard(
                        label = stringResource(R.string.settings_token_stats_cache_hit_rate),
                        value = if (hasData) formatCacheHitRate(summary) else "-",
                        sub = if (hasData) formatCache(context, summary.cachedInputTokens, summary.cacheCreationTokens) else null,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ── Trend chart (not rendered at all when there is no data or only a single point, to avoid Vico empty-series / single-point xStep errors)──
        if (state.trend.size > 1) {
            item(key = "trend_chart") {
                Column {
                    SettingsGroupHeader(text = stringResource(R.string.settings_token_stats_trend))
                    Spacer(Modifier.height(Spacing.xs))
                    SettingsGroup {
                        TokenTrendChart(
                            trend = state.trend,
                            isHourly = state.period == TokenStatsPeriod.TODAY
                        )
                        SettingsDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.lg, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
                        ) {
                            LegendDot(INPUT_COLOR, stringResource(R.string.settings_token_stats_legend_input))
                            LegendDot(OUTPUT_COLOR, stringResource(R.string.settings_token_stats_legend_output))
                            LegendDot(CACHE_COLOR, stringResource(R.string.settings_token_stats_legend_cached))
                        }
                    }
                }
            }
        }

        // ── Provider stats (placeholder shown when there is no data)──
        item(key = "provider_stats_header") {
            SettingsGroupHeader(text = stringResource(R.string.settings_token_stats_provider))
        }
        item(key = "provider_stats_content") {
            SettingsGroup {
                if (state.providers.isEmpty()) {
                    EmptyStatsPlaceholder()
                } else {
                    state.providers.forEachIndexed { index, p ->
                        if (index > 0) SettingsDivider()
                        ProviderStatsRow(
                            p = p,
                            keyCount = state.providerKeyCounts[p.providerId],
                            isSelected = state.filterProviderId == p.providerId,
                            onClick = {
                                if (state.filterProviderId == p.providerId) {
                                    onSelectFilterProvider(null)
                                } else {
                                    onSelectFilterProvider(p.providerId)
                                }
                            }
                        )
                    }
                    if (state.providersTotal > STATS_PAGE_SIZE) {
                        SettingsDivider()
                        StatsPaginationBar(
                            page = state.providersPage,
                            total = state.providersTotal,
                            pageSize = STATS_PAGE_SIZE,
                            onSelectPage = onSelectProviderPage
                        )
                    }
                }
            }
        }

        // ── Model stats (placeholder shown when there is no data)──
        item(key = "model_stats_header") {
            SettingsGroupHeader(text = stringResource(R.string.settings_token_stats_model))
        }
        item(key = "model_stats_content") {
            SettingsGroup {
                if (state.models.isEmpty()) {
                    EmptyStatsPlaceholder()
                } else {
                    state.models.forEachIndexed { index, m ->
                        if (index > 0) SettingsDivider()
                        ModelStatsRow(
                            m = m,
                            isSelected = state.filterModel == m.model,
                            onClick = {
                                if (state.filterModel == m.model) {
                                    onSelectFilterModel(null)
                                } else {
                                    onSelectFilterModel(m.model)
                                }
                            }
                        )
                    }
                    if (state.modelsTotal > STATS_PAGE_SIZE) {
                        SettingsDivider()
                        StatsPaginationBar(
                            page = state.modelsPage,
                            total = state.modelsTotal,
                            pageSize = STATS_PAGE_SIZE,
                            onSelectPage = onSelectModelPage
                        )
                    }
                }
            }
        }

        // ── Call details (placeholder shown when there is no data)──
        item(key = "calls_header") {
            SettingsGroupHeader(text = stringResource(R.string.settings_token_stats_recent_calls))
        }
        item(key = "calls_content") {
            SettingsGroup {
                if (state.recentCalls.isEmpty()) {
                    EmptyStatsPlaceholder()
                } else {
                    CallRecordsTable(
                        calls = state.recentCalls,
                        costs = state.recentCallCosts,
                        page = state.callsPage,
                        total = state.callsTotal,
                        onSelectPage = onSelectPage
                    )
                }
            }
        }
    }
}

/** Top filter bar: provider dropdown, model dropdown, and one-tap clear. */
@Composable
private fun TokenStatsFilterBar(
    selectedProviderId: String?,
    selectedModel: String?,
    availableProviders: List<Pair<String, String>>,
    availableModels: List<String>,
    onSelectProvider: (String?) -> Unit,
    onSelectModel: (String?) -> Unit,
    onClearFilters: () -> Unit
) {
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    val hasFilter = selectedProviderId != null || selectedModel != null
    val providerDisplayName = availableProviders.firstOrNull { it.first == selectedProviderId }?.second
        ?: selectedProviderId

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Provider filter dropdown
        Box(modifier = Modifier.weight(1f)) {
            FilterChip(
                selected = selectedProviderId != null,
                onClick = { providerMenuExpanded = true },
                label = {
                    Text(
                        text = if (selectedProviderId != null) {
                            stringResource(R.string.settings_token_stats_filter_provider, providerDisplayName.orEmpty())
                        } else {
                            stringResource(R.string.settings_token_stats_filter_all_providers)
                        },
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                trailingIcon = {
                    Icon(
                        imageVector = FeatherIcons.ChevronDown,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                },
                shape = RoundedCornerShape(8.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    containerColor = MaterialTheme.semanticColors.cardSurface,
                    labelColor = MaterialTheme.colorScheme.onSurface
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selectedProviderId != null,
                    borderColor = MaterialTheme.semanticColors.subtleBorder,
                    selectedBorderColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.fillMaxWidth()
            )
            DropdownMenu(
                expanded = providerMenuExpanded,
                onDismissRequest = { providerMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_token_stats_filter_all_providers)) },
                    trailingIcon = if (selectedProviderId == null) {
                        { Icon(FeatherIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                    onClick = {
                        onSelectProvider(null)
                        providerMenuExpanded = false
                    }
                )
                availableProviders.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        trailingIcon = if (selectedProviderId == id) {
                            { Icon(FeatherIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        onClick = {
                            onSelectProvider(id)
                            providerMenuExpanded = false
                        }
                    )
                }
            }
        }

        // Model filter dropdown
        Box(modifier = Modifier.weight(1f)) {
            FilterChip(
                selected = selectedModel != null,
                onClick = { modelMenuExpanded = true },
                label = {
                    Text(
                        text = if (selectedModel != null) {
                            stringResource(R.string.settings_token_stats_filter_model, selectedModel)
                        } else {
                            stringResource(R.string.settings_token_stats_filter_all_models)
                        },
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                trailingIcon = {
                    Icon(
                        imageVector = FeatherIcons.ChevronDown,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                },
                shape = RoundedCornerShape(8.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    containerColor = MaterialTheme.semanticColors.cardSurface,
                    labelColor = MaterialTheme.colorScheme.onSurface
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selectedModel != null,
                    borderColor = MaterialTheme.semanticColors.subtleBorder,
                    selectedBorderColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.fillMaxWidth()
            )
            DropdownMenu(
                expanded = modelMenuExpanded,
                onDismissRequest = { modelMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_token_stats_filter_all_models)) },
                    trailingIcon = if (selectedModel == null) {
                        { Icon(FeatherIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null,
                    onClick = {
                        onSelectModel(null)
                        modelMenuExpanded = false
                    }
                )
                availableModels.forEach { modelName ->
                    DropdownMenuItem(
                        text = { Text(modelName) },
                        trailingIcon = if (selectedModel == modelName) {
                            { Icon(FeatherIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        onClick = {
                            onSelectModel(modelName)
                            modelMenuExpanded = false
                        }
                    )
                }
            }
        }

        // Clear filters button
        if (hasFilter) {
            FilterChip(
                selected = true,
                onClick = onClearFilters,
                label = { Text(stringResource(R.string.settings_token_stats_filter_clear), fontSize = 12.sp) },
                trailingIcon = {
                    Icon(
                        imageVector = FeatherIcons.X,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                },
                shape = RoundedCornerShape(8.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.semanticColors.cardSurface,
                    selectedLabelColor = MaterialTheme.colorScheme.error
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = true,
                    borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                    selectedBorderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                )
            )
        }
    }
}

@Composable
private fun SummaryCard(label: String, value: String, sub: String? = null, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.semanticColors.cardSurface
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            } else {
                // Placeholder when there is no sub-text, to keep the four cards the same height
                Spacer(Modifier.height(2.dp + 16.dp))
            }
        }
    }
}

@Composable
private fun TokenTrendChart(trend: List<DayCallStats>, isHourly: Boolean) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val axisLabel = rememberTextComponent(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textSize = 10.sp
    )
    // y-axis: token counts shown in k/M units to avoid crowding large numbers
    val yAxisFormatter = remember {
        CartesianValueFormatter { _, value, _ -> formatTokenCount(value.toLong()) }
    }
    val hourSuffix = stringResource(R.string.token_stats_hour_suffix)
    val xAxisFormatter = remember(trend, isHourly, hourSuffix) {
        CartesianValueFormatter { _, value, _ ->
            if (isHourly) {
                "${value.toLong() % 24}$hourSuffix"
            } else {
                SimpleDateFormat("M/d", Locale.getDefault())
                    .format(Date(value.toLong() * 86_400_000L + tzOffsetNow()))
            }
        }
    }
    val itemPlacer = remember(trend.size) {
        if (trend.size <= 10) HorizontalAxis.ItemPlacer.aligned() else HorizontalAxis.ItemPlacer.segmented()
    }
    LaunchedEffect(trend) {
        modelProducer.runTransaction {
            val x = trend.map { it.day.toDouble() }
            lineSeries {
                series(x = x, y = trend.map { it.inputTokens })
                series(x = x, y = trend.map { it.outputTokens })
                series(x = x, y = trend.map { it.cachedInputTokens })
            }
        }
    }
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(
                    trendLine(INPUT_COLOR),
                    trendLine(OUTPUT_COLOR),
                    trendLine(CACHE_COLOR)
                )
            ),
            startAxis = VerticalAxis.rememberStart(
                label = axisLabel,
                valueFormatter = yAxisFormatter
            ),
            bottomAxis = HorizontalAxis.rememberBottom(
                label = axisLabel,
                valueFormatter = xAxisFormatter,
                itemPlacer = itemPlacer
            )
        ),
        modelProducer = modelProducer,
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .padding(top = Spacing.md)
    )
}

/** Trend line: solid line + translucent area fill, still distinguishable when multiple lines overlap. */
private fun trendLine(color: Color): LineCartesianLayer.Line = LineCartesianLayer.Line(
    fill = LineCartesianLayer.LineFill.single(Fill(color.toArgb())),
    stroke = LineCartesianLayer.LineStroke.Continuous(thicknessDp = 2f),
    areaFill = LineCartesianLayer.AreaFill.single(Fill(color.copy(alpha = 0.18f).toArgb()))
)

private fun tzOffsetNow(): Long =
    java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()

@Composable
private fun EmptyStatsPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.settings_token_stats_no_data),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Provider stats row (refactored):
 * Combines the reference design's request count, success rate, credential count, and progress-bar visual hierarchy,
 * hides the retry display, and supports tapping to directly filter by that provider.
 */
@Composable
private fun ProviderStatsRow(
    p: ProviderCallStats,
    keyCount: Int?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val successRateFloat = if (p.calls > 0) (p.calls - p.errors).toFloat() / p.calls else 0f
    val successRatePercent = String.format(Locale.getDefault(), "%.1f%%", successRateFloat * 100f)
    val successColor = if (successRateFloat >= 0.95f) SUCCESS_COLOR else WARNING_COLOR

    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    } else {
        Color.Transparent
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = 12.dp)
    ) {
        // Top row: provider name + large emphasized request count
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = p.providerName ?: p.providerId ?: stringResource(R.string.settings_token_stats_unknown),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = String.format(Locale.getDefault(), "%,d", p.calls),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.settings_token_stats_requests_unit),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Second row: credential count + success-rate percentage + token usage
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (keyCount != null && keyCount > 0) {
                Text(
                    text = stringResource(R.string.settings_token_stats_provider_keys, keyCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(
                text = successRatePercent,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = successColor
            )

            Spacer(Modifier.weight(1f))

            Text(
                text = "↑${formatTokenCount(p.inputTokens)} ↓${formatTokenCount(p.outputTokens)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))

        // Third row: thin success-rate progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.semanticColors.subtleBorder)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = successRateFloat.coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(successColor)
            )
        }
    }
}

/**
 * Model stats row:
 * Hides the retry chip, shows call count and success rate, and supports tapping to directly filter by that model.
 */
@Composable
private fun ModelStatsRow(
    m: ModelCallStats,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val successRate = if (m.calls > 0) {
        String.format(Locale.getDefault(), "%.1f%%", (m.calls - m.errors) * 100.0 / m.calls)
    } else {
        "-"
    }

    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    } else {
        Color.Transparent
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = m.model ?: stringResource(R.string.settings_token_stats_unknown),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "↑${formatTokenCount(m.inputTokens)} ↓${formatTokenCount(m.outputTokens)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            StatChip(stringResource(R.string.settings_token_stats_calls_suffix2, m.calls))
            StatChip(stringResource(R.string.settings_token_stats_success_rate, successRate))
        }
        Spacer(Modifier.height(6.dp))
        // Usage-share progress bar (input + output segments)
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .weight((m.inputTokens + 1f).toFloat())
                    .height(6.dp)
                    .background(INPUT_COLOR, RoundedCornerShape(3.dp))
            )
            Spacer(Modifier.width(2.dp))
            Box(
                modifier = Modifier
                    .weight((m.outputTokens + 1f).toFloat())
                    .height(6.dp)
                    .background(OUTPUT_COLOR, RoundedCornerShape(3.dp))
            )
        }
    }
}

@Composable
private fun StatChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun StatsPaginationBar(
    page: Int,
    total: Int,
    pageSize: Int,
    onSelectPage: (Int) -> Unit
) {
    val pageCount = if (total == 0) 1 else (total - 1) / pageSize + 1
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = { onSelectPage(page - 1) }, enabled = page > 0) {
            Text(stringResource(R.string.common_prev))
        }
        Text(
            text = stringResource(R.string.settings_token_stats_page, page + 1, pageCount),
            style = MaterialTheme.typography.bodySmall,
            color = mutedColor
        )
        TextButton(onClick = { onSelectPage(page + 1) }, enabled = page < pageCount - 1) {
            Text(stringResource(R.string.common_next))
        }
    }
}

private val COL_MODEL_W = 150.dp
private val COL_PROVIDER_W = 100.dp
private val COL_TIME_W = 120.dp
private val COL_STATUS_W = 70.dp
private val COL_TOKENS_W = 60.dp
private val COL_DURATION_W = 62.dp
private val COL_EFFORT_W = 72.dp
private val COL_COST_W = 80.dp

@Composable
private fun CallRecordsTable(
    calls: List<RecentCallRecord>,
    costs: Map<Long, Double?>,
    page: Int,
    total: Int,
    onSelectPage: (Int) -> Unit
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        // Header and data rows share one horizontal-scroll container so columns stay aligned while scrolling
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                Row(modifier = Modifier.padding(horizontal = Spacing.md, vertical = 8.dp)) {
                    TableCell(stringResource(R.string.settings_token_stats_col_time), mutedColor, COL_TIME_W, FontWeight.Medium)
                    TableCell(stringResource(R.string.settings_token_stats_col_provider), mutedColor, COL_PROVIDER_W, FontWeight.Medium)
                    TableCell(stringResource(R.string.settings_token_stats_col_model), mutedColor, COL_MODEL_W, FontWeight.Medium)
                    TableCell(stringResource(R.string.settings_token_stats_col_reasoning_effort), mutedColor, COL_EFFORT_W, FontWeight.Medium)
                    TableCell(stringResource(R.string.settings_token_stats_col_status), mutedColor, COL_STATUS_W, FontWeight.Medium)
                    TableCell(stringResource(R.string.settings_token_stats_col_input), mutedColor, COL_TOKENS_W, FontWeight.Medium)
                    TableCell(stringResource(R.string.settings_token_stats_col_output), mutedColor, COL_TOKENS_W, FontWeight.Medium)
                    TableCell(stringResource(R.string.settings_token_stats_col_cached_read), mutedColor, COL_TOKENS_W, FontWeight.Medium)
                    TableCell(stringResource(R.string.settings_token_stats_col_cached_write), mutedColor, COL_TOKENS_W, FontWeight.Medium)
                    TableCell(stringResource(R.string.settings_token_stats_col_cache_hit_rate), mutedColor, COL_TOKENS_W, FontWeight.Medium)
                    TableCell(stringResource(R.string.settings_token_stats_col_ttfb), mutedColor, COL_DURATION_W, FontWeight.Medium)
                    TableCell(stringResource(R.string.settings_token_stats_col_duration), mutedColor, COL_DURATION_W, FontWeight.Medium)
                    TableCell(stringResource(R.string.settings_token_stats_col_cost), mutedColor, COL_COST_W, FontWeight.Medium)
                }
                calls.forEach { call ->
                    CallRecordRow(call, costs[call.record.id], textColor, mutedColor)
                }
            }
        }
        StatsPaginationBar(
            page = page,
            total = total,
            pageSize = CALLS_PAGE_SIZE,
            onSelectPage = onSelectPage
        )
    }
}

@Composable
private fun CallRecordRow(call: RecentCallRecord, cost: Double?, textColor: Color, mutedColor: Color) {
    val statusColor = when (call.record.status) {
        "error" -> ERROR_COLOR
        "cancelled" -> CANCELLED_COLOR
        else -> TokenStatsPalette.Progress
    }
    Row(modifier = Modifier.padding(horizontal = Spacing.md, vertical = 6.dp)) {
        TableCell(formatCallTime(call.record.createdAt), mutedColor, COL_TIME_W)
        TableCell(call.providerName ?: call.record.providerId ?: stringResource(R.string.settings_token_stats_unknown), textColor, COL_PROVIDER_W)
        TableCell(call.record.model ?: stringResource(R.string.settings_token_stats_unknown), textColor, COL_MODEL_W)
        TableCell(call.record.reasoningEffort ?: "-", mutedColor, COL_EFFORT_W)
        TableCell(call.record.status, statusColor, COL_STATUS_W, FontWeight.Medium)
        TableCell(call.record.inputTokens.toString(), textColor, COL_TOKENS_W)
        TableCell(call.record.outputTokens.toString(), textColor, COL_TOKENS_W)
        TableCell(call.record.cachedInputTokens.toString(), textColor, COL_TOKENS_W)
        TableCell(call.record.cacheCreationTokens.toString(), textColor, COL_TOKENS_W)
        TableCell(formatRecordCacheHitRate(call.record), textColor, COL_TOKENS_W)
        TableCell(formatDuration(call.record.ttfbMillis?.toDouble()), mutedColor, COL_DURATION_W)
        TableCell(formatDuration(call.record.durationMillis?.toDouble()), mutedColor, COL_DURATION_W)
        TableCell(if (cost == null) "-" else formatCostUsd(cost), textColor, COL_COST_W)
    }
}

@Composable
private fun TableCell(text: String, color: Color, width: Dp, fontWeight: FontWeight = FontWeight.Normal) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width)
    )
}

/** Token abbreviation format consistent with the chat page header (see MarkdownContent.formatTokenCount): 1234 -> 1.2k. */
private fun formatCache(context: android.content.Context, cachedRead: Long, cachedWrite: Long): String =
    if (cachedRead > 0 || cachedWrite > 0) {
        context.getString(
            R.string.settings_token_stats_cache_read_write,
            formatTokenCount(cachedRead),
            formatTokenCount(cachedWrite)
        )
    } else {
        ""
    }

private fun formatCallTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault()).format(Date(epochMillis))

/** Per-call cache hit rate: cached input / total input (inputTokens includes the cache-hit portion; provider adapters have unified this definition). */
private fun formatRecordCacheHitRate(call: LlmCallRecordEntity): String {
    if (call.inputTokens <= 0) return "-"
    val rate = min(call.cachedInputTokens * 100.0 / call.inputTokens, 100.0)
    return String.format(Locale.getDefault(), "%.1f%%", rate)
}

/** Overview cache hit rate: cached input / total input (same definition as per-call); clamped to 100% to guard against anomalous values reported by some servers. */
private fun formatCacheHitRate(s: CallSummary): String {
    if (s.inputTokens <= 0) return "-"
    val rate = min(s.cachedInputTokens * 100.0 / s.inputTokens, 100.0)
    return String.format(Locale.getDefault(), "%.1f%%", rate)
}

private fun formatDuration(ms: Double?): String {
    if (ms == null) return "-"
    return if (ms >= 1000) String.format(Locale.getDefault(), "%.1fs", ms / 1000)
    else "${ms.toInt()}ms"
}
