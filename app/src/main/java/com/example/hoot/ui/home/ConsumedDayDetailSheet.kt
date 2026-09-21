package com.example.hoot.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hoot.data.local.entity.MealEntity
import com.example.hoot.data.local.entity.SupplementEntity
import com.example.hoot.data.local.entity.TailEntryEntity
import com.example.hoot.domain.nutrition.WaterIntake
import com.example.hoot.ui.common.EmptyState
import com.example.hoot.ui.common.formatNutrient
import com.example.hoot.ui.common.prettyDay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Consumed that day" detail sheet (feedback 2026-09): the Home summary card
 * is intentionally brief, so tapping it opens EVERYTHING logged on the day —
 * meals (with energy/macros + summary), supplements, water entries and other
 * Tail habit entries, each with its log time. Read-only projection of the
 * three ledgers; no edit actions here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsumedDayDetailSheet(
    day: String,
    isToday: Boolean,
    meals: List<MealEntity>,
    supplements: List<SupplementEntity>,
    tailEntries: List<TailEntryEntity>,
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
                    Text(
                        if (isToday) "Everything consumed today"
                        else "Everything consumed — ${prettyDay(day)}",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(4.dp))
                    val waterL = WaterIntake.liters(tailEntries)
                    Text(
                        buildString {
                            append("${meals.size} meal${if (meals.size == 1) "" else "s"}")
                            append(" · ${supplements.size} supplement${if (supplements.size == 1) "" else "s"}")
                            if (waterL > 0) append(" · ${formatNutrient(waterL, "L")} water")
                            val misc = tailEntries.count { it.kind != WaterIntake.KIND_WATER }
                            if (misc > 0) append(" · $misc other entr${if (misc == 1) "y" else "ies"}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (meals.isEmpty() && supplements.isEmpty() && tailEntries.isEmpty()) {
                item {
                    EmptyState(
                        emoji = "🍽️",
                        title = "Nothing logged this day",
                        body = "Meals, supplements, water and other Tail habits will appear here.",
                        modifier = Modifier.padding(0.dp)
                    )
                }
            }

            // ---- Meals ------------------------------------------------------
            if (meals.isNotEmpty()) {
                item { DetailSectionLabel("Meals") }
                items(meals.size) { i ->
                    val meal = meals[i]
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "🍽️",
                                modifier = Modifier.width(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    meal.title ?: meal.rawText.take(60),
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                                val macros = buildString {
                                    append("${meal.calories} kcal")
                                    if (meal.proteinGrams > 0) append(" · ${"%.0f".format(meal.proteinGrams)} g protein")
                                    if (meal.carbsGrams > 0) append(" · ${"%.0f".format(meal.carbsGrams)} g carbs")
                                    if (meal.fatGrams > 0) append(" · ${"%.0f".format(meal.fatGrams)} g fat")
                                }
                                Text(
                                    macros,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!meal.summary.isNullOrBlank()) {
                                    Text(
                                        meal.summary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Text(
                                formatTime(meal.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ---- Supplements ------------------------------------------------
            if (supplements.isNotEmpty()) {
                item { DetailSectionLabel("Supplements") }
                items(supplements.size) { i ->
                    val supp = supplements[i]
                    Row(
                        Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("💊", modifier = Modifier.width(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            supp.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            formatTime(supp.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---- Water ------------------------------------------------------
            val waterRows = tailEntries.filter { it.kind == WaterIntake.KIND_WATER }
            if (waterRows.isNotEmpty()) {
                item { DetailSectionLabel("Water") }
                items(waterRows.size) { i ->
                    val entry = waterRows[i]
                    Row(
                        Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("💧", modifier = Modifier.width(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            entry.text.ifBlank { "Water" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            formatTime(entry.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ---- Other Tail habit entries ----------------------------------
            val miscRows = tailEntries.filter { it.kind != WaterIntake.KIND_WATER }
            if (miscRows.isNotEmpty()) {
                item { DetailSectionLabel("Other habits") }
                items(miscRows.size) { i ->
                    val entry = miscRows[i]
                    Row(
                        Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("•", modifier = Modifier.width(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.habitName,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                entry.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            formatTime(entry.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)); HorizontalDivider() }
            item {
                Text(
                    "Tap entries on the dashboard for deeper detail.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DetailSectionLabel(label: String) {
    Column {
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(2.dp))
    }
}

/** Epoch millis → "HH:mm" in the device zone (0/negative → "—"). */
private fun formatTime(timestampMs: Long): String {
    if (timestampMs <= 0) return "—"
    return Instant.ofEpochMilli(timestampMs)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))
}
