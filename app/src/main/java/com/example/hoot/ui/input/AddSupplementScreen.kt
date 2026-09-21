package com.example.hoot.ui.input

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.hoot.appGraph
import com.example.hoot.data.local.entity.SupplementEntity
import com.example.hoot.domain.nutrition.SupplementListSplitter
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Common supplement quick-picks (dose optional — LLM fills the rest). */
private val KNOWN_SUPPLEMENTS = listOf(
    "Magnesium 400 mg", "Vitamin D 2000 IU", "Vitamin B12 500 mcg",
    "Vitamin C 500 mg", "Omega-3 1 g", "Zinc 15 mg", "Iron 18 mg",
    "Calcium 500 mg", "Potassium 99 mg", "Multivitamin"
)

/**
 * Quick-add supplement (phase 3 native input): free text or pick-from-known
 * chips. Saves a [SupplementEntity] (source="native", pending contributions)
 * and kicks the resolution queue; the label parser resolves "Name dose unit"
 * without an LLM round-trip when unambiguous.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddSupplementScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val graph = context.appGraph
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var text by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add supplement") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Taken today — e.g. “Magnesium 400 mg”. Pick a common one or type your own:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (known in KNOWN_SUPPLEMENTS) {
                    FilterChip(
                        selected = text.equals(known, ignoreCase = true),
                        onClick = { text = known },
                        label = { Text(known) }
                    )
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Supplement") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(4.dp))
            Button(
                enabled = !saving && text.isNotBlank(),
                onClick = {
                    saving = true
                    scope.launch {
                        // Multi-item free text ("iron, vitamin D") → one row per
                        // item, same timestamp/day provenance, stable ids.
                        val result = runCatching {
                            val now = System.currentTimeMillis()
                            val day = LocalDate.ofInstant(
                                Instant.ofEpochMilli(now), ZoneId.systemDefault()
                            ).toString()
                            val baseId = "hoot:${UUID.randomUUID()}"
                            val items = SupplementListSplitter.split(text)
                                .ifEmpty { listOf(text.trim()) }
                            val rows = items.mapIndexed { index, item ->
                                SupplementEntity(
                                    id = if (index == 0) baseId else "$baseId#$index",
                                    label = item.take(80),
                                    description = null,
                                    resolvedFoodId = null,          // pending resolution
                                    doseAmount = null,
                                    doseUnit = null,
                                    nutrientContributions = "[]",   // pending resolution
                                    tailHabitName = "",
                                    timestamp = now,
                                    day = day,
                                    rawText = item,
                                    source = "native"
                                )
                            }
                            graph.meals.upsertSupplements(rows)
                            rows.size
                        }
                        saving = false
                        result.fold(
                            onSuccess = {
                                graph.nutritionProcessor.kick()
                                onDone()
                            },
                            onFailure = { snackbar.showSnackbar("Save failed: ${it.message}") }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (saving) "Saving…" else "Save supplement")
            }
        }
    }
}
