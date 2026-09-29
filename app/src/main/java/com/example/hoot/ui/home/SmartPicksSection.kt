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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import com.example.hoot.domain.nutrition.FoodKnowledgeIndex
import com.example.hoot.domain.nutrition.SeedFoodLibrary
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
 *
 * Feedback 2026-09-27 (knowledge base): the sheet now also carries the
 * "search online" action — one gap-targeted LLM discovery pass that adds NEW
 * foods rich in exactly the current gaps into the permanent food-knowledge
 * base, from where they immediately join the recommendation pool.
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
                // "More" entry point (feedback 2026-09-21): ALWAYS available —
                // the popup is the full ranked list keyed to the current
                // gaps, with "Show more" windowing and a "Generate even
                // more" deep pass when the pool holds extra candidates.
                TextButton(onClick = onSeeAll) {
                    Text(
                        if (allPicks.size > picks.size) "See all ${allPicks.size} recommendations"
                        else "More recommendations"
                    )
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
/**
 * Full recommendations sheet (feedback 2026-09-21 "show more"): opens with a
 * windowed slice of the ranked list and a "Show more" button that keeps
 * growing it; when the standard ranking is exhausted, a deeper pass
 * ([deepPicks] — quality floor fully relaxed, still instant + zero LLM)
 * extends the list further.
 *
 * Feedback 2026-09-27 (knowledge base): bigger default window, a live
 * knowledge-base counter in the header, and a persistent "search online"
 * action — [onResearch] runs the gap-targeted discovery pass whose findings
 * land in the permanent food-knowledge base and join this list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllSmartPicksSheet(
    picks: List<SmartFoodPick>,
    gaps: List<com.example.hoot.domain.insights.FocusNowItem>,
    loading: Boolean,
    onOpenPick: (SmartFoodPick) -> Unit,
    onDismiss: () -> Unit,
    deepPicks: List<SmartFoodPick> = emptyList(),
    knowledgeCount: Int = 0,
    researching: Boolean = false,
    onResearch: () -> Unit = {}
) {
    // Continuous growth (quality rework 2026-09-22): "Show more" keeps
    // revealing the ranked list in windows; when the standard floor runs
    // out, the button switches to the deep pass (floor 0) and keeps going —
    // the user can ALWAYS get more while candidates remain, with one honest
    // end-state line when the pool is truly exhausted.
    var showDeep by remember { mutableStateOf(false) }
    var visibleCount by remember { mutableStateOf(SHEET_WINDOW) }
    val source = if (showDeep && deepPicks.size > picks.size) deepPicks else picks
    val visible = source.take(visibleCount)
    val exhausted = visibleCount >= source.size &&
        (!showDeep || deepPicks.size <= picks.size)
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
                    if (knowledgeCount > 0) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Your food knowledge base: $knowledgeCount researched " +
                                "food${if (knowledgeCount == 1) "" else "s"} and growing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ScoreHigh
                        )
                    }
                }
            }
            if (source.isEmpty() && !loading) {
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
            items(visible.size) { i ->
                SmartPickRow(visible[i], onClick = { onOpenPick(visible[i]) })
            }
            if (visibleCount < source.size) {
                item {
                    TextButton(onClick = { visibleCount += SHEET_WINDOW }) {
                        Text("Show more (${source.size - visibleCount} left)")
                    }
                }
            } else if (!showDeep && deepPicks.size > picks.size) {
                item {
                    TextButton(onClick = { showDeep = true; visibleCount += SHEET_WINDOW }) {
                        Text("Show more — keep going (+${deepPicks.size - picks.size})")
                    }
                }
            } else if (exhausted && source.isNotEmpty()) {
                item {
                    Text(
                        "That's every food in your library that matches your " +
                            "current gaps — so far.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // Knowledge-base growth (feedback 2026-09-27): always available
            // (even before exhaustion) — discovery adds NEW foods rich in the
            // exact current gaps to the permanent knowledge base; they appear
            // here as soon as the refresh tick fires.
            item {
                Column {
                    TextButton(onClick = onResearch, enabled = !researching && gaps.isNotEmpty()) {
                        Text(
                            if (researching) "Searching online…"
                            else "🔎 Find more foods online for these gaps"
                        )
                    }
                    if (researching) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp), strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Researching foods rich in your gaps — new finds land " +
                                    "in your knowledge base automatically.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Rows shown per "Show more" step in [AllSmartPicksSheet]. */
private const val SHEET_WINDOW = 25

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
 * Detail bottom sheet for one smart pick (EXPANDED, feedback 2026-09-27):
 *  - the full "high in" knowledge-base summary (not just the current gaps),
 *  - the complete per-100 g nutrition panel,
 *  - gap hits with % of the remaining daily deficit covered,
 *  - serving size, cautions, sources (cache-resolved foods),
 *  - RELATED picks — other foods from the full ranking that cover the same
 *    gaps, one tap to swap the sheet content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartPickDetailSheet(
    pick: SmartFoodPick,
    onDismiss: () -> Unit,
    allPicks: List<SmartFoodPick> = emptyList(),
    onOpenPick: ((SmartFoodPick) -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var sources by remember { mutableStateOf<List<SourceEntity>>(emptyList()) }
    var per100 by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var highIn by remember { mutableStateOf<List<String>>(emptyList()) }
    var namesById by remember { mutableStateOf<Map<String, Pair<String, String>>>(emptyMap()) }

    LaunchedEffect(pick.foodId) {
        val graph = context.appGraph
        runCatching {
            namesById = graph.nutrients.definitionsAll()
                .associate { it.id to (it.name to it.unit) }
            when {
                pick.foodId.startsWith("kb:") -> {
                    graph.foodKnowledge.byName(pick.foodId.removePrefix("kb:"))?.let { row ->
                        per100 = FoodKnowledgeIndex.valuesFromJson(row.valuesJson)
                        highIn = FoodKnowledgeIndex.fromJson(row.highInJson)
                    }
                }
                pick.foodId.startsWith("seed:") -> {
                    SeedFoodLibrary.lookup(pick.foodId.removePrefix("seed:"))?.let { seed ->
                        per100 = seed.per100
                        highIn = FoodKnowledgeIndex.highIn(
                            seed.per100,
                            graph.nutrients.definitionsAll()
                                .associate { it.id to (it.rdaValue ?: 0.0) }
                        )
                    }
                }
                else -> {
                    graph.foods.profileForFood(pick.foodId)?.let { profile ->
                        per100 = per100FromProfile(profile.valuesJson, profile.perAmount)
                        highIn = FoodKnowledgeIndex.fromJson(
                            graph.foodKnowledge.byName(
                                graph.nutrients.lookupKeyForFood(pick.foodId)?.normalizedKey ?: ""
                            )?.highInJson ?: "[]"
                        )
                    }
                    val cache = graph.nutrients.lookupKeyForFood(pick.foodId) ?: return@runCatching
                    sources = graph.nutrients.sourcesForLookup(cache.normalizedKey)
                }
            }
        }
    }

    // Related picks (feedback 2026-09-27): foods covering ≥1 of THIS pick's
    // gap hits, ranked position order (best-first already), excluding self.
    val related = remember(pick.foodId, allPicks) {
        val hitIds = pick.hits.map { it.nutrientId }.toSet()
        allPicks
            .filter { it.foodId != pick.foodId }
            .filter { cand -> cand.hits.any { it.nutrientId in hitIds } }
            .take(RELATED_LIMIT)
    }

    // Full panel rows: richest first, deterministic tiebreak.
    val panelRows = remember(per100) {
        per100.entries.sortedWith(
            compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }
        )
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
                    } else if (pick.foodId.startsWith("kb:")) {
                        AssistChip(onClick = {}, label = { Text("Researched") })
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
            // Knowledge-base "high in" summary (feedback 2026-09-27).
            if (highIn.isNotEmpty()) {
                item {
                    val names = highIn.map { id -> namesById[id]?.first ?: id }
                    Text(
                        "High in: ${names.joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ScoreHigh
                    )
                }
            }
            item {
                Text(
                    "One serving: ${pick.servingGrams.toInt()} g covers:",
                    style = MaterialTheme.typography.titleSmall
                )
            }
            items(pick.hits, key = { "hit_" + it.nutrientId }) { hit ->
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
            // Full per-100 g panel (feedback 2026-09-27 "show a lot more").
            if (panelRows.isNotEmpty()) {
                item { HorizontalDivider() }
                item {
                    Text(
                        "Full nutrition panel — per 100 g",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                items(panelRows.size, key = { "panel_" + panelRows[it].key }) { i ->
                    val (id, amount) = panelRows[i]
                    val (name, unit) = namesById[id] ?: (prettyId(id) to guessUnit(id))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Text(
                            name,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${formatNutrient(amount, unit)} $unit",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (id in highIn) ScoreHigh
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (sources.isNotEmpty()) {
                item {
                    Text("Sources", style = MaterialTheme.typography.titleSmall)
                }
                items(sources, key = { "src_" + it.id }) { src ->
                    Text(
                        "• ${src.publisher ?: src.title ?: src.url}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // Related picks (feedback 2026-09-27): more foods for the same gaps.
            if (related.isNotEmpty() && onOpenPick != null) {
                item {
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "More foods for these gaps",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                items(related.size, key = { "rel_" + related[it].foodId }) { i ->
                    val rel = related[i]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenPick(rel) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            rel.emojiHint ?: foodEmoji(rel.displayName),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            rel.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            rel.hitsSummary(),
                            style = MaterialTheme.typography.labelSmall,
                            color = ScoreHigh,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** Max related-food rows in the detail sheet. */
private const val RELATED_LIMIT = 8

/** Scales a stored profile JSON to per-100 g (profiles may carry perAmount). */
private fun per100FromProfile(valuesJson: String, perAmount: Double): Map<String, Double> {
    val raw = FoodKnowledgeIndex.valuesFromJson(valuesJson)
    if (perAmount <= 0.0 || perAmount == 100.0) return raw
    val factor = 100.0 / perAmount
    return raw.mapValues { it.value * factor }
}

/** "omega3_epa_dha" → "Omega3 Epa Dha" for ids without a seeded definition. */
private fun prettyId(id: String): String =
    id.split('_', '-').filter { it.isNotBlank() }
        .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercaseChar() } }

/** Best-effort unit guess for ids missing from the definitions table. */
private fun guessUnit(id: String): String = when {
    id.endsWith("_g") || id in setOf("protein", "carbohydrates", "total_fat", "fiber") -> "g"
    id.startsWith("vitamin_") && id in setOf("vitamin_b12", "vitamin_d") -> "mcg"
    else -> "mg"
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
