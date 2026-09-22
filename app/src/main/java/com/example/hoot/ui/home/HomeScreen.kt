package com.example.hoot.ui.home

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hoot.appGraph
import com.example.hoot.domain.insights.Insight
import com.example.hoot.domain.insights.InsightSeverity
import com.example.hoot.domain.nutrition.WaterIntake
import com.example.hoot.domain.score.ScoreStatus
import com.example.hoot.ui.charts.ProgressRing
import com.example.hoot.ui.charts.Sparkline
import com.example.hoot.ui.charts.scoreColor
import com.example.hoot.ui.common.EmptyState
import com.example.hoot.ui.common.NutrientDetailSheet
import com.example.hoot.ui.common.NutrientDetailViewModel
import com.example.hoot.ui.common.NutrientProgressRow
import com.example.hoot.ui.common.ScoreExplainerSheet
import com.example.hoot.ui.common.SectionHeader
import com.example.hoot.ui.common.TierChip
import com.example.hoot.ui.common.dayKeyToDatePickerMillis
import com.example.hoot.ui.common.datePickerMillisToDayKey
import com.example.hoot.ui.common.formatNutrient
import com.example.hoot.ui.common.prettyDay
import com.example.hoot.ui.common.todayKey
import com.example.hoot.ui.tail.TailSyncBanner
import com.example.hoot.ui.theme.ScoreHigh
import com.example.hoot.ui.theme.ScoreLow
import com.example.hoot.ui.theme.ScoreMid

/**
 * Home (start destination, overhaul feedback #4): hybrid "what should I eat
 * today" dashboard. Focus-now gaps first (recent-past AND today, tappable →
 * food suggestions), labeled score ring with explainer, quick stats,
 * trend sparkline, sync/analysis chips, all-tier nutrient sections
 * (Tier-1 default + expandable Tier-2/3), meals & supplements.
 */
