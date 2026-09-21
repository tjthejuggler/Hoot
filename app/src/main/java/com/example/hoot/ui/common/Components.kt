package com.example.hoot.ui.common

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.hoot.domain.score.ScoreStatus
import com.example.hoot.ui.theme.ScoreHigh
import com.example.hoot.ui.theme.ScoreLow
import com.example.hoot.ui.theme.ScoreMid

/** Status → color (theme accents, dark-theme safe). */
@Composable
fun statusColor(status: ScoreStatus): Color = when (status) {
    ScoreStatus.MET -> ScoreHigh
    ScoreStatus.CLOSE -> ScoreMid
    ScoreStatus.LOW -> ScoreLow
    ScoreStatus.EXCESS -> MaterialTheme.colorScheme.error
}

/** Small tier badge: T1 (critical) / T2 (important) / T3 (nice-to-have). */
@Composable
fun TierChip(tier: Int, modifier: Modifier = Modifier) {
    val (bg, fg) = when (tier) {
        1 -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        2 -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val desc = when (tier) {
        1 -> "Tier 1, critical nutrient"
        2 -> "Tier 2, important nutrient"
        else -> "Tier 3, nice to have"
    }
    Text(
        "T$tier",
        modifier = modifier
            .background(bg.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .semantics { contentDescription = desc },
        style = MaterialTheme.typography.labelSmall,
        color = fg
    )
}

/**
 * One nutrient progress row (overhaul feedback #1): name + tier chip, intake
 * vs target with unit, % bar in status color. The WHOLE row navigates
 * directly to the food-suggestions detail sheet — no separate hint button.
 */
@Composable
fun NutrientProgressRow(
    name: String,
    tier: Int,
    unit: String,
    intake: Double,
    target: Double,
    coverage: Double,
    status: ScoreStatus,
    emphasize: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val color = statusColor(status)
    val pct = percentOf(target, intake).coerceAtMost(999)
    Column(
        (onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics {
                contentDescription =
                    "$name, tier $tier: ${formatNutrient(intake, unit)} of " +
                        "${formatNutrient(target, unit)} target, $pct percent"
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TierChip(tier)
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                style = if (emphasize) MaterialTheme.typography.titleSmall
                else MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${formatNutrient(intake, unit)} / ${formatNutrient(target, unit)} · $pct%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { coverage.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = color,
            trackColor = color.copy(alpha = 0.15f)
        )
    }
}

/** Consistent section header with an optional plain-language caption. */
@Composable
fun SectionHeader(title: String, caption: String? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (caption != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Meaningful empty state: icon/emoji, title, body, optional action. */
@Composable
fun EmptyState(
    emoji: String,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(emoji, style = MaterialTheme.typography.displaySmall)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Small colored status dot (insight severity / sync chips). */
@Composable
fun StatusDot(color: Color, size: Int = 8) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(size.dp)
            .background(color, CircleShape)
    )
}
