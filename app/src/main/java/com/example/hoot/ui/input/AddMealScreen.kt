package com.example.hoot.ui.input

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.hoot.appGraph
import com.example.hoot.data.local.entity.IngredientEntity
import com.example.hoot.data.local.entity.MealEntity
import com.example.hoot.domain.nutrition.IngredientParser
import com.example.hoot.domain.nutrition.NutritionProcessState
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Quick-add meal (phase 3 native input): title + free-text ingredient string
 * + optional time. Saves [MealEntity] + one [IngredientEntity] per parsed
 * ingredient (raw, pending nutrition resolution) and kicks the background
 * resolution queue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMealScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val graph = context.appGraph
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var title by remember { mutableStateOf("") }
    var ingredientsText by remember { mutableStateOf("") }
    var timeText by remember { mutableStateOf("") } // "HH:mm", blank = now
    var saving by remember { mutableStateOf(false) }
    val processing by graph.nutritionProcessor.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add meal") },
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
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = ingredientsText,
                onValueChange = { ingredientsText = it },
                label = { Text("Ingredients") },
                placeholder = { Text("2 eggs, 1 cup rice, spinach") },
                supportingText = {
                    val parsed = IngredientParser.parse(ingredientsText)
                    Text(
                        if (parsed.isEmpty()) "Comma-separated foods; quantities optional"
                        else parsed.joinToString(", ") { it.displayName } + "  (${parsed.size} detected)"
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
            OutlinedTextField(
                value = timeText,
                onValueChange = { timeText = it },
                label = { Text("Time (optional, HH:mm)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(Modifier.height(4.dp))
            Button(
                enabled = !saving && ingredientsText.isNotBlank(),
                onClick = {
                    saving = true
                    scope.launch {
                        val result = runCatching { saveMeal(graph, title, ingredientsText, timeText) }
                        saving = false
                        result.fold(
                            onSuccess = { count ->
                                graph.nutritionProcessor.kick()
                                snackbar.showSnackbar("Saved — analyzing $count ingredient(s)…")
                                onDone()
                            },
                            onFailure = { snackbar.showSnackbar("Save failed: ${it.message}") }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (saving) "Saving…" else "Save meal")
            }
            NutritionStatusChipLine(processing)
        }
    }
}

@Composable
private fun NutritionStatusChipLine(state: NutritionProcessState) {
    val proc = state as? NutritionProcessState.Processing ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Analyzing nutrition… ${proc.pending} foods left",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** Pure save path (Room rows + processor kick) — kept suspend + testable. */
internal suspend fun saveMeal(
    graph: com.example.hoot.di.AppGraph,
    title: String,
    ingredientsText: String,
    timeText: String
): Int {
    val parsed = IngredientParser.parse(ingredientsText)
    require(parsed.isNotEmpty()) { "no ingredients detected" }
    val now = System.currentTimeMillis()
    val timestamp = parseTime(timeText, now)
    val meal = MealEntity(
        id = "hoot:${UUID.randomUUID()}",
        tailHabitName = "",
        timestamp = timestamp,
        day = LocalDate.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()).toString(),
        title = title.trim().ifBlank { null },
        rawText = buildString {
            if (title.isNotBlank()) append(title.trim()).append(": ")
            append(ingredientsText.trim())
        },
        source = "hoot"
    )
    graph.meals.upsert(meal)
    graph.meals.replaceIngredients(
        meal.id,
        parsed.map { p ->
            IngredientEntity(
                id = UUID.randomUUID().toString(),
                mealId = meal.id,
                rawText = p.rawText,
                foodId = null,               // pending nutrition resolution
                amount = p.quantity,
                unit = p.unit,
                gramsEstimate = null
            )
        }
    )
    return parsed.size
}

/** "HH:mm" → epoch millis today; blank/invalid → [fallback]. */
internal fun parseTime(text: String, fallback: Long): Long {
    val t = text.trim()
    if (t.isEmpty()) return fallback
    return runCatching {
        val time = LocalTime.parse(t)
        LocalDate.now().atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrDefault(fallback)
}
