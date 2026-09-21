package com.example.hoot.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
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
 * cache-first, instant). Horizontal strip of cards; tap → detail sheet.
 */
@Composable
fun SmartPicksSection(
    picks: List<SmartFoodPick>,
    cacheCold: Boolean,
    loading: Boolean,
    onOpenPick: (SmartFoodPick) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(
            "Smart picks for you",
            "Foods that cover several of your gaps at once."
        )
        Spacer(Modifier.height(8.dp))
        when {
            picks.isNotEmpty() -> Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                picks.forEach { pick -> SmartPickCard(pick, onClick = { onOpenPick(pick) }) }
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

/** One food card in the horizontal strip: emoji, name, hits summary, caution chip. */
@Composable
private fun SmartPickCard(pick: SmartFoodPick, onClick: () -> Unit) {
    val summary = pick.hitsSummary()
    Card(
        Modifier
            .width(150.dp)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "${pick.displayName}: $summary. Tap for details."
            }
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                pick.emojiHint ?: foodEmoji(pick.displayName),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
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
            if (pick.cautions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
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
            if (pick.source == "llm") {
                Spacer(Modifier.height(4.dp))
                Text(
                    "AI pick",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
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
