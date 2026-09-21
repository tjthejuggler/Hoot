package com.example.hoot.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.hoot.ui.common.formatNutrient
import com.example.hoot.ui.theme.ScoreHigh
import com.example.hoot.ui.theme.ScoreLow
import com.example.hoot.ui.theme.ScoreMid

/**
 * Per-nutrient goals: current effective target (custom override or RDA),
 * tier badge, tap-to-edit custom target dialog, per-nutrient and global
 * reset-to-RDA. Targets live in nutrient_goals (NutrientGoalDao) and feed
 * the ScoreEngine + recommender immediately.
 */
@Composable
internal fun GoalsSection(
    rows: List<SettingsViewModel.GoalRow>,
    onSaveGoal: (SettingsViewModel.GoalRow, Double) -> Unit,
    onResetGoal: (String) -> Unit,
    onResetAll: () -> Unit
) {
    var editing by remember { mutableStateOf<SettingsViewModel.GoalRow?>(null) }
    var confirmResetAll by remember { mutableStateOf(false) }

    SectionCard(
        title = "Nutrient goals",
        subtitle = "${rows.size} tracked nutrients — tap a row to set a custom target"
    ) {
        rows.forEach { row ->
            GoalRowView(row, onClick = { editing = row })
            Spacer(Modifier.height(2.dp))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { confirmResetAll = true }) {
            Text("Reset all to RDA")
        }
    }

    // Per-nutrient editor dialog.
    editing?.let { row ->
        var text by remember(row.nutrientId) {
            mutableStateOf(if (row.isCustom) trimTarget(row.target ?: 0.0) else "")
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(row.name) },
            text = {
                Column {
                    Text(
                        "RDA default: ${row.rda?.let { formatNutrient(it, row.unit) } ?: "—"}" +
                            " · tier ${row.tier}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (row.isCustom) {
                        Text(
                            "Custom: ${formatNutrient(row.target ?: 0.0, row.unit)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = ScoreMid
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { v -> text = v.filter { it.isDigit() || it == '.' } },
                        label = { Text("Custom target (${row.unit})") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    text.toDoubleOrNull()?.let { onSaveGoal(row, it) }
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    if (row.isCustom) {
                        TextButton(onClick = {
                            onResetGoal(row.nutrientId)
                            editing = null
                        }) { Text("Reset") }
                    }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            }
        )
    }

    if (confirmResetAll) {
        AlertDialog(
            onDismissRequest = { confirmResetAll = false },
            title = { Text("Reset all goals?") },
            text = { Text("Every custom target is dropped and all nutrients revert to their RDA defaults.") },
            confirmButton = {
                Button(onClick = {
                    onResetAll()
                    confirmResetAll = false
                }) { Text("Reset all") }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetAll = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun GoalRowView(row: SettingsViewModel.GoalRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.bodyMedium)
            if (row.isCustom) {
                Text(
                    "custom · RDA ${row.rda?.let { formatNutrient(it, row.unit) } ?: "—"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ScoreMid
                )
            }
        }
        Text(
            "T${row.tier}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = tierColor(row.tier),
            modifier = Modifier.padding(end = 12.dp)
        )
        Text(
            formatNutrient(row.effectiveTarget, row.unit),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun tierColor(tier: Int) = when (tier) {
    1 -> ScoreLow
    2 -> ScoreMid
    else -> ScoreHigh
}

private fun trimTarget(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else "%.2f".format(d).trimEnd('0').trimEnd('.')
