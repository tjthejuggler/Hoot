package com.example.hoot.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.error
import com.example.hoot.data.local.entity.RecommendationEntity
import com.example.hoot.domain.insights.Insight
import com.example.hoot.domain.insights.InsightSeverity
import com.example.hoot.ui.common.TierChip
import com.example.hoot.ui.common.foodEmoji

/** Card composables of the Insights screen (extracted from InsightsScreen). */

@Composable
internal fun InsightCard(insight: Insight, tier: Int?, dietFilter: com.example.hoot.domain.insights.DietTextFilter) {
    // Render-boundary diet gate (diet-fix hardening, 2026-09): the message —
    // including the "Good sources: …" tail — is re-filtered on the way OUT
    // so stale/persisted omnivore-era text can never reach the user.
    val safeMessage = com.example.hoot.domain.insights.DietAwareSources
        .sanitizeForDisplay(insight.message, dietFilter.toProfile())
    val severityColor = when (insight.severity) {
        InsightSeverity.CRITICAL -> MaterialTheme.colorScheme.error
        InsightSeverity.WARNING -> Color(0xFFF2C94C)
        InsightSeverity.INFO -> MaterialTheme.colorScheme.primary
    }
    val severityLabel = when (insight.severity) {
        InsightSeverity.CRITICAL -> "high"
        InsightSeverity.WARNING -> "watch"
        InsightSeverity.INFO -> "info"
    }
    // State-appropriate effects line (feedback: show common symptoms/effects
    // of deficiencies and excesses) — seed-curated, diet-sanitized too.
    val safeEffects = insight.effects?.let {
        com.example.hoot.domain.insights.DietAwareSources
            .sanitizeForDisplay(it, dietFilter.toProfile())
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (tier != null) {
                    TierChip(tier)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    insight.title,
                    style = if (tier == 1) MaterialTheme.typography.titleSmall
                    else MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .background(severityColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        severityLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = severityColor
                    )
                }
            }
            Text(
                safeMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3, overflow = TextOverflow.Ellipsis
            )
            safeEffects?.takeIf { it.isNotBlank() }?.let { effects ->
                Text(
                    "You may notice: $effects",
                    style = MaterialTheme.typography.bodySmall,
                    color = severityColor,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun RecommendationCard(rec: RecommendationEntity) {
    Card(
        Modifier
            .width(220.dp)
            .semantics {
                contentDescription = "Food high in ${rec.nutrientId}: ${rec.foodName}"
            }
    ) {
        Column {
            // Coil image when a URL exists; tasteful emoji fallback otherwise
            // (and on load error) — never crashes on absent images.
            if (rec.imageUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(rec.imageUrl)
                        .crossfade(true)
                        .error(android.R.drawable.ic_menu_report_image)
                        .build(),
                    contentDescription = rec.foodName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        foodEmoji(rec.foodName),
                        style = MaterialTheme.typography.displayMedium
                    )
                }
            }
            Column(Modifier.padding(12.dp)) {
                Text(
                    rec.foodName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    rec.reasonText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun CoachNoteCard(state: InsightsUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Coach",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (state.coachLoading) {
                    Text(
                        "thinking…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                state.coachNote?.note ?: "Building your summary…",
                style = MaterialTheme.typography.bodyMedium
            )
            state.coachNote?.let { note ->
                Text(
                    if (note.fromLlm) "via LLM" else "template summary",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
