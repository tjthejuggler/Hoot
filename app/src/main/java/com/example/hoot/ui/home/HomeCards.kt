package com.example.hoot.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.hoot.ui.charts.ProgressRing
import com.example.hoot.ui.charts.Sparkline
import com.example.hoot.ui.charts.scoreColor
import com.example.hoot.ui.common.SectionHeader
import com.example.hoot.ui.common.formatNutrient
import com.example.hoot.ui.common.prettyDay
import com.example.hoot.ui.theme.ScoreHigh
import com.example.hoot.ui.theme.ScoreLow
import com.example.hoot.ui.theme.ScoreMid

/** Card-level composables of the Home dashboard (extracted from HomeScreen). */

@Composable
internal fun ScoreCard(state: HomeUiState, onOpenInsights: () -> Unit, onExplain: () -> Unit) {
    val snapshot = state.score
    val score = snapshot?.score ?: 0.0
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Self-explanatory ring: big number + caption, tap → explainer.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.clickable(onClick = onExplain)
            ) {
                ProgressRing(
                    progress = score / 100.0,
                    modifier = Modifier.size(110.dp),
                    color = scoreColor(score, high = ScoreHigh, mid = ScoreMid, low = ScoreLow)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "%.0f".format(score),
                        style = MaterialTheme.typography.headlineLarge
                    )
                    Text(
                        "/100",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Daily score",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (snapshot == null) "Waiting for data"
                        else "${snapshot.nutrientsMet}/${snapshot.nutrientsTracked} targets met",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onExplain, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = "What does this score mean?",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (snapshot == null) {
                    Text(
                        "Score appears once today's data lands.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
internal fun CaloriesCard(state: HomeUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SectionHeader("Energy & macros", "Quick stats for today.")
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

/** "Consumed so far" summary: energy, macros, water and entry counts. */
@Composable
internal fun ConsumedSummaryCard(
    state: HomeUiState,
    isToday: Boolean,
    waterL: Double,
    mealCount: Int,
    supplementCount: Int,
    otherEntryCount: Int,
    onClick: () -> Unit
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(16.dp)) {
            SectionHeader(
                if (isToday) "Consumed so far today" else "Consumed that day",
                prettyDay(state.day)
            )
            Spacer(Modifier.height(8.dp))
            val nothing = state.calories == null &&
                state.macros.all { it.intake <= 0.0 } && waterL <= 0.0 &&
                mealCount == 0 && supplementCount == 0 && otherEntryCount == 0
            if (nothing) {
                Text(
                    "Nothing logged for this day yet — use “+ Add”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.calories
                                ?.let { (kcal, unit) -> formatNutrient(kcal, unit) } ?: "0 kcal",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Energy",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.macros.forEach { m ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(start = 12.dp)
                        ) {
                            Text(formatNutrient(m.intake, m.unit), style = MaterialTheme.typography.titleSmall)
                            Text(
                                m.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (waterL > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "💧 ${formatNutrient(waterL, "L")} water",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                val parts = buildList {
                    if (mealCount > 0) add("$mealCount meal${if (mealCount == 1) "" else "s"}")
                    if (supplementCount > 0) {
                        add("$supplementCount supplement${if (supplementCount == 1) "" else "s"}")
                    }
                    if (otherEntryCount > 0) add("$otherEntryCount other entries")
                }
                if (parts.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        parts.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tap for everything consumed this day",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
