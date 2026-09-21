package com.example.hoot.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hoot.appGraph
import com.example.hoot.data.local.entity.SourceEntity
import com.example.hoot.domain.insights.SmartFoodPick
import com.example.hoot.domain.insights.SmartNutrientHit
import com.example.hoot.ui.common.EmptyState
import com.example.hoot.ui.common.SectionHeader
import com.example.hoot.ui.common.formatNutrient
import com.example.hoot.ui.common.foodEmoji
import com.example.hoot.ui.theme.ScoreHigh

/**
 * Feature C — "Smart picks for you": the TOP section of Home. Foods that hit
 * MULTIPLE current gaps while avoiding excess/limit-tracker nutrients,
 * scored by [com.example.hoot.domain.insights.SmartFoodMatcher] (pure,
 * cache-first, instant). Vertical list (2026-09: widened to ~12 picks, the
 * old horizontal 6-card strip hid most suggestions); tap row → detail sheet.
 *
 * Feedback 2026-09: the dashboard keeps the curated top slice; a "See all"
 * action opens [AllSmartPicksSheet] — the FULL deficiency-keyed ranking
 * ([com.example.hoot.domain.insights.SmartFoodProvider.SmartPicksResult.allPicks])
 * with the current gaps summarized up top.
 */
@Composable
fun SmartPicksSection(
    picks: List<SmartFoodPick>,
    allPicks: List<SmartFoodPick> = emptyList(),
    cacheCold: Boolean,
    loading: Boolean,
    onOpenPick: (SmartFoodPick) -> Unit,
    onSeeAll: () -> Unit = {}
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(
            "Smart picks for you",
            "Foods that cover several of your gaps at once."
        )
        Spacer(Modifier.height(8.dp))
        when {
            picks.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                picks.forEach { pick -> SmartPickRow(pick, onClick = { onOpenPick(pick) }) }
                if (allPicks.size > picks.size) {
                    TextButton(onClick = onSeeAll) {
                        Text("See all ${allPicks.size} recommendations")
                    }
                }
            }
            cacheCold && !loading -> Card(Modifier.fillMaxWidth()) {
                EmptyState(
                    emoji = "🛒",
                    title = "Building your smart picks",
                    body = "As Hoot learns your foods, this shows the ones that cover " +
                        "several of your gaps at once. Log a few meals first.",
                    modifier = Modifier.padding(0.dp)
                )
            }
            // else: loading or no gaps → show nothing (Focus now handles gaps).
        }
    }
}

/**
 * Full recommendations sheet (feedback 2026-09): EVERY scored food for the
 * current long-term deficiencies, ranked best-first, with the gap list it is
 * keyed to. Rows reuse [SmartPickRow]; tapping one opens the same detail
 * sheet as the dashboard cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllSmartPicksSheet(
    picks: List<SmartFoodPick>,
    gaps: List<com.example.hoot.domain.insights.FocusNowItem>,
    loading: Boolean,
    onOpenPick: (SmartFoodPick) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Column {
                    Text(
                        "All smart recommendations",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (gaps.isEmpty()) "No active gaps right now."
                        else "Keyed to your current gaps: " +
                            gaps.joinToString { g -> g.name } + ". " +
                            "Ranked by how much of each deficit one serving covers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (picks.isEmpty() && !loading) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        EmptyState(
                            emoji = "🛒",
                            title = "Nothing to recommend yet",
                            body = "Once foods are analyzed and gaps exist, every matching " +
                                "food shows up here ranked for your deficiencies.",
                            modifier = Modifier.padding(0.dp)
                        )
                    }
                }
            }
            items(picks.size) { i ->
                SmartPickRow(picks[i], onClick = { onOpenPick(picks[i]) })
            }
        }
    }
}

/** One suggestion row: emoji, name, hits summary, serving + caution chip. */
@Composable
private fun SmartPickRow(pick: SmartFoodPick, onClick: () -> Unit) {
    val summary = pick.hitsSummary()
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "${pick.displayName}: $summary. Tap for details."
            }
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                pick.emojiHint ?: foodEmoji(pick.displayName),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    pick.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = ScoreHigh,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "${pick.servingGrams.toInt()} g",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (pick.cautions.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                AssistChip(
                    onClick = onClick,
                    label = {
                        Text(
                            pick.cautions.first(),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    },
                    modifier = Modifier.height(24.dp)
                )
            }
        }
    }
}

/**
 * Detail bottom sheet for one smart pick: full nutrient-hit list with % of
 * the remaining daily deficit covered per nutrient, serving size used,
 * sources (via the lookup-cache → sources chain when cached), and the
 * deterministic "why this" line.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartPickDetailSheet(
    pick: SmartFoodPick,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var sources by remember { mutableStateOf<List<SourceEntity>>(emptyList()) }
    LaunchedEffect(pick.foodId) {
        // Sources only exist for cache-resolved foods (llm:* ids have none).
        val graph = context.appGraph
        runCatching {
            val cache = graph.nutrients.lookupKeyForFood(pick.foodId) ?: return@runCatching
            sources = graph.nutrients.sourcesForLookup(cache.normalizedKey)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pick.emojiHint ?: foodEmoji(pick.displayName),
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        pick.displayName,
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(Modifier.weight(1f))
                    if (pick.source == "llm") {
                        AssistChip(onClick = {}, label = { Text("AI pick") })
                    }
                }
            }
            item {
                Text(
                    pick.why,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                Text(
                    "One serving: ${pick.servingGrams.toInt()} g covers:",
                    style = MaterialTheme.typography.titleSmall
                )
            }
            items(pick.hits, key = { it.nutrientId }) { hit ->
                NutrientHitRow(hit)
            }
            if (pick.cautions.isNotEmpty()) {
                item {
                    Text(
                        "Heads up: ${pick.cautions.joinToString(", ")}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (sources.isNotEmpty()) {
                item {
                    Text("Sources", style = MaterialTheme.typography.titleSmall)
                }
                items(sources, key = { it.id }) { src ->
                    Text(
                        "• ${src.publisher ?: src.title ?: src.url}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** One hit row: nutrient name + % of the remaining daily deficit covered. */
@Composable
private fun NutrientHitRow(hit: SmartNutrientHit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            hit.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            "${formatNutrient(hit.servingAmount, hit.unit)} · ${(hit.deficitCovered * 100).toInt()}%",
            style = MaterialTheme.typography.labelLarge,
            color = ScoreHigh
        )
    }
}
