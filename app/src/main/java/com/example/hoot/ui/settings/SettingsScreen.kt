package com.example.hoot.ui.settings

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hoot.data.tail.TailSyncState
import com.example.hoot.ui.theme.ErrorAccent
import com.example.hoot.ui.theme.ScoreHigh
import com.example.hoot.ui.theme.ScoreMid
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Settings — sectioned cards mirroring Inuit's SettingsScreen: LLM endpoint
 * (+ Test via /models), MCP JSON editor (+ Test), Tail integration status,
 * dietary profile, personal stats, nutrient goals, sync preferences and
 * data/cache management. Save-on-button semantics with dirty indicators.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenTailSetup: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val tailConfig by viewModel.tailConfig.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val processorState by viewModel.processorState.collectAsStateWithLifecycle()
    val cacheCount by viewModel.cacheCount.collectAsStateWithLifecycle()
    val cacheOldest by viewModel.cacheOldest.collectAsStateWithLifecycle()
    val llmTest by viewModel.llmTest.collectAsStateWithLifecycle()
    val mcpTest by viewModel.mcpTest.collectAsStateWithLifecycle()
    val goalRows by viewModel.goalRows.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Tail integration ──────────────────────────────────────────
            TailSection(
                config = tailConfig,
                tailPackage = settings.tailPackage,
                syncState = syncState,
                onOpenSetup = onOpenTailSetup,
                onSyncNow = viewModel::syncNow
            )

            // ── LLM ───────────────────────────────────────────────────────
            LlmSection(
                settings = settings,
                test = llmTest,
                onSave = viewModel::saveLlm,
                onDisableThinking = viewModel::setDisableThinking,
                onTest = viewModel::testLlm
            )

            // ── MCP ───────────────────────────────────────────────────────
            McpSection(
                settings = settings,
                test = mcpTest,
                onSave = viewModel::saveMcp,
                onResetDefault = viewModel::resetMcpJson,
                onTest = viewModel::testMcp
            )

            // ── Dietary profile ───────────────────────────────────────────
            DietarySection(
                settings = settings,
                onSave = viewModel::saveDiet
            )

            // ── Personal stats ────────────────────────────────────────────
            PersonalStatsSection(
                settings = settings,
                onSave = viewModel::saveUserProfile
            )

            // ── Nutrient goals ────────────────────────────────────────────
            GoalsSection(
                rows = goalRows,
                onSaveGoal = viewModel::saveCustomGoal,
                onResetGoal = viewModel::resetGoal,
                onResetAll = viewModel::resetAllGoals
            )

            // ── Sync preferences ──────────────────────────────────────────
            SyncPrefsSection(
                settings = settings,
                onSave = viewModel::saveSync
            )

            // ── Data & cache ──────────────────────────────────────────────
            DataCacheSection(
                cacheCount = cacheCount,
                cacheOldest = cacheOldest,
                processor = processorState,
                onClear = {
                    viewModel.clearCache {
                        scope.launch { snackbar.showSnackbar("Cache cleared — citation sources kept") }
                    }
                },
                onReresolve = {
                    viewModel.reresolveFailed()
                    scope.launch { snackbar.showSnackbar("Re-resolution queued for unresolved ingredients") }
                },
                onExport = {
                    scope.launch {
                        val json = viewModel.exportDataJson()
                        if (json == null) {
                            snackbar.showSnackbar("Export failed")
                        } else {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Hoot data export")
                                putExtra(Intent.EXTRA_TEXT, json)
                            }
                            context.startActivity(Intent.createChooser(send, "Share Hoot data"))
                        }
                    }
                }
            )

            // ── About ─────────────────────────────────────────────────────
            AboutCard()

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Shared building blocks (Inuit's SectionCard / TestResultView clones) ─────

/** Section container: bordered card with an accent tick, title + subtitle. */
@Composable
internal fun SectionCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 3.dp, height = 14.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** Inline status under Save/Test rows (Inuit's TestResultView). */
@Composable
internal fun TestResultView(result: SettingsViewModel.TestResult?) {
    when (result) {
        is SettingsViewModel.TestResult.Running -> {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.height(16.dp).width(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Testing…", style = MaterialTheme.typography.bodySmall)
            }
        }
        is SettingsViewModel.TestResult.Ok -> {
            Spacer(Modifier.height(8.dp))
            Text(
                "✓ ${result.message}",
                style = MaterialTheme.typography.bodySmall,
                color = ScoreHigh,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }
        is SettingsViewModel.TestResult.Fail -> {
            Spacer(Modifier.height(8.dp))
            Text(
                "✗ ${result.message}",
                style = MaterialTheme.typography.bodySmall,
                color = ErrorAccent,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }
        null -> {}
    }
}

/** Slider with an "label: value" caption (Inuit's LabeledSlider). */
@Composable
internal fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    display: (Float) -> String,
    onChange: (Float) -> Unit
) {
    Column {
        Text("$label: ${display(value)}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps
        )
    }
}

/** Small "unsaved changes" marker next to Save buttons. */
@Composable
internal fun UnsavedBadge(dirty: Boolean) {
    if (!dirty) return
    Text(
        "● unsaved changes",
        style = MaterialTheme.typography.labelSmall,
        color = ScoreMid
    )
}

/** "Sep 19, 14:05" style formatting for sync/cursor timestamps. */
internal fun formatTimestamp(ms: Long?): String {
    if (ms == null || ms <= 0) return "never"
    return runCatching {
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))
    }.getOrDefault("—")
}

/** "com.example.tail" → "Tail" for status rows. */
internal fun shortAppName(pkg: String): String =
    pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }.ifBlank { pkg }
