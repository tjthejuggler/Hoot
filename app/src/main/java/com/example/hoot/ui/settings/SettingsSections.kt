package com.example.hoot.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.hoot.data.local.AppSettings
import com.example.hoot.data.local.entity.TailAppConfigEntity
import com.example.hoot.data.tail.TailSyncState
import com.example.hoot.ui.theme.ScoreMid

// ── Tail integration section ─────────────────────────────────────────────────

/**
 * Status card: connected app + mapped habits + last sync + meal-logs banner.
 * "Set up / change" opens the wizard; "Sync now" kicks [TailSyncManager].
 */
@Composable
internal fun TailSection(
    config: TailAppConfigEntity?,
    tailPackage: String,
    syncState: TailSyncState,
    onOpenSetup: () -> Unit,
    onSyncNow: () -> Unit
) {
    val connected = config != null && config.integrationEnabled
    val syncing = syncState is TailSyncState.Syncing

    SectionCard(
        title = "Tail integration",
        subtitle = "import meals & supplements from the Tail habit tracker"
    ) {
        if (connected) {
            StatusRow("Connected app", shortAppName(tailPackage))
            StatusRow(
                "Mapped habits",
                listOfNotNull(
                    config?.mealHabitName?.let { "$it (meals)" },
                    config?.pillsHabitName?.let { "$it (pills)" }
                ).joinToString(", ").ifBlank { "none" }
            )
            StatusRow(
                "Last sync",
                formatTimestamp(config?.lastMealSyncAt ?: config?.lastPillsSyncAt) +
                    if (syncing) " — syncing…" else ""
            )
            if (syncState is TailSyncState.Success && syncState.mealLogsUnavailable) {
                Spacer(Modifier.height(8.dp))
                Banner(
                    text = "Tail doesn't expose meal logs yet — Hoot is syncing shared " +
                        "text entries instead. See docs/TAIL_REQUEST.md.",
                    color = ScoreMid
                )
            }
            if (syncState is TailSyncState.Error) {
                Spacer(Modifier.height(8.dp))
                Banner(text = "Sync failed: ${syncState.message}", color = MaterialTheme.colorScheme.error)
            }
        } else {
            Text(
                "Not connected. Pick your Tail app and map the meal/pills habits to " +
                    "import your food log automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onOpenSetup) {
                Text(if (connected) "Set up / change" else "Set up")
            }
            if (connected) {
                OutlinedButton(onClick = onSyncNow, enabled = !syncing) {
                    Text(if (syncing) "Syncing…" else "Sync now")
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
internal fun Banner(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .padding(8.dp)
    )
}

// ── LLM section ──────────────────────────────────────────────────────────────

private val MODEL_SUGGESTIONS = listOf("glm-4.7", "gpt-4o", "llama-3.1-70b")

/** LLM endpoint card — Inuit-identical fields, defaults and Test semantics. */
@Composable
internal fun LlmSection(
    settings: AppSettings,
    test: SettingsViewModel.TestResult?,
    onSave: (String, String, String, Float) -> Unit,
    onDisableThinking: (Boolean) -> Unit,
    onTest: (String, String, String) -> Unit
) {
    // Local editable state, re-seeded whenever persisted settings change (Inuit).
    var baseUrl by rememberSaveable(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var apiKey by rememberSaveable(settings.apiKey) { mutableStateOf(settings.apiKey) }
    var model by rememberSaveable(settings.model) { mutableStateOf(settings.model) }
    var temperature by remember(settings.temperature) { mutableFloatStateOf(settings.temperature) }
    var showKey by remember { mutableStateOf(false) }

    val dirty = baseUrl != settings.baseUrl || apiKey != settings.apiKey ||
        model != settings.model || temperature != settings.temperature

    SectionCard(title = "LLM", subtitle = "any OpenAI-compatible endpoint") {
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            placeholder = { Text("https://api.example.com/v1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { showKey = !showKey }) {
                    Text(if (showKey) "Hide" else "Show")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model name") },
            placeholder = { Text("e.g. glm-4.7, gpt-4o, llama-3.1-70b") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MODEL_SUGGESTIONS.forEach { suggestion ->
                AssistChip(
                    onClick = { model = suggestion },
                    label = { Text(suggestion, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Temperature: ${"%.2f".format(temperature)}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = temperature,
            onValueChange = { temperature = it },
            valueRange = 0f..2f
        )
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.weight(1f)) {
                Text("Disable deep thinking", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "GLM reasoning models: skip internal chains — much faster and cheaper",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Bound straight to the store like Inuit (autosaves).
            Switch(checked = settings.disableThinking, onCheckedChange = onDisableThinking)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onSave(baseUrl, apiKey, model, temperature) }) { Text("Save") }
            OutlinedButton(onClick = { onTest(baseUrl, apiKey, model) }) { Text("Test") }
            UnsavedBadge(dirty)
        }
        TestResultView(test)
    }
}

// ── MCP section ──────────────────────────────────────────────────────────────

/** MCP servers JSON editor + tool budget, Inuit-style with validation. */
@Composable
internal fun McpSection(
    settings: AppSettings,
    test: SettingsViewModel.TestResult?,
    onSave: (json: String, budget: Int, onInvalid: (String) -> Unit) -> Unit,
    onResetDefault: () -> Unit,
    onTest: (String) -> Unit
) {
    var mcpJson by rememberSaveable(settings.mcpJson) { mutableStateOf(settings.mcpJson) }
    var budget by remember(settings.mcpBudget) { mutableFloatStateOf(settings.mcpBudget.toFloat()) }
    var jsonError by remember { mutableStateOf<String?>(null) }

    val dirty = mcpJson != settings.mcpJson || budget.toInt() != settings.mcpBudget

    SectionCard(
        title = "MCP servers",
        subtitle = "streamable-http servers with url + headers (stdio is skipped on Android)"
    ) {
        OutlinedTextField(
            value = mcpJson,
            onValueChange = {
                mcpJson = it
                jsonError = null
            },
            label = { Text("mcpServers JSON") },
            isError = jsonError != null,
            supportingText = jsonError?.let { err -> { Text("Invalid JSON: $err") } },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            minLines = 8,
            maxLines = 20,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        LabeledSlider(
            label = "Web tool budget per resolution",
            value = budget,
            range = 0f..20f,
            steps = 19,
            display = { "${it.toInt()}" },
            onChange = { budget = it }
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                onSave(mcpJson, budget.toInt()) { err -> jsonError = err }
            }) { Text("Save") }
            OutlinedButton(onClick = { onTest(mcpJson) }) { Text("Test") }
            OutlinedButton(onClick = {
                jsonError = null
                onResetDefault()
            }) { Text("Reset default") }
            UnsavedBadge(dirty)
        }
        TestResultView(test)
        Spacer(Modifier.height(8.dp))
        Text(
            "Seeded with z.ai web search + web reader. Paste your own API key into the " +
                "Authorization headers. Used (sparingly, per budget) to ground nutrition " +
                "lookups when the LLM is not confident.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
