package com.example.hoot.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hoot.appGraph
import com.example.hoot.data.tail.TailSyncState
import com.example.hoot.domain.insights.Insight
import com.example.hoot.domain.score.ScoreStatus
import com.example.hoot.ui.charts.ProgressRing
import com.example.hoot.ui.charts.Sparkline
import com.example.hoot.ui.charts.scoreColor
import com.example.hoot.ui.common.formatNutrient
import com.example.hoot.ui.common.percentOf
import com.example.hoot.ui.common.prettyDay
import com.example.hoot.ui.tail.TailSyncBanner

/**
 * Today dashboard (phase 4): date header, animated score ring, Tier-1
 * nutrient bars, calories + macros, meals/supplements with retry affordance,
 * Tail-sync + analysis chips, insight summary and a 7-day sparkline.
 */
@Composable
fun TodayScreen(
    onOpenHistory: () -> Unit,
    onOpenInsights: () -> Unit,
    onOpenTailSetup: () -> Unit = {}
) {
    val context = LocalContext.current
    val vm: TodayViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val meals by vm.meals.collectAsStateWithLifecycle()
    val supplements by vm.supplements.collectAsStateWithLifecycle()
    val unresolved by vm.unresolved.collectAsStateWithLifecycle()

    val config by context.appGraph.tailConfig.observeTailConfig()
        .collectAsStateWithLifecycle(initialValue = null)
    val syncState by context.appGraph.tailSync.syncState.collectAsStateWithLifecycle()
    val nutritionState by context.appGraph.nutritionProcessor.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Extra bottom padding keeps the last card clear of the "+ Add" FAB.
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- Date header + sync chips -----------------------------------
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Today", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        prettyDay(state.day),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = vm::retryUnresolved) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh analysis")
                }
            }
        }
        item {
            if (state.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TailSyncBanner(
                    config = config,
                    syncState = syncState,
                    onOpenSetup = onOpenTailSetup,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            NutritionProcessingChip(nutritionState, unresolved, onRetry = vm::retryUnresolved)
        }

        // ---- Score ring + sparkline --------------------------------------
        item { ScoreCard(state, onOpenInsights) }

        // ---- Tier-1 progress ---------------------------------------------
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Tier-1 nutrients", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "vs RDA", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (state.tier1.isEmpty()) {
                        Text(
                            "No nutrient data yet — log a meal or connect Tail.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.tier1.forEach { comp -> NutrientBar(comp) }
                }
            }
        }

        // ---- Calories + macros ---------------------------------------------
        item { CaloriesCard(state) }

        // ---- Insights summary (top 3) --------------------------------------
        item {
            Card(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenInsights)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Insights", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    val top = com.example.hoot.domain.insights.InsightsEngine.topSummary(state.insights)
                    if (top.isEmpty()) {
                        Text(
                            "Nothing noteworthy — keep it up!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    top.forEach { InsightRow(it) }
                }
            }
        }

        // ---- Meals + supplements --------------------------------------------
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Logged today", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (meals.isEmpty() && supplements.isEmpty()) {
                        Text(
                            "Use “+ Add” to log a meal or supplement.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    meals.forEach { meal ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Restaurant, contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                meal.title ?: meal.rawText,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    supplements.forEach { supp ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Bolt, contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                supp.label, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreCard(state: TodayUiState, onOpenInsights: () -> Unit) {
    val snapshot = state.score
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenInsights)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center) {
                val score = snapshot?.score ?: 0.0
                ProgressRing(
                    progress = score / 100.0,
                    modifier = Modifier.size(110.dp),
                    color = scoreColor(
                        score,
                        high = ScoreHighAccent, mid = ScoreMidAccent, low = ScoreLowAccent
                    )
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "%.0f".format(score),
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        "/100",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Daily score", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                if (snapshot == null) {
                    Text(
                        "Score appears once today's data lands.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "${snapshot.nutrientsMet}/${snapshot.nutrientsTracked} targets met",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (state.sparkline.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Sparkline(
                        values = state.sparkline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Last 7 days",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun NutrientBar(comp: com.example.hoot.domain.score.ScoreComponent) {
    val targetPct = percentOf(comp.target, comp.intake).coerceAtMost(999)
    val color = when (comp.status) {
        ScoreStatus.MET -> ScoreHighAccent
        ScoreStatus.CLOSE -> ScoreMidAccent
        ScoreStatus.EXCESS -> MaterialTheme.colorScheme.error
        ScoreStatus.LOW -> ScoreLowAccent
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics {
                contentDescription =
                    "${comp.name}: ${formatNutrient(comp.intake, comp.unit)} of " +
                        "${formatNutrient(comp.target, comp.unit)} target"
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(comp.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                "${formatNutrient(comp.intake, comp.unit)} / $targetPct%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { comp.coverage.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = color,
            trackColor = color.copy(alpha = 0.15f)
        )
    }
}

@Composable
private fun CaloriesCard(state: TodayUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Energy & macros", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            state.calories?.let { (kcal, unit) ->
                Text(
                    "${formatNutrient(kcal, unit)} today",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
            } ?: Text(
                "No calories logged yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                state.macros.forEach { m ->
                    Column {
                        Text(
                            formatNutrient(m.intake, m.unit),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            m.name, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightRow(insight: Insight) {
    val badgeColor = when (insight.severity) {
        com.example.hoot.domain.insights.InsightSeverity.CRITICAL ->
            MaterialTheme.colorScheme.error
        com.example.hoot.domain.insights.InsightSeverity.WARNING ->
            ScoreMidAccent
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(badgeColor, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(insight.title, style = MaterialTheme.typography.labelLarge)
            Text(
                insight.message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun NutritionProcessingChip(
    state: com.example.hoot.domain.nutrition.NutritionProcessState,
    unresolved: Int,
    onRetry: () -> Unit
) {
    if (state is com.example.hoot.domain.nutrition.NutritionProcessState.Processing) {
        AssistChip(
            onClick = onRetry,
            label = { Text("Analyzing nutrition… ${state.pending} foods left") },
            leadingIcon = {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        )
    } else if (unresolved > 0) {
        AssistChip(
            onClick = onRetry,
            label = { Text("$unresolved foods need analysis — tap to retry") },
            leadingIcon = {
                Icon(
                    Icons.Filled.Call, contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        )
    }
}

// Theme accent shortcuts (mirror ui/theme/Color.kt semantic accents).
private val ScoreHighAccent get() = Color(0xFF7DD97B)
private val ScoreMidAccent get() = Color(0xFFF2C94C)
private val ScoreLowAccent get() = Color(0xFFEF7B5C)
