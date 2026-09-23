package com.example.hoot.ui.tail

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.hoot.data.local.entity.TailAppConfigEntity
import com.example.hoot.data.tail.TailHabit
import com.example.hoot.data.tail.TailSyncState

/**
 * Per-step section composables of the Tail setup flow (extracted from
 * TailSetupScreen so each wizard step edits in its own file).
 */

@Composable
internal fun AppPickerSection(
    apps: List<com.example.hoot.data.tail.TailAppInfo>,
    loading: Boolean,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Choose the Tail app to sync from", style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
        }
    }
    if (loading) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) { CircularProgressIndicator(Modifier.size(28.dp)) }
    } else if (apps.isEmpty()) {
        Text(
            "No Tail installation found.\n\nInstall the Tail habit tracker (signed with the same " +
                "keystore) and make sure Hoot is enabled under Tail → Settings → Integrations.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        apps.forEach { app ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(app.packageName) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(app.appName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun HabitMappingSection(
    habits: List<TailHabit>,
    mealHabit: String?,
    pillsHabit: String?,
    waterHabit: String?,
    waterUnitMode: String,
    miscHabits: List<String>,
    loading: Boolean,
    onMeal: (String?) -> Unit,
    onPills: (String?) -> Unit,
    onWater: (String?) -> Unit,
    onWaterUnitMode: (String) -> Unit,
    onAddMisc: (String?) -> Unit,
    onRemoveMisc: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    var showMealPicker by remember { mutableStateOf(false) }
    var showPillsPicker by remember { mutableStateOf(false) }
    var showWaterPicker by remember { mutableStateOf(false) }
    var showMiscPicker by remember { mutableStateOf(false) }

    Text("Map your habits", style = MaterialTheme.typography.titleMedium)
    Text(
        "Pick which Tail habit holds your food log and which holds your supplements. " +
            "Optionally map water and any other habits you log.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (loading) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(28.dp))
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = { showMealPicker = true }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Restaurant, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(mealHabit ?: "Meal habit")
        }
        OutlinedButton(onClick = { showPillsPicker = true }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Medication, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(pillsHabit ?: "Pills habit")
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(onClick = { showWaterPicker = true }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.WaterDrop, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(waterHabit ?: "Water habit")
        }
        OutlinedButton(onClick = { showMiscPicker = true }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (miscHabits.isEmpty()) "Add misc habit"
                else "Misc (${miscHabits.size})"
            )
        }
    }
    // Water unit interpretation (feedback 2026-09): only relevant when a
    // water habit is mapped. Bare Tail numbers ("2500") must be read as the
    // unit the user actually logs — ml for Tail's raw-counter logs.
    if (waterHabit != null) {
        Text(
            "A number without a unit in your water habit means…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "auto" to "Auto",
                "ml" to "ml",
                "l" to "liters",
                "oz" to "fl oz",
                "glass" to "glasses"
            ).forEach { (mode, label) ->
                FilterChip(
                    selected = waterUnitMode == mode,
                    onClick = { onWaterUnitMode(mode) },
                    label = { Text(label) }
                )
            }
        }
    }
    miscHabits.forEachIndexed { index, name ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Misc: $name",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onRemoveMisc(name) }) { Text("Remove") }
        }
    }
    // Heuristic preselection hints for first-time users.
    if (mealHabit == null) {
        habits.firstOrNull { it.isMealType || it.habitName.contains("food", true) }?.let {
            AssistChip(onClick = { onMeal(it.habitId) }, label = { Text("Use \"${it.habitName}\" for meals?") })
        }
    }
    if (pillsHabit == null) {
        habits.firstOrNull { it.isTextType || it.habitName.contains("pill", true) }?.let {
            AssistChip(onClick = { onPills(it.habitId) }, label = { Text("Use \"${it.habitName}\" for pills?") })
        }
    }
    if (waterHabit == null) {
        habits.firstOrNull { it.habitName.contains("water", true) }?.let {
            AssistChip(onClick = { onWater(it.habitId) }, label = { Text("Use \"${it.habitName}\" for water?") })
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onCancel) { Text("Back") }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onSave,
            enabled = mealHabit != null || pillsHabit != null ||
                waterHabit != null || miscHabits.isNotEmpty()
        ) {
            Text("Save & sync")
        }
    }

    if (showMealPicker) {
        HabitRadioDialog(
            title = "Meal habit",
            habits = habits,
            selected = mealHabit,
            onSelect = { onMeal(it); showMealPicker = false },
            onDismiss = { showMealPicker = false }
        )
    }
    if (showPillsPicker) {
        HabitRadioDialog(
            title = "Pills habit (text)",
            habits = habits,
            selected = pillsHabit,
            onSelect = { onPills(it); showPillsPicker = false },
            onDismiss = { showPillsPicker = false }
        )
    }
    if (showWaterPicker) {
        HabitRadioDialog(
            title = "Water habit",
            habits = habits,
            selected = waterHabit,
            onSelect = { onWater(it); showWaterPicker = false },
            onDismiss = { showWaterPicker = false }
        )
    }
    if (showMiscPicker) {
        HabitRadioDialog(
            title = "Miscellaneous habit (text)",
            habits = habits,
            selected = null,
            onSelect = { id ->
                onAddMisc(id)
                showMiscPicker = false
            },
            onDismiss = { showMiscPicker = false }
        )
    }
}

