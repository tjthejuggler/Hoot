package com.example.hoot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.hoot.data.local.AppSettings
import com.example.hoot.domain.nutrition.NutritionProcessState

// ── Sync preferences section ─────────────────────────────────────────────────

/** Background-sync cadence + cache TTL, saved on button (DataStore keys). */
@Composable
internal fun SyncPrefsSection(
    settings: AppSettings,
    onSave: (syncEnabled: Boolean, intervalMin: Int, ttlDays: Int, webFallback: Boolean) -> Unit
) {
    var enabled by remember(settings.syncEnabled) { mutableStateOf(settings.syncEnabled) }
    var interval by remember(settings.syncIntervalMinutes) { mutableFloatStateOf(settings.syncIntervalMinutes.toFloat()) }
    var ttl by remember(settings.cacheTtlDays) { mutableFloatStateOf(settings.cacheTtlDays.toFloat()) }
    var webFallback by remember(settings.webSearchFallback) { mutableStateOf(settings.webSearchFallback) }

    val dirty = enabled != settings.syncEnabled ||
        interval.toInt() != settings.syncIntervalMinutes ||
        ttl.toInt() != settings.cacheTtlDays ||
        webFallback != settings.webSearchFallback

    SectionCard(
        title = "Sync & freshness",
        subtitle = "Tail backlog polling and nutrition-cache freshness"
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.weight(1f)) {
                Text("Background sync", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Periodically pull new meals & supplements from Tail",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        Spacer(Modifier.height(8.dp))
        LabeledSlider(
            label = "Sync interval",
            value = interval,
            range = 15f..720f,
            steps = 4,
            display = { mins ->
                val m = mins.toInt()
                if (m % 60 == 0) "${m / 60} h" else "$m min"
            },
            onChange = { interval = it }
        )
        Spacer(Modifier.height(8.dp))
        LabeledSlider(
            label = "Cache TTL",
            value = ttl,
            range = 1f..365f,
            display = { "${
                it.toInt().let { d -> if (d >= 30 && d % 30 == 0) "${d / 30} mo" else "$d d"
                }
            }" },
            onChange = { ttl = it }
        )
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.weight(1f)) {
                Text("Web-search fallback", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Use MCP web tools when the LLM is unsure about a food",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = webFallback, onCheckedChange = { webFallback = it })
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onSave(enabled, interval.toInt(), ttl.toInt(), webFallback) }) { Text("Save") }
            UnsavedBadge(dirty)
        }
    }
}

// ── Data & cache section ─────────────────────────────────────────────────────

/**
 * Lookup-cache stats + destructive actions (cache clear keeps citation
 * Sources), re-resolution kick, JSON export via the share sheet.
 */
@Composable
internal fun DataCacheSection(
    cacheCount: Int,
    cacheOldest: Long?,
    processor: NutritionProcessState,
    onClear: () -> Unit,
    onReresolve: () -> Unit,
    onExport: () -> Unit
) {
    val processing = processor is NutritionProcessState.Processing

    SectionCard(
        title = "Data & cache",
        subtitle = "resolved nutrition lookups and your logged data"
    ) {
        Text(
            "Lookup cache: $cacheCount entries · oldest ${formatTimestamp(cacheOldest)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (processing) {
            val p = processor as NutritionProcessState.Processing
            Text(
                "Resolving nutrition… ${p.current} (${p.pending} foods left)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onClear) { Text("Clear nutrition cache") }
                OutlinedButton(onClick = onReresolve, enabled = !processing) {
                    Text("Re-resolve failed ingredients")
                }
            }
            Button(onClick = onExport) { Text("Export data (JSON)") }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Clearing keeps citation sources and your meals — only resolved lookups " +
                "are dropped, so ingredients are re-resolved on demand.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── About ────────────────────────────────────────────────────────────────────

@Composable
internal fun AboutCard() {
    SectionCard(title = "About", subtitle = null) {
        Text(
            "Hoot 1.0 — nutrition-recommendation companion for the Tail habit tracker. " +
                "Meals sync from Tail, resolve to per-nutrient intake through an " +
                "OpenAI-compatible LLM (with MCP web-search grounding), and roll up " +
                "into a daily 0–100 score.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
