package com.example.hoot.ui.intake

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.io.File
import java.util.UUID

/** Row/dialog composables of the Intake ledger (extracted from IntakeScreen). */

@Composable
internal fun IntakeRowCard(row: IntakeRow, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (row.photoPath != null) {
                AsyncImage(
                    model = File(row.photoPath),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(row.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.subtitle != null) {
                    Text(
                        row.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (row.kind == "meal" && row.calories > 0) {
                    Text(
                        "${row.calories} kcal · P ${row.proteinGrams.toInt()}g · C ${row.carbsGrams.toInt()}g · F ${row.fatGrams.toInt()}g",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            AssistChip(onClick = onClick, label = {
                Text(
                    when (row.kind) {
                        "meal" -> "Meal"
                        "supplement" -> "Supp"
                        "water" -> "Water"
                        else -> "Log"
                    }
                )
            })
        }
    }
}

@Composable
internal fun MealDetailDialog(row: IntakeRow, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(row.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row.photoPath != null) {
                    AsyncImage(
                        model = File(row.photoPath),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }
                if (row.calories > 0) {
                    Text(
                        "${row.calories} kcal · Protein ${row.proteinGrams} g · " +
                            "Carbs ${row.carbsGrams} g · Fat ${row.fatGrams} g",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (row.ingredients.isNotEmpty()) {
                    Text("Ingredients (${row.ingredients.size}):", style = MaterialTheme.typography.titleSmall)
                    Text(row.ingredients.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Nutrition resolved for ${row.sourcesCount}/${row.ingredients.size} — " +
                            "the queue keeps working in the background.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}

/** Copies a camera/gallery Uri into app-private storage (survives Uri permission loss). */
internal fun copyUriToFile(context: android.content.Context, uri: android.net.Uri): File? = runCatching {
    val dir = File(context.filesDir, "intake_images").apply { mkdirs() }
    val out = File(dir, "pick_${UUID.randomUUID()}.jpg")
    context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { output -> input.copyTo(output) }
    } ?: return null
    out
}.getOrNull()
