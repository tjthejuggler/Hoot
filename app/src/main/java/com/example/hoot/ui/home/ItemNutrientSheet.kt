package com.example.hoot.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hoot.ui.home.HomeViewModel.ItemNutrientState
import com.example.hoot.ui.home.HomeViewModel.NutrientOption

/**
 * Per-item nutrient breakdown sheet (feedback 2026-11-18): tapping a
 * "Logged today" row opens the nutrients attributed to that meal or
 * supplement on the selected day — only the ones with values, each in a
 * pre-filled editable field. A "+" chip opens a search-filterable picker of
 * every other nutrient. Saved edits become persistent overrides
 * ([com.example.hoot.data.local.entity.NutrientOverrideEntity]) that survive
 * ledger recomputes; "Reset" reverts one nutrient to the computed value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemNutrientSheet(
    state: ItemNutrientState,
    onEditDraft: (nutrientId: String, text: String) -> Unit,
    onAddNutrient: (nutrientId: String) -> Unit,
    onSave: () -> Unit,
    onReset: (nutrientId: String) -> Unit,
    onDismiss: () -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(
                "Nutrients from this item",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                state.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Edit any amount to correct it — saved edits stick even when " +
                    "Hoot re-analyzes the day.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            if (state.loading) {
                Spacer(Modifier.height(24.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(380.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(state.rows.size) { i ->
                        val row = state.rows[i]
                        NutrientEditRow(
                            name = row.name,
                            unit = row.unit,
                            effective = row.effective,
                            overridden = row.isOverridden,
                            draft = state.drafts[row.nutrientId]
                                ?: formatAmount(row.effective), // pre-filled, in place
                            onDraftChange = { onEditDraft(row.nutrientId, it) },
                            onReset = { onReset(row.nutrientId) }
                        )
                    }
                    item {
                        TextButton(onClick = { showPicker = true }) {
                            Icon(
                                Icons.Filled.Add, contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Add a nutrient")
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.saving) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = onDismiss) { Text("Close") }
                Spacer(Modifier.width(4.dp))
                Button(onClick = onSave, enabled = state.drafts.isNotEmpty() && !state.saving) {
                    Text("Save changes")
                }
            }
        }
    }

    if (showPicker) {
        NutrientPickerDialog(
            options = state.options.filter { opt -> state.rows.none { it.nutrientId == opt.id } },
            onPick = { id ->
                showPicker = false
                onAddNutrient(id)
            },
            onDismiss = { showPicker = false }
        )
    }
}

/** Compact amount → field text ("20.5", "0"; no trailing zeros/unit). */
private fun formatAmount(value: Double): String {
    if (value == 0.0) return "0"
    return if (value == value.toLong().toDouble()) value.toLong().toString()
    else "%.3f".format(value).trimEnd('0').trimEnd('.')
}

/**
 * Search-filterable picker over every nutrient definition not already shown
 * as a row. ListItem rows keep it consistent with the app's other pickers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NutrientPickerDialog(
    options: List<NutrientOption>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, options) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) options else options.filter { it.name.lowercase().contains(q) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search nutrients") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            if (filtered.isEmpty()) {
                Text(
                    "No matching nutrient.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(360.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filtered.size) { i ->
                    val opt = filtered[i]
                    ListItem(
                        headlineContent = { Text(opt.name) },
                        supportingContent = { Text("unit: ${opt.unit}") },
                        modifier = Modifier.clickable { onPick(opt.id) }
                    )
                }
            }
        }
    }
}

/**
 * One nutrient line: name (+ ✍️ when manually set) on the left, pre-filled
 * numeric field with the unit as label on the right; "Reset" restores the
 * computed value when an override is active.
 */
@Composable
private fun NutrientEditRow(
    name: String,
    unit: String,
    effective: Double,
    overridden: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onReset: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodyMedium)
                if (overridden) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Manually set",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (overridden) {
                TextButton(onClick = onReset) {
                    Text("Reset to computed", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { raw ->
                onDraftChange(raw.filter { it.isDigit() || it == '.' || it == ',' || it == '-' })
            },
            label = { Text(unit) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(120.dp)
        )
    }
}
