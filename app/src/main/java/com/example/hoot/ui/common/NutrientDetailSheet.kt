package com.example.hoot.ui.common

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.hoot.data.local.entity.RecommendationEntity

/**
 * Informational nutrient detail sheet (overhaul feedback #3): how much is
 * lacking, why the nutrient matters (deficiency symptoms from the seed
 * definition), and FOOD SUGGESTIONS with per-food coverage — cache-first,
 * "More suggestions" on demand ([NutrientDetailViewModel]). Suggestions are
 * display-only: intake is recorded via Tail / the in-app Intake tab, not by
 * manual marking here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutrientDetailSheet(
    vm: NutrientDetailViewModel,
    onDismiss: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TierChip(state.def.tier)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        state.def.name,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            item {
                val pct = percentOf(state.target, state.intake)
                Text(
                    "So far today: ${formatNutrient(state.intake, state.def.unit)} of " +
                        "${formatNutrient(state.target, state.def.unit)} · $pct% of target" +
                        if (state.intake < state.target) {
                            " · ${formatNutrient(state.target - state.intake, state.def.unit)} to go"
                        } else "",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            state.def.deficiencySymptoms?.takeIf { it.isNotBlank() }?.let { symptoms ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Why it matters", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                symptoms.trimIndent().trim(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    state.def.name.takeIf { it.isNotBlank() }
                        ?.let { "Foods high in $it" } ?: "Food suggestions",
                    style = MaterialTheme.typography.titleSmall
                )
            }
            if (state.suggestions.isEmpty() && !state.loadingMore) {
                item {
                    Text(
                        if (state.llmUnavailable) {
                            "No local suggestions yet — connect an LLM in Settings to generate ideas, " +
                                "or log foods rich in ${state.def.name} manually."
                        } else {
                            "Loading suggestions…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(state.suggestions, key = { it.id }) { rec ->
                SuggestionRow(rec = rec)
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
        }
    }
}

/** One food suggestion with image/emoji + coverage reason (display-only). */
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
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else {
                Text(
                    foodEmoji(rec.foodName),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.size(56.dp),
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

/** Severity → accent used by insight rows in both Home and Insights. */
fun insightAccent(severity: String, error: Color, warn: Color, info: Color): Color = when (severity) {
    "CRITICAL" -> error
    "WARNING" -> warn
    else -> info
}
