package com.example.hoot.ui.nutrient

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.hoot.data.local.entity.RecommendationEntity
import com.example.hoot.domain.insights.Grade
import androidx.compose.foundation.layout.Box
import com.example.hoot.ui.charts.ChartTimeNavRow
import androidx.compose.foundation.layout.Box
import com.example.hoot.ui.charts.ChartTimeNavRow
import com.example.hoot.ui.charts.LineChart
import com.example.hoot.ui.charts.chartPan
import com.example.hoot.ui.charts.shortRangeLabel
import com.example.hoot.ui.charts.chartPan
import com.example.hoot.ui.charts.shortRangeLabel
import com.example.hoot.ui.common.SectionHeader
import com.example.hoot.ui.common.TierChip
import com.example.hoot.ui.common.foodEmoji
import com.example.hoot.ui.common.formatNutrient
import com.example.hoot.ui.common.percentOf
import com.example.hoot.ui.common.shortDayLabel
import com.example.hoot.ui.theme.ScoreHigh
import com.example.hoot.ui.theme.ScoreLow
import com.example.hoot.ui.theme.ScoreMid

/**
 * Full-screen nutrient detail page (replaces the Insights bottom drawer):
 * today's status + window grade, a 7/30/90-day intake chart with target line,
 * consistency stats, your usual meal/supplement sources with share bars,
 * diet-aware "high in X that you don't usually eat" ideas, the cache-first
 * LLM suggestion feed, and the reference cards (targets, symptoms, risks).
 * Driven by [NutrientPageViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutrientDetailScreen(
    nutrientId: String,
    onBack: () -> Unit,
    vm: NutrientPageViewModel = viewModel()
) {
    LaunchedEffect(nutrientId) { vm.open(nutrientId) }
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.def.name.ifBlank { "Nutrient" },
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(8.dp))
                        TierChip(state.def.tier)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- Today + grade header --------------------------------------
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            state.grade?.let { g ->
                                GradeBadge(g.grade, g.isExcess)
                                Spacer(Modifier.width(12.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                if (state.hasTarget) {
                                    val pct = percentOf(state.target, state.intakeToday)
                                    Text(
                                        "Today: ${formatNutrient(state.intakeToday, state.def.unit)} " +
                                            "of ${formatNutrient(state.target, state.def.unit)} · $pct% of target",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (state.intakeToday < state.target) {
                                        Text(
                                            "${formatNutrient(state.target - state.intakeToday, state.def.unit)} to go",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                } else {
                                    Text(
                                        "Today: ${formatNutrient(state.intakeToday, state.def.unit)} " +
                                            "(no target set)",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                        if (state.windows.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                state.windows.joinToString("  ·  ") { w ->
                                    if (!w.hasData) "${w.label}: no data"
                                    else if (!w.hasTarget) "${w.label}: ${formatNutrient(w.intake, w.unit)}/day avg"
                                    else "${w.label}: ${w.pct}% of target"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ---- Trend chart with range selector ---------------------------
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        SectionHeader(
                            "Intake over time",
                            "Daily totals with your target as the dashed line."
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(7, 30, 90, 365, 0).forEach { days ->
                                FilterChip(
                                    selected = state.rangeDays == days,
                                    onClick = { vm.setRange(days) },
                                    label = { Text(if (days == 0) "All" else if (days == 365) "1y" else "${days}d") }
                                )
                            }
                        }
                        if (state.seriesFrom.isNotBlank()) {
                            ChartTimeNavRow(
                                label = shortRangeLabel(state.seriesFrom, state.seriesTo),
                                canGoBack = state.rangeDays > 0 && state.endOffset < state.maxOffset,
                                canGoForward = state.endOffset > 0,
                                onBack = vm::panBack,
                                onForward = vm::panForward
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        if (state.series.none { it.intake != null }) {
                            Text(
                                "Nothing logged in this window — log meals or connect Tail to see the trend.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .chartPan(
                                        enabled = state.rangeDays > 0 &&
                                            (state.endOffset < state.maxOffset || state.endOffset > 0)
                                    ) { older -> if (older) vm.panBack() else vm.panForward() }
                            ) {
                                LineChart(
                                    points = state.series.map { it.intake },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp),
                                    lineColor = MaterialTheme.colorScheme.primary,
                                    referenceLine = state.target.takeIf { it > 0 },
                                    referenceLabel = if (state.hasTarget) "target" else "",
                                    axisLabels = true,
                                    xLabels = state.series.map { shortDayLabel(it.day) },
                                    yLabelFormat = { formatNutrient(it, state.def.unit) },
                                    contentDescriptionText = "Daily ${state.def.name} intake"
                                )
                            }
                            Text(
                                "Swipe the chart or use the arrows to browse through time.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ---- Consistency stats -------------------------------------------
            item {
                if (state.daysTracked > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatCell(
                            "Avg / tracked day",
                            formatNutrient(state.avgPerDay, state.def.unit),
                            Modifier.weight(1f)
                        )
                        StatCell(
                            "Days tracked",
                            "${state.daysTracked}/" +
                                (if (state.rangeDays == 0) "all" else "${state.rangeDays}"),
                            Modifier.weight(1f)
                        )
                        StatCell(
                            if (state.isLimitTracker) "Over-cap days" else "Low days",
                            "${state.lowDays}",
                            Modifier.weight(1f)
                        )
                        StatCell(
                            if (state.isLimitTracker) "Under-cap streak" else "On-target streak",
                            "${state.streak}d",
                            Modifier.weight(1f)
                        )
                    }
                }
            }

            // ---- Usual sources ------------------------------------------------
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        SectionHeader(
                            "Where you get it",
                            "Your top sources over the last 30 days (meals 💊 and supplements)."
                        )
                        Spacer(Modifier.height(8.dp))
                        if (state.usualSources.isEmpty()) {
                            Text(
                                "No logged sources yet — this fills in as you log meals.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        state.usualSources.forEach { src ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (src.isSupplement) "💊" else foodEmoji(src.name),
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        src.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "${src.sharePct}% · ${formatNutrient(src.total, state.def.unit)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(3.dp))
                                LinearProgressIndicator(
                                    progress = { (src.sharePct / 100f).coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(5.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                )
                            }
                        }
                    }
                }
            }

            // ---- Fresh ideas (diet-aware) --------------------------------------
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        SectionHeader(
                            "Rich in ${state.def.name} — worth trying",
                            "Fits your ${state.dietStyle} profile and your allergies. " +
                                "Ranked by density per 100 g."
                        )
                        Spacer(Modifier.height(8.dp))
                        if (state.freshFoods.isEmpty()) {
                            Text(
                                "No new ideas yet — log a few more days so Hoot can " +
                                    "learn what you already eat.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        state.freshFoods.forEach { food ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    foodEmoji(food.name),
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.size(28.dp),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        food.name.replaceFirstChar(Char::uppercase),
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        when (food.origin) {
                                            FreshOrigin.KNOWLEDGE ->
                                                if (food.pctPer100g != null)
                                                    "≈${food.pctPer100g}% of target per 100 g · from your food library"
                                                else "from your food library"
                                            FreshOrigin.CURATED -> "curated for your diet"
                                            FreshOrigin.SEED -> "common source"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---- Suggestions feed (LLM, same engine as before) ------------------
            item {
                SectionHeader(
                    "Foods high in ${state.def.name}",
                    "Personalized picks — more on demand."
                )
            }
            if (state.suggestions.isEmpty() && !state.loadingMore) {
                item {
                    Text(
                        if (state.llmUnavailable) {
                            "No local suggestions yet — connect an LLM in Settings to generate ideas."
                        } else "Loading suggestions…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(state.suggestions, key = { it.id }) { rec ->
                SuggestionRow(rec)
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.loadingMore) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Finding more…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        OutlinedButton(onClick = vm::loadMore) {
                            Text("More suggestions")
                        }
                    }
                }
            }
            state.error?.let { err ->
                item {
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ---- Reference cards -------------------------------------------------
            item { TargetsCard(state) }
            state.def.deficiencySymptoms?.takeIf { it.isNotBlank() }?.let { sym ->
                item { EffectsCard("Signs you're too low", sym) }
            }
            state.def.excessRisks?.takeIf { it.isNotBlank() }?.let { risks ->
                item { EffectsCard("If you consistently overshoot", risks) }
            }
        }
    }
}

/** Big letter badge + one-line situation (mirrors the report card semantics). */
@Composable
private fun GradeBadge(grade: Grade, isExcess: Boolean) {
    val color = when (grade) {
        Grade.F -> MaterialTheme.colorScheme.error
        Grade.D -> ScoreLow
        Grade.C -> ScoreMid
        Grade.B -> ScoreHigh.copy(alpha = 0.75f)
        Grade.A -> ScoreHigh
    }
    val situation = when {
        isExcess && grade != Grade.A -> "too high"
        grade == Grade.A -> "doing great"
        else -> "too low"
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .background(color.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(grade.name, style = MaterialTheme.typography.headlineSmall, color = color)
        }
        Text(
            situation,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Compact stat tile used in the consistency row. */
@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** RDA / custom goal / upper-limit reference card. */
@Composable
private fun TargetsCard(state: NutrientPageState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionHeader("Targets & limits")
            state.def.rdaValue?.let {
                Text(
                    "Recommended daily amount: ${formatNutrient(it, state.def.unit)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.def.ulValue?.let {
                Text(
                    "Upper limit: ${formatNutrient(it, state.def.unit)} — staying below is safest",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                if (state.hasTarget)
                    "Your current target: ${formatNutrient(state.target, state.def.unit)}" +
                        if (state.isLimitTracker) " (limit tracker — less is better)" else ""
                else "No target set — add a goal in Settings to track this nutrient",
                style = MaterialTheme.typography.bodySmall
            )
            if (state.foodSourcesLine.isNotBlank()) {
                Text(
                    "Common sources: ${state.foodSourcesLine}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Deficiency-symptom / excess-risk explainer card. */
@Composable
private fun EffectsCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                body.trimIndent().trim(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** One LLM/cache food suggestion (image with emoji fallback) — display-only. */
@Composable
private fun SuggestionRow(rec: RecommendationEntity) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (rec.imageUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(rec.imageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = rec.foodName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else {
                Text(
                    foodEmoji(rec.foodName),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.size(52.dp),
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    rec.foodName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    rec.reasonText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