@Composable
fun HomeScreen(
    onOpenHistory: () -> Unit,
    onOpenInsights: () -> Unit,
    onOpenTailSetup: () -> Unit = {}
) {
    val context = LocalContext.current
    val vm: HomeViewModel = viewModel()
    val detailVm: NutrientDetailViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val meals by vm.meals.collectAsStateWithLifecycle()
    val supplements by vm.supplements.collectAsStateWithLifecycle()
    val tailEntries by vm.tailEntries.collectAsStateWithLifecycle()
    val unresolved by vm.unresolved.collectAsStateWithLifecycle()

    val config by context.appGraph.tailConfig.observeTailConfig()
        .collectAsStateWithLifecycle(initialValue = null)
    val syncState by context.appGraph.tailSync.syncState.collectAsStateWithLifecycle()
    val nutritionState by context.appGraph.nutritionProcessor.state.collectAsStateWithLifecycle()

    var showScoreExplainer by remember { mutableStateOf(false) }
    var detailNutrientId by remember { mutableStateOf<String?>(null) }
    var tiersExpanded by remember { mutableStateOf(false) }
    var smartPickDetail by remember { mutableStateOf<com.example.hoot.domain.insights.SmartFoodPick?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showConsumedDetail by remember { mutableStateOf(false) }
    var showAllPicks by remember { mutableStateOf(false) }

    // Selected dashboard day (header arrows / date picker navigate it).
    val selectedDay by vm.day.collectAsStateWithLifecycle()
    val isToday = selectedDay == todayKey()

    // Water unit interpretation (feedback 2026-09): what a bare number in the
    // Tail water habit means ("ml" for Tail's raw-ml logs). Reactive — the
    // card re-sums immediately when the mode changes in Tail setup.
    val settingsState by context.appGraph.settings.settings
        .collectAsStateWithLifecycle(initialValue = null)
    val waterUnitMode = settingsState?.waterUnitMode ?: "auto"

    if (showScoreExplainer) {
        ScoreExplainerSheet(snapshot = state.score, onDismiss = { showScoreExplainer = false })
    }
    detailNutrientId?.let { id ->
        detailVm.open(id)
        NutrientDetailSheet(vm = detailVm, onDismiss = { detailNutrientId = null })
    }
    smartPickDetail?.let { pick ->
        SmartPickDetailSheet(pick = pick, onDismiss = { smartPickDetail = null })
    }
    if (showConsumedDetail) {
        ConsumedDayDetailSheet(
            day = selectedDay,
            isToday = isToday,
            meals = meals,
            supplements = supplements,
            tailEntries = tailEntries,
            waterUnitMode = waterUnitMode,
            onDismiss = { showConsumedDetail = false }
        )
    }
    if (showAllPicks) {
        AllSmartPicksSheet(
            picks = state.allSmartPicks,
            deepPicks = state.deepSmartPicks,
            gaps = state.focusNow,
            loading = state.loading,
            onOpenPick = { smartPickDetail = it },
            onDismiss = { showAllPicks = false }
        )
    }
    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = dayKeyToDatePickerMillis(selectedDay).takeIf { it > 0 }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        vm.selectDay(datePickerMillisToDayKey(it))
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- Header: day navigation + refresh ----------------------------
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::goBackDay) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Previous day"
                        )
                    }
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Home", style = MaterialTheme.typography.headlineSmall)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable(onClick = { showDatePicker = true })
                        ) {
                            Text(
                                prettyDay(selectedDay),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                Icons.Filled.ExpandMore,
                                contentDescription = "Pick a date",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    IconButton(onClick = vm::goForwardDay, enabled = !isToday) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Next day"
                        )
                    }
                    IconButton(onClick = vm::retryUnresolved) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh analysis")
                    }
                }
                if (!isToday) {
                    TextButton(
                        onClick = vm::jumpToToday,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("Viewing a past day — jump back to today")
                    }
                }
            }
        }
        item {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        // ---- Consumed so far (selected day) --------------------------------
        // Tappable (feedback 2026-09): the card is the brief summary; tapping
        // it opens the full day sheet (meals + supplements + water + misc).
        // Water sums honor the user's water-unit mode (Tail logs bare ml).
        item {
            ConsumedSummaryCard(
                state = state,
                isToday = isToday,
                waterL = WaterIntake.liters(tailEntries, waterUnitMode),
                mealCount = meals.size,
                supplementCount = supplements.size,
                otherEntryCount = tailEntries.count { it.kind != WaterIntake.KIND_WATER },
                onClick = { showConsumedDetail = true }
            )
        }
        item {
            TailSyncBanner(
                config = config, syncState = syncState,
                onOpenSetup = onOpenTailSetup, modifier = Modifier.fillMaxWidth()
            )
        }
        item { NutritionProcessingChip(nutritionState, unresolved, onRetry = vm::retryUnresolved) }

        // ---- Smart picks (feature C, TOP section) --------------------------
        // "See all" (feedback 2026-09) opens the full deficiency-keyed list.
        item {
            SmartPicksSection(
                picks = state.smartPicks,
                allPicks = state.allSmartPicks,
                cacheCold = state.smartPicksCacheCold,
                loading = state.loading,
                onOpenPick = { smartPickDetail = it },
                onSeeAll = { showAllPicks = true }
            )
        }

        // ---- Focus now (primary, actionable) ------------------------------
        item {
            SectionHeader(
                "Focus now",
                "Lately low and still low today."
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    if (state.focusNow.isEmpty()) {
                        EmptyState(
                            emoji = "🦉",
                            title = if (state.loading) "Crunching your numbers…"
                            else "Nothing needs attention",
                            body = if (state.loading) "One moment."
                            else "Your recent nutrient gaps are covered. Keep logging meals " +
                                "and any new gap will show up here with food suggestions.",
                            modifier = Modifier.padding(0.dp)
                        )
                    } else {
                        state.focusNow.forEach { item ->
                            FocusNowRow(
                                name = item.name,
                                tier = item.tier,
                                unit = item.unit,
                                todayIntake = item.todayIntake,
                                target = item.target,
                                todayCoverage = item.todayCoverage,
                                recentCoverage = item.recentCoverage,
                                onClick = { detailNutrientId = item.nutrientId }
                            )
                        }
                    }
                }
            }
        }

        // ---- Score ring (labeled) + trend ---------------------------------
        item { ScoreCard(state, onOpenInsights, onExplain = { showScoreExplainer = true }) }

        // ---- Quick stats ----------------------------------------------------
        item { CaloriesCard(state) }

        // ---- All-tier nutrients ----------------------------------------------
        item {
            SectionHeader(
                "Today's nutrients",
                "Tier 1 = critical, 2 = important, 3 = nice to have. All shown."
            )
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    if (state.tier1.isEmpty() && state.otherTiers.isEmpty()) {
                        EmptyState(
                            emoji = "🍽️",
                            title = "No data yet — add a meal",
                            body = "Use “+ Add” to log a meal, or connect Tail so meals " +
                                "import automatically. Nutrient bars appear once food is analyzed.",
                            actionLabel = null, onAction = null
                        )
                    }
                    state.tier1.forEach { comp ->
                        NutrientProgressRow(
                            name = comp.name, tier = comp.tier, unit = comp.unit,
                            intake = comp.intake, target = comp.target,
                            coverage = comp.coverage, status = comp.status,
                            emphasize = true,
                            onClick = { detailNutrientId = comp.nutrientId }
                        )
                    }
                    AnimatedVisibility(visible = tiersExpanded) {
                        Column {
                            state.otherTiers.forEach { comp ->
                                NutrientProgressRow(
                                    name = comp.name, tier = comp.tier, unit = comp.unit,
                                    intake = comp.intake, target = comp.target,
                                    coverage = comp.coverage, status = comp.status,
                                    onClick = { detailNutrientId = comp.nutrientId }
                                )
                            }
                        }
                    }
                    if (state.otherTiers.isNotEmpty()) {
                        TextButton(onClick = { tiersExpanded = !tiersExpanded }) {
                            Icon(
                                if (tiersExpanded) Icons.Filled.ExpandLess
                                else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (tiersExpanded) "Hide Tier 2 & 3 (${state.otherTiers.size})"
                                else "Show Tier 2 & 3 (${state.otherTiers.size})"
                            )
                        }
                    }
                }
            }
        }

        // ---- Insight summary ---------------------------------------------------
        item {
            Card(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenInsights)
            ) {
                Column(Modifier.padding(16.dp)) {
                    SectionHeader("What stands out", "Patterns from the last 7 days — tap for deep trends.")
                    Spacer(Modifier.height(8.dp))
                    val top = com.example.hoot.domain.insights.InsightsEngine.topSummary(state.insights)
                    if (top.isEmpty()) {
                        Text(
                            "Nothing noteworthy — keep it up!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    top.forEach { InsightRow(it) }
                }
            }
        }

        // ---- Meals + supplements -------------------------------------------
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    SectionHeader("Logged today", "Meals and supplements analyzed so far.")
                    Spacer(Modifier.height(8.dp))
                    if (meals.isEmpty() && supplements.isEmpty() && tailEntries.isEmpty()) {
                        Text(
                            "Use “+ Add” to log a meal or supplement.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    meals.forEach { meal ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Restaurant, contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                meal.title ?: meal.rawText,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    supplements.forEach { supp ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Bolt, contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                supp.label, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    tailEntries.forEach { entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (entry.kind == "water") "💧" else "•",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.size(width = 16.dp, height = 16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                entry.amount?.let { amt ->
                                    "${entry.habitName}: $amt ${entry.unit ?: ""}".trim()
                                } ?: "${entry.habitName}: ${entry.text}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Focus-now row: name + tier chip + "so far today" progress + 7-day hint. */
@Composable
private fun FocusNowRow(
    name: String,
    tier: Int,
    unit: String,
    todayIntake: Double,
    target: Double,
    todayCoverage: Double,
    recentCoverage: Double,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TierChip(tier)
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                "7-day avg ${(recentCoverage * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = ScoreLow
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${formatNutrient(todayIntake, unit)} of ${formatNutrient(target, unit)} today",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { todayCoverage.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = ScoreMid,
            trackColor = ScoreMid.copy(alpha = 0.15f)
        )
    }
}

