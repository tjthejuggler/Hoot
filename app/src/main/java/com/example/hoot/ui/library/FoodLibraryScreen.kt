package com.example.hoot.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hoot.data.local.entity.NutrientDefinitionEntity
import com.example.hoot.ui.theme.ErrorAccent
import com.example.hoot.ui.theme.ScoreHigh
import com.example.hoot.ui.theme.ScoreMid

/**
 * Settings → Food library: the full list of foods with their nutrient
 * associations. Searchable and filterable; tapping a row opens a per-food
 * editor where values, the per-basis and the nutrient set can be changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodLibraryScreen(
    onBack: () -> Unit,
    viewModel: FoodLibraryViewModel = viewModel()
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val edit by viewModel.edit.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Food library") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = viewModel::setSearch,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search foods…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (search.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearch("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FoodLibraryFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { viewModel.setFilter(f) },
                        label = { Text(f.label) }
                    )
                }
            }

            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No foods match.\nResolved foods appear here once meals " +
                            "are analyzed (or via Settings → Re-resolve).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(rows, key = { it.food.id }) { row ->
                        FoodLibraryRowItem(row = row, onClick = { viewModel.openEditor(row.food.id) })
                    }
                }
            }
        }
    }

    edit?.let { state ->
        FoodEditSheet(
            state = state,
            vm = viewModel,
            onDismiss = viewModel::closeEditor
        )
    }
}

/** One library row: display name, association badge and a value summary. */
@Composable
private fun FoodLibraryRowItem(row: FoodLibraryRow, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row.food.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (row.food.isSupplement) {
                    TagPill("supplement")
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    if (row.valueCount > 0) "${row.valueCount} nutrients" else "no data",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (row.valueCount > 0) ScoreHigh else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                row.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TagPill(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/**
 * Per-food editor sheet: rename, per-basis, one field per associated nutrient
 * (blank = remove), an "add nutrient" picker for the remaining definitions,
 * plus Save / Clear-data actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FoodEditSheet(
    state: FoodEditState,
    vm: FoodLibraryViewModel,
    onDismiss: () -> Unit
) {
    var showAddPicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.food.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            state.profile?.let {
                                "Per ${fmt(it.perAmount)} ${it.perUnit} · " +
                                    when (it.resolutionMethod) {
                                        "manual" -> "edited manually"
                                        "cache" -> "from cache"
                                        "web_usda" -> "from USDA"
                                        else -> "LLM-estimated"
                                    } + " · confidence ${"%.0f".format(it.confidence * 100)}%"
                            } ?: "No nutrition data yet",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (state.dirty) Text(
                        "unsaved",
                        style = MaterialTheme.typography.labelSmall,
                        color = ScoreMid
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = state.nameText,
                    onValueChange = vm::updateName,
                    label = { Text("Display name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.perAmountText,
                        onValueChange = vm::updatePerAmount,
                        label = { Text("Per amount") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = state.perUnit,
                        onValueChange = vm::updatePerUnit,
                        label = { Text("Unit") },
                        singleLine = true,
                        modifier = Modifier.width(96.dp)
                    )
                }
            }

            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            if (state.rows.isEmpty()) {
                item {
                    Text(
                        "No nutrients associated yet — add some below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(state.rows, key = { it.key }) { row ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                row.displayName + if (row.unit.isNotEmpty()) " (${row.unit})" else "",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { vm.removeEditRow(row.key) }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remove ${row.displayName}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(18.dp).height(18.dp)
                                )
                            }
                        }
                        OutlinedTextField(
                            value = row.valueText,
                            onValueChange = { vm.updateEditValue(row.key, it) },
                            singleLine = true,
                            placeholder = { Text("value — blank removes it") },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            item {
                OutlinedButton(onClick = { showAddPicker = true }) {
                    Text("+ Add nutrient")
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = vm::saveEdit,
                        enabled = state.dirty,
                        modifier = Modifier.weight(1f)
                    ) { Text("Save") }
                    if (state.profile != null) {
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = ErrorAccent,
                                modifier = Modifier.width(18.dp).height(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Clear data", color = ErrorAccent)
                        }
                    }
                }
            }
        }
    }

    if (showAddPicker) {
        AddNutrientPickerDialog(
            addable = state.addable,
            alreadyShown = state.rows.map { it.displayName }.toSet(),
            onPick = { vm.addEditNutrient(it) },
            onDismiss = { showAddPicker = false }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Clear nutrition data?") },
            text = { Text("Removes every nutrient association for “${state.food.displayName}”. The next lookup will re-resolve it from scratch.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteAssociation()
                }) { Text("Clear", color = ErrorAccent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

/** Search-and-tap picker of seeded nutrient definitions not yet on the food. */
@Composable
private fun AddNutrientPickerDialog(
    addable: List<NutrientDefinitionEntity>,
    alreadyShown: Set<String>,
    onPick: (NutrientDefinitionEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val shown = addable.filter { def ->
        (q.isEmpty() || def.name.lowercase().contains(q) || def.id.contains(q)) &&
            def.name !in alreadyShown
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add nutrient") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search…") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(shown, key = { it.id }) { def ->
                        Text(
                            "${def.name}${if (def.unit.isNotEmpty()) " (${def.unit})" else ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPick(def)
                                    onDismiss()
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