@Composable
internal fun HabitRadioDialog(
    title: String,
    habits: List<TailHabit>,
    selected: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Search-as-you-type filter — Tail installs with many habits and the
    // mapping dialogs must stay usable when the list grows.
    var query by remember { mutableStateOf("") }
    val filtered = remember(habits, query) {
        if (query.isBlank()) habits
        else habits.filter {
            it.habitName.contains(query.trim(), ignoreCase = true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(title) },
        text = {
            Column {
                if (habits.size > 5) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("Search habits…") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (habits.isEmpty()) {
                    Text("No habits available.")
                } else if (filtered.isEmpty()) {
                    Text(
                        "No habits match \"$query\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn {
                        items(filtered, key = { it.habitId }) { h ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(h.habitId) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selected == h.habitId,
                                    onClick = { onSelect(h.habitId) }
                                )
                                Column {
                                    Text(h.habitName, style = MaterialTheme.typography.bodyMedium)
                                    h.habitType?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
internal fun ConnectedSection(
    config: TailAppConfigEntity?,
    syncState: TailSyncState,
    onSyncNow: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Text(
            "Tail connected",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Meal habit: ${config?.mealHabitName ?: "—"}", style = MaterialTheme.typography.bodyMedium)
            Text("Pills habit: ${config?.pillsHabitName ?: "—"}", style = MaterialTheme.typography.bodyMedium)
            Text("Water habit: ${config?.waterHabitName ?: "—"}", style = MaterialTheme.typography.bodyMedium)
            val misc = config?.miscHabitNames.orEmpty()
            Text(
                if (misc.isEmpty()) "Misc habits: —"
                else "Misc habits: ${misc.joinToString()}",
                style = MaterialTheme.typography.bodyMedium
            )
            val lastSync = config?.lastMealSyncAt ?: config?.lastPillsSyncAt
                ?: config?.lastWaterSyncAt ?: config?.lastMiscSyncAt
            Text(
                "Last sync: ${if (lastSync != null) formatSyncTime(lastSync) else "never"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    if ((config?.mealHabitName != null) && mealLogsNeverAvailable(syncState, config.lastMealSyncAt)) {
        // Soft notice: the v2 meal endpoint isn't up yet on the Tail side.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Meal logs not yet exposed by Tail",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Until Tail ships the v2 meal endpoint, Hoot syncs your meal habit's text " +
                        "entries instead. The full proposal lives in docs/TAIL_REQUEST.md.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (syncState is TailSyncState.Syncing) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Syncing…", style = MaterialTheme.typography.bodySmall)
        } else {
            Button(onClick = onSyncNow) { Text("Sync now") }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onDisconnect) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
    }
}

internal fun mealLogsNeverAvailable(state: TailSyncState, lastMealSyncAt: Long?): Boolean {
    // Show the banner until a sync has confirmed the v2 meal surface.
    return lastMealSyncAt == null || state is TailSyncState.Success && state.mealLogsUnavailable
}

internal fun formatSyncTime(epochMs: Long): String =
    java.time.Instant.ofEpochMilli(epochMs)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