@Composable
private fun ScoreCard(state: HomeUiState, onOpenInsights: () -> Unit, onExplain: () -> Unit) {
    val snapshot = state.score
    val score = snapshot?.score ?: 0.0
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Self-explanatory ring: big number + caption, tap → explainer.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.clickable(onClick = onExplain)
            ) {
                ProgressRing(
                    progress = score / 100.0,
                    modifier = Modifier.size(110.dp),
                    color = scoreColor(score, high = ScoreHigh, mid = ScoreMid, low = ScoreLow)
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "%.0f".format(score),
                        style = MaterialTheme.typography.headlineLarge
                    )
                    Text(
                        "/100",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Daily score",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (snapshot == null) "Waiting for data"
                        else "${snapshot.nutrientsMet}/${snapshot.nutrientsTracked} targets met",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onExplain, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.HelpOutline,
                            contentDescription = "What does this score mean?",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (snapshot == null) {
                    Text(
                        "Score appears once today's data lands.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.sparkline.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Sparkline(
                        values = state.sparkline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Last 7 days",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CaloriesCard(state: HomeUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SectionHeader("Energy & macros", "Quick stats for today.")
            Spacer(Modifier.height(8.dp))
            state.calories?.let { (kcal, unit) ->
                Text(
                    "${formatNutrient(kcal, unit)} today",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
            } ?: Text(
                "No calories logged yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                state.macros.forEach { m ->
                    Column {
                        Text(
                            formatNutrient(m.intake, m.unit),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            m.name, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** "Consumed so far" summary: energy, macros, water and entry counts. */
@Composable
private fun ConsumedSummaryCard(
    state: HomeUiState,
    isToday: Boolean,
    waterL: Double,
    mealCount: Int,
    supplementCount: Int,
    otherEntryCount: Int,
    onClick: () -> Unit
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(16.dp)) {
            SectionHeader(
                if (isToday) "Consumed so far today" else "Consumed that day",
                prettyDay(state.day)
            )
            Spacer(Modifier.height(8.dp))
            val nothing = state.calories == null &&
                state.macros.all { it.intake <= 0.0 } && waterL <= 0.0 &&
                mealCount == 0 && supplementCount == 0 && otherEntryCount == 0
            if (nothing) {
                Text(
                    "Nothing logged for this day yet — use “+ Add”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.calories
                                ?.let { (kcal, unit) -> formatNutrient(kcal, unit) } ?: "0 kcal",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Energy",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.macros.forEach { m ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(start = 12.dp)
                        ) {
                            Text(formatNutrient(m.intake, m.unit), style = MaterialTheme.typography.titleSmall)
                            Text(
                                m.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (waterL > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "💧 ${formatNutrient(waterL, "L")} water",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                val parts = buildList {
                    if (mealCount > 0) add("$mealCount meal${if (mealCount == 1) "" else "s"}")
                    if (supplementCount > 0) {
                        add("$supplementCount supplement${if (supplementCount == 1) "" else "s"}")
                    }
                    if (otherEntryCount > 0) add("$otherEntryCount other entries")
                }
                if (parts.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        parts.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tap for everything consumed this day",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun InsightRow(insight: Insight) {
    val badgeColor = when (insight.severity) {
        InsightSeverity.CRITICAL -> MaterialTheme.colorScheme.error
        InsightSeverity.WARNING -> ScoreMid
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(badgeColor, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(insight.title, style = MaterialTheme.typography.labelLarge)
            Text(
                insight.message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun NutritionProcessingChip(
    state: com.example.hoot.domain.nutrition.NutritionProcessState,
    unresolved: Int,
    onRetry: () -> Unit
) {
    if (state is com.example.hoot.domain.nutrition.NutritionProcessState.Processing) {
        AssistChip(
            onClick = onRetry,
            label = { Text("Analyzing nutrition… ${state.pending} foods left") },
            leadingIcon = {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        )
    } else if (unresolved > 0) {
        AssistChip(
            onClick = onRetry,
            label = { Text("$unresolved foods need analysis — tap to retry") },
            leadingIcon = {
                Icon(
                    Icons.Filled.Call, contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        )
    }
}
