package com.example.hoot.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.hoot.data.local.entity.ScoreSnapshotEntity
import com.example.hoot.ui.theme.ScoreHigh
import com.example.hoot.ui.theme.ScoreLow
import com.example.hoot.ui.theme.ScoreMid
import com.example.hoot.domain.score.ScoreEngine

/**
 * Score-ring explainer (overhaul feedback #2): plain-language breakdown of
 * exactly how today's number is computed — completeness %, deficiency
 * penalty, recommendation adherence — plus the color legend.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreExplainerSheet(
    snapshot: ScoreSnapshotEntity?,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("How your daily score works", style = MaterialTheme.typography.headlineSmall)
            Text(
                "One number, 0–100, from three parts. Each updates as you log meals.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (snapshot == null) {
                Text(
                    "No score yet today — it appears as soon as your first meal or " +
                        "supplement is analyzed.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                ComponentRow(
                    label = "Completeness",
                    weightLabel = "45% of score",
                    value = snapshot.completeness,
                    display = "%.0f%%".format(snapshot.completeness),
                    color = ScoreHigh,
                    description = "How much of today's nutrient targets you've reached so far, " +
                        "weighted: Tier-1 (critical) counts triple, Tier-2 double, Tier-3 single."
                )
                ComponentRow(
                    label = "Deficiency penalty",
                    weightLabel = "35% of score",
                    value = (1.0 - snapshot.deficiencyPenalty) * 100.0,
                    display = "-%.0f%%".format(snapshot.deficiencyPenalty * 100),
                    color = ScoreMid,
                    description = "Extra deductions when Tier-1 nutrients stay below 50% of target " +
                        "(worse after several low days) or you go over a safe upper limit."
                )
                ComponentRow(
                    label = "Recommendation adherence",
                    weightLabel = "20% of score",
                    value = snapshot.adherence * 100.0,
                    display = "%.0f%%".format(snapshot.adherence * 100),
                    color = ScoreLow,
                    description = "Share of suggested foods you actually ate in the last 7 days " +
                        "(dismissals count a little against you)."
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Right now: ${snapshot.nutrientsMet}/${snapshot.nutrientsTracked} daily " +
                        "targets met → score %.0f/100.".format(snapshot.score),
                    style = MaterialTheme.typography.titleSmall
                )
            }

            Spacer(Modifier.height(2.dp))
            Text("Ring colors", style = MaterialTheme.typography.titleSmall)
            LegendRow(ScoreHigh, "75–100 · strong day")
            LegendRow(ScoreMid, "50–74 · getting there")
            LegendRow(ScoreLow, "0–49 · gaps to close")
        }
    }
}

@Composable
private fun ComponentRow(
    label: String,
    weightLabel: String,
    value: Double,
    display: String,
    color: androidx.compose.ui.graphics.Color,
    description: String
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                display,
                style = MaterialTheme.typography.titleSmall,
                color = color
            )
        }
        Text(
            weightLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (value / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = color,
            trackColor = color.copy(alpha = 0.15f)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LegendRow(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
