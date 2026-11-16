package com.example.hoot.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hoot.domain.insights.SymptomMatcher

/**
 * "Likely symptoms" section (deficiency system, 2026-11-16): symptoms the
 * user most plausibly experiences right now, derived from multi-window
 * intake coverage (DeficiencyModel) × nutrient-symptom associations
 * (SymptomMatcher). Multi-cause symptoms outrank single-nutrient ones.
 */
/** Fixed height of the scrollable symptom list inside the section. */
private val SYMPTOM_LIST_HEIGHT = 300.dp

@Composable
fun LikelySymptomsCard(
    symptoms: List<SymptomMatcher.RankedSymptom>,
    onOpenSymptom: (String) -> Unit
) {
    androidx.compose.foundation.layout.Column(
        Modifier.padding(top = 16.dp, bottom = 16.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.example.hoot.ui.common.SectionHeader(
                title = "Likely symptoms",
                caption = "From your intake patterns over the last week → year."
            )
        }
        Spacer(Modifier.height(10.dp))
        // All ranked symptoms, scrollable INSIDE the fixed-height section
        // (feedback 2026-11-16: was 6 clipped-height rows, header jammed
        // against the card top, last row's text cut off).
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(SYMPTOM_LIST_HEIGHT),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, bottom = 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(symptoms.size) { i ->
                val s = symptoms[i]
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSymptom(s.symptomKey) }
                        .padding(vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            s.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${(s.likelihood * 100).toInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { s.likelihood.toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (s.contributors.size == 1)
                            "via ${s.contributors.first().nutrientName}"
                        else "${s.contributors.size} nutrients: " +
                            s.contributors.take(3).joinToString { it.nutrientName } +
                            if (s.contributors.size > 3) "…" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (symptoms.isEmpty()) {
                item {
                    Text(
                        "No notable deficiency patterns — keep logging.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

/**
 * Symptom detail popup: every OTHER nutrient associated with the symptom,
 * ordered by the user's own deficiency likelihood (strongest first).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SymptomDetailSheet(
    symptomKey: String,
    display: String,
    nutrients: List<SymptomMatcher.Contributor>,
    onOpenNutrient: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, bottom = 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column {
                    Text(display, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Nutrients linked to this symptom, ordered by how likely " +
                            "YOU are low in them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (nutrients.isEmpty()) {
                item {
                    Text(
                        "No linked nutrients with current gaps — this symptom is " +
                            "unlikely to be diet-related right now.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            items(nutrients.size) { i ->
                val n = nutrients[i]
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenNutrient(n.nutrientId) }
                        .padding(vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(n.nutrientName, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "target ${"%.4g".format(n.target)} ${n.unit}/day",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "${(n.deficiencyLikelihood * 100).toInt()}% likely low",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    LinearProgressIndicator(
                        progress = { n.deficiencyLikelihood.toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    )
                    if (i < nutrients.size - 1) Spacer(Modifier.height(6.dp))
                    if (i < nutrients.size - 1) HorizontalDivider()
                }
            }
        }
    }
}
