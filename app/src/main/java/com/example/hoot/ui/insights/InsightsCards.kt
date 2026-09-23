package com.example.hoot.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.example.hoot.domain.insights.Grade
import com.example.hoot.domain.insights.NutrientGradeRow
import com.example.hoot.ui.common.TierChip
import com.example.hoot.ui.common.foodEmoji
import com.example.hoot.ui.theme.ScoreHigh
import com.example.hoot.ui.theme.ScoreLow
import com.example.hoot.ui.theme.ScoreMid

/** Card composables of the Insights screen (extracted from InsightsScreen). */

/** Letter → color (school-report semantics; theme accents, dark-safe). */
@Composable
internal fun gradeColor(grade: Grade): Color = when (grade) {
    Grade.F -> MaterialTheme.colorScheme.error
    Grade.D -> ScoreLow
    Grade.C -> ScoreMid
    Grade.B -> ScoreHigh.copy(alpha = 0.75f)
    Grade.A -> ScoreHigh
}

/**
 * One report-card row (feedback 2026-09-23): letter badge (F..A) replaces the
 * ambiguous high/watch/info chip. Badge text = situation summary, e.g.
 * "F · too low", "D · too high", "B · on track-ish", "A · solid".
 */
@Composable
internal fun GradeRow(row: NutrientGradeRow, onClick: (() -> Unit)?) {
    val color = gradeColor(row.grade)
    val situation = when {
        !row.isScoreable -> "not tracked"
        row.isExcess && row.grade != Grade.A -> "too high"
        row.grade == Grade.A -> "doing great"
        else -> "too low"
    }
    val desc = "${row.name}: grade ${row.grade.name}, $situation"
    Row(
        (onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics { contentDescription = desc },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .background(color.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                row.grade.name,
                style = MaterialTheme.typography.titleSmall,
                color = color
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                TierChip(row.tier)
            }
            val detail = when {
                !row.isScoreable -> "No target set — shown for reference only"
                row.grade == Grade.F && row.daysWithData == 0 ->
                    "No data logged in this window — log meals to find out"
                row.isExcess -> "Averaging ${(row.avgCoverage * 100).toInt()}% of your cap (${
                    row.badDays} over-cap days)"
                else -> "Averaging ${(row.avgCoverage * 100).toInt()}% of target (${
                    row.badDays} low days)"
            }
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
