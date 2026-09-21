package com.example.hoot.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hoot.data.local.entity.NutrientDefinitionEntity
import com.example.hoot.ui.charts.LineChart
import com.example.hoot.ui.common.formatNutrient
import com.example.hoot.ui.common.foodEmoji
import com.example.hoot.ui.common.percentOf
import com.example.hoot.ui.common.prettyDay

/**
 * Nutrient-centric history (phase 4): searchable tier-badged nutrient picker,
 * window selector (7d/30d/90d/1y/all), intake-vs-RDA line chart, stats
 * header (avg, % days meeting, trend) and top food contributors.
 */
@Composable
fun HistoryScreen(historyVm: HistoryViewModel = viewModel()) {
    val state by historyVm.state.collectAsStateWithLifecycle()
    val window by historyVm.window.collectAsStateWithLifecycle()
    val search by historyVm.search.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }   // 0 = Nutrient, 1 = Days

    Column(Modifier.fillMaxSize()) {
        // ---- Header + window selector -------------------------------------
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "History", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HistoryWindow.entries.forEach { w ->
                FilterChip(
                    selected = window == w,
                    onClick = { historyVm.setWindow(w) },
                    label = { Text(w.label) }
                )
            }
        }

        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Nutrient") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Days") })
        }

        if (tab == 0) {
            NutrientTab(state, historyVm, search)
        } else {
            DaysTab(state)
        }
    }
}

@Composable
private fun NutrientTab(
    state: HistoryUiState,
    historyVm: HistoryViewModel,
    search: String
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- Selected nutrient header + picker toggle ----------------------
        item {
            state.selected?.let { def ->
                NutrientHeader(def, state.goals[def.id]?.targetValue)
                Spacer(Modifier.height(4.dp))
            }
        }
        // ---- Stats header ---------------------------------------------------
        item { StatsHeader(state.stats, state.selected) }
        // ---- Chart -----------------------------------------------------------
        item {
            val def = state.selected
            if (def != null && state.series.isEmpty() && state.loading.not()) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "No intake data in this window yet — log meals or connect Tail " +
                            "and your ${def.name} history will chart itself here.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (def != null && state.series.isNotEmpty()) {
                val target = state.series.first().target
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Intake vs target", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        LineChart(
                            points = state.series.map { it.intake },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            lineColor = MaterialTheme.colorScheme.primary,
                            referenceLine = target.takeIf { it > 0 },
                            referenceLabel = "target ${formatNutrient(target, def.unit)}",
                            referenceColor = MaterialTheme.colorScheme.tertiary,
                            contentDescriptionText = "${def.name} intake history"
                        )
                    }
                }
            }
        }
        // ---- Contributors ------------------------------------------------------
        item {
            if (state.contributors.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Top food sources", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        state.contributors.forEach { c ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(foodEmoji(c.food.displayName), style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(c.food.displayName, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${formatNutrient(c.total, state.selected?.unit ?: "")} in window",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    "${(c.pctOfWindow * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
        // ---- Picker (searchable) -------------------------------------------------
        item {
            OutlinedTextField(
                value = search,
                onValueChange = historyVm::setSearch,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search nutrients…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true
            )
        }
        items(
            state.definitions.filter {
                search.isBlank() || it.name.contains(search, true) || it.id.contains(search, true)
            },
            key = { it.id }
        ) { def ->
            PickerRow(
                def = def,
                selected = def.id == state.selected?.id,
                onClick = { historyVm.select(def) }
            )
        }
    }
}

@Composable
private fun NutrientHeader(def: NutrientDefinitionEntity, goalTarget: Double?) {
    val isLimit = def.id in com.example.hoot.ui.common.LIMIT_TRACKER_IDS
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    foodEmoji(def.name),
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(def.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${def.group} · ${def.unit}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TierBadge(def.tier)
            }
            Spacer(Modifier.height(8.dp))
            val target = goalTarget ?: def.rdaValue ?: 0.0
            Text(
                when {
                    isLimit ->
                        "Limit-tracker · cap ${formatNutrient(target, def.unit)}/day" +
                            (def.ulValue?.let { " (UL ${formatNutrient(it, def.unit)})" } ?: "")
                    target > 0 ->
                        "Target ${formatNutrient(target, def.unit)}/day" +
                            (def.ulValue?.let { " · UL ${formatNutrient(it, def.unit)}" } ?: "")
                    else -> "No daily target"
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun TierBadge(tier: Int) {
    val (color, label) = when (tier) {
        1 -> MaterialTheme.colorScheme.error to "T1"
        2 -> MaterialTheme.colorScheme.tertiary to "T2"
        else -> MaterialTheme.colorScheme.outline to "T3"
    }
    Box(
        Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun StatsHeader(stats: NutrientStats?, def: NutrientDefinitionEntity?) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Average", style = MaterialTheme.typography.labelSmall)
                Text(
                    stats?.let { formatNutrient(it.avg, def?.unit ?: "") } ?: "—",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Column {
                Text("Days meeting", style = MaterialTheme.typography.labelSmall)
                Text(
                    stats?.let { "${it.daysMeeting}/${it.daysWithData}" } ?: "—",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Column {
                Text("Trend", style = MaterialTheme.typography.labelSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (stats?.trendUp) {
                        true -> Icon(
                            Icons.Filled.ArrowUpward, contentDescription = "trending up",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        false -> Icon(
                            Icons.Filled.ArrowDownward, contentDescription = "trending down",
                            tint = MaterialTheme.colorScheme.error
                        )
                        null -> Text("—", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerRow(
    def: NutrientDefinitionEntity,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    CircleShape
                )
        )
        Spacer(Modifier.width(10.dp))
        Text(def.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        TierBadge(def.tier)
    }
}

@Composable
private fun DaysTab(state: HistoryUiState) {
    val def = state.selected
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            Text(
                "Per-day totals for ${def?.name ?: "nutrient"} (selected window)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.series.isEmpty()) {
            item {
                Text(
                    "Nothing here yet — log a meal or connect Tail to start the history.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(state.series.asReversed(), key = { it.day }) { p ->
            val pct = percentOf(p.target, p.intake)
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .padding(12.dp)
                        .semantics {
                            contentDescription = "${prettyDay(p.day)}: " +
                                formatNutrient(p.intake, def?.unit ?: "")
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(prettyDay(p.day), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            formatNutrient(p.intake, def?.unit ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val color = when {
                        p.target <= 0 -> MaterialTheme.colorScheme.outline
                        p.intake >= p.target -> MaterialTheme.colorScheme.primary
                        pct >= 75 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    }
                    Text(
                        if (p.target > 0) "$pct%" else "—",
                        style = MaterialTheme.typography.titleMedium,
                        color = color
                    )
                }
            }
        }
    }
}
