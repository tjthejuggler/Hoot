package com.example.hoot.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hoot.domain.insights.Insight
import com.example.hoot.domain.insights.InsightSeverity
import com.example.hoot.ui.common.TierChip
import com.example.hoot.ui.common.formatNutrient
import com.example.hoot.ui.theme.ScoreLow
import com.example.hoot.ui.theme.ScoreMid

/** Row/chip-level composables of the Home dashboard (extracted from HomeScreen). */

/** Focus-now row: name + tier chip + "so far today" progress + 7-day hint. */
@Composable
internal fun FocusNowRow(
    name: String,
    tier: Int,
    unit: String,
    todayIntake: Double,
    target: Double,
    todayCoverage: Double,
    recentCoverage: Double,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TierChip(tier)
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                "7-day avg ${(recentCoverage * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = ScoreLow
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${formatNutrient(todayIntake, unit)} of ${formatNutrient(target, unit)} today",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { todayCoverage.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = ScoreMid,
            trackColor = ScoreMid.copy(alpha = 0.15f)
        )
    }
}

@Composable
internal fun InsightRow(insight: Insight) {
    val badgeColor = when (insight.severity) {
        InsightSeverity.CRITICAL -> MaterialTheme.colorScheme.error
        InsightSeverity.WARNING -> ScoreMid
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
internal fun NutritionProcessingChip(
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
