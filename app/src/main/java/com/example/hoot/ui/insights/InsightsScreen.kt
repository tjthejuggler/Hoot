package com.example.hoot.ui.insights

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.error
import coil3.request.crossfade
import com.example.hoot.data.local.entity.RecommendationEntity
import com.example.hoot.domain.insights.Insight
import com.example.hoot.domain.insights.InsightKind
import com.example.hoot.domain.insights.InsightSeverity
import com.example.hoot.ui.charts.BarChart
import com.example.hoot.ui.charts.LineChart
import com.example.hoot.ui.charts.RadarChart
import com.example.hoot.ui.common.NutrientDetailSheet
import com.example.hoot.ui.common.NutrientDetailViewModel
import com.example.hoot.ui.common.SectionHeader
import com.example.hoot.ui.common.TierChip
import com.example.hoot.ui.common.foodEmoji
import com.example.hoot.ui.common.prettyDay

/**
 * Insights screen (phase 4): window selector, score trend (weekly bars +
 * daily line), Tier-1 %RDA radar, deficiency/excess lists with severity
 * chips, recommendations carousel (Coil images with emoji fallback),
 * adherence summary, coach note, and links into History.
 */
@Composable
fun InsightsScreen(
    onOpenHistory: () -> Unit,
    vm: InsightsViewModel = viewModel(),
    detailVm: NutrientDetailViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var detailNutrientId by remember { mutableStateOf<String?>(null) }

    // One coach-note attempt per window change (LLM configured → live note,
    // otherwise the deterministic template — never an error surfaced).
    LaunchedEffect(state.window) { vm.refreshCoachNote() }

    detailNutrientId?.let { id ->
        detailVm.open(id)
        NutrientDetailSheet(vm = detailVm, onDismiss = { detailNutrientId = null })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- Header + window selector --------------------------------------
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Insights", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InsightsWindow.entries.forEach { w ->
                    FilterChip(
                        selected = state.window == w,
                        onClick = { vm.setWindow(w) },
                        label = { Text(w.label) }
                    )
                }
            }
        }

        // ---- First-run guidance ------------------------------------------------
        item {
            if (state.scoreSeries.isEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "No nutrition history yet. Log your first meal with “+ Add” on " +
                            "Today, or connect Tail to import meals automatically — " +
                            "trends, coverage and coaching appear after a couple of days.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---- Score trend ------------------------------------------------------
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Score trend", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        state.prevScoreAvg?.let { prev ->
                            val now = state.scoreSeries.map { it.score }
                                .takeIf { it.isNotEmpty() }?.average()
                            if (now != null) {
                                val delta = now - prev
                                Text(
                                    "%+.0f vs prev".format(delta),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (delta >= 0) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (state.weekBars.isNotEmpty()) {
                        BarChart(
                            values = state.weekBars.map { it.avgScore },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(90.dp),
                            barColor = MaterialTheme.colorScheme.primary,
                            valueRange = 0.0..100.0,
                            contentDescriptionText = "Weekly average score"
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    LineChart(
                        points = state.scoreSeries.map { it.score },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp),
                        lineColor = MaterialTheme.colorScheme.tertiary,
                        referenceLine = 70.0,
                        referenceLabel = "good (70)",
                        yMaxOverride = 100.0,
                        contentDescriptionText = "Daily score history"
                    )
                }
            }
        }

        // ---- Coverage radar (labeled axes, Tier-1/2) ------------------------------
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    SectionHeader(
                        "Coverage shape",
                        "Average % of target per nutrient (Tier 1 & 2), last " +
                            state.window.label + ". Spokes are labeled."
                    )
                    Spacer(Modifier.height(8.dp))
                    if (state.radar.size >= 3) {
                        RadarChart(
                            axes = state.radar,
                            modifier = Modifier.size(280.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Closer to the edge = closer to 100% of target.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "Not enough data yet — log a few days of meals.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ---- Deficiencies / excess (ALL tiers, filterable, actionable) -------------
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SectionHeader(
                        "What stands out",
                        "Persistent gaps and over-limits across ALL tiers."
                    )
                    Spacer(Modifier.height(8.dp))
                    // Tier filter chips: All / T1 / T2 / T3.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.tierFilter == null,
                            onClick = { vm.setTierFilter(null) },
                            label = { Text("All") }
                        )
                        listOf(1, 2, 3).forEach { tier ->
                            FilterChip(
                                selected = state.tierFilter == tier,
                                onClick = { vm.setTierFilter(tier) },
                                label = { Text("T$tier") }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val relevant = state.insights.filter {
                        it.kind == InsightKind.DEFICIENCY || it.kind == InsightKind.EXCESS
                    }
                    if (relevant.isEmpty()) {
                        Text(
                            "No persistent gaps or excesses detected in this tier.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    relevant.forEach { insight ->
                        Box(Modifier.clickable {
                            insight.nutrientId?.let { detailNutrientId = it }
                        }) {
                            InsightCard(
                                insight,
                                tier = insight.nutrientId?.let { state.tierById[it] },
                                dietFilter = state.dietFilter
                            )
                        }
                    }
                }
            }
        }

        // ---- Coach note ------------------------------------------------------------
        item { CoachNoteCard(state) }

        // ---- Adherence summary --------------------------------------------------------
        item {
            state.adherencePct?.let { pct ->
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "You followed $pct% of recommendations this " +
                            state.window.label.replace("d", " window").trim(),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // ---- Food suggestions carousel (informational; intake comes from Tail) -------
        item {
            Text("Foods high in your lacking nutrients", style = MaterialTheme.typography.titleMedium)
        }
        item {
            // Render-boundary diet gate (diet-fix hardening, 2026-09): rows
            // come from the PERSISTED recommendation_log — anything issued
            // before a diet change (or by a constraint-ignoring LLM) is
            // dropped here even if upstream filters regress.
            val safeRecs = state.recommendations.filter {
                state.dietFilter.allows(it.foodName) && state.dietFilter.allows(it.reasonText)
            }
            if (safeRecs.isEmpty()) {
                Text(
                    "No food suggestions yet — they appear when gaps are detected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(safeRecs, key = { it.id }) { rec ->
                        RecommendationCard(rec = rec)
                    }
                }
            }
        }

        // ---- Link into History ------------------------------------------------------------
        item {
            TextButton(onClick = onOpenHistory) {
                Text("Explore nutrient history →")
            }
        }
    }
}

