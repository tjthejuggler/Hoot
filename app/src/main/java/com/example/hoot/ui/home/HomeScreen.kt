package com.example.hoot.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.hoot.ui.common.NutrientProgressRow
import com.example.hoot.ui.common.SUBSECTION_SCROLL_HEIGHT
import com.example.hoot.ui.common.ScoreExplainerSheet
import com.example.hoot.ui.common.SectionHeader
import com.example.hoot.ui.common.TierChip
import com.example.hoot.ui.common.dayKeyToDatePickerMillis
import com.example.hoot.ui.common.datePickerMillisToDayKey
import com.example.hoot.ui.common.formatNutrient
import com.example.hoot.ui.common.killParentFling
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
    onOpenTailSetup: () -> Unit = {},
    onOpenNutrient: (String) -> Unit = {},
    /** Day preselected by an external navigation (nutrient day-node "Open this day"). */
    pendingDay: String? = null
) {
    val context = LocalContext.current
    val vm: HomeViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val meals by vm.meals.collectAsStateWithLifecycle()
    val supplements by vm.supplements.collectAsStateWithLifecycle()
    val tailEntries by vm.tailEntries.collectAsStateWithLifecycle()
    val unresolved by vm.unresolved.collectAsStateWithLifecycle()
    val researching by vm.researching.collectAsStateWithLifecycle()

    val config by context.appGraph.tailConfig.observeTailConfig()
        .collectAsStateWithLifecycle(initialValue = null)
    val syncState by context.appGraph.tailSync.syncState.collectAsStateWithLifecycle()
    val nutritionState by context.appGraph.nutritionProcessor.state.collectAsStateWithLifecycle()

    var showScoreExplainer by remember { mutableStateOf(false) }
    var smartPickDetail by remember { mutableStateOf<com.example.hoot.domain.insights.SmartFoodPick?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showConsumedDetail by remember { mutableStateOf(false) }
    var showAllPicks by remember { mutableStateOf(false) }
    // Per-item nutrient breakdown (feedback 2026-11-18): tapping a logged
    // meal/supplement opens its attributed-nutrients sheet (editable).
    val itemNutrientState by vm.itemNutrients.collectAsStateWithLifecycle()
    var symptomDetail by remember {
        mutableStateOf<com.example.hoot.domain.insights.SymptomMatcher.RankedSymptom?>(null)
    }

    // Selected dashboard day (header arrows / date picker navigate it).
    val selectedDay by vm.day.collectAsStateWithLifecycle()
    val isToday = selectedDay == todayKey()

    // External day preselection (nutrient day-node "Open this day").
    LaunchedEffect(pendingDay) {
        pendingDay?.let { vm.selectDay(it) }
    }

    // Water unit interpretation (feedback 2026-09): what a bare number in the
    // Tail water habit means ("ml" for Tail's raw-ml logs). Reactive — the
    // card re-sums immediately when the mode changes in Tail setup.
    val settingsState by context.appGraph.settings.settings
        .collectAsStateWithLifecycle(initialValue = null)
    val waterUnitMode = settingsState?.waterUnitMode ?: "auto"

    if (showScoreExplainer) {
        ScoreExplainerSheet(snapshot = state.score, onDismiss = { showScoreExplainer = false })
    }
    smartPickDetail?.let { pick ->
        SmartPickDetailSheet(
            pick = pick,
            onDismiss = { smartPickDetail = null },
            allPicks = state.allSmartPicks.ifEmpty { state.smartPicks },
            onOpenPick = { smartPickDetail = it }
        )
    }
    if (showConsumedDetail) {
        ConsumedDayDetailSheet(
            day = selectedDay,
            isToday = isToday,
            meals = meals,
            supplements = supplements,
            tailEntries = tailEntries,
            waterUnitMode = waterUnitMode,
            onOpenMealNutrients = { meal ->
                showConsumedDetail = false
                vm.openMealNutrients(meal)
            },
            onOpenSupplementNutrients = { supp ->
                showConsumedDetail = false
                vm.openSupplementNutrients(supp)
            },
            onDismiss = { showConsumedDetail = false }
        )
    }
    symptomDetail?.let { symptom ->
        SymptomDetailSheet(
            symptomKey = symptom.symptomKey,
            display = symptom.displayName,
            nutrients = symptom.contributors,
            onOpenNutrient = onOpenNutrient,
            onDismiss = { symptomDetail = null }
        )
    }
    if (itemNutrientState.ref != null) {
        ItemNutrientSheet(
            state = itemNutrientState,
            onEditDraft = vm::editItemNutrientDraft,
            onAddNutrient = vm::addItemNutrientRow,
            onSave = vm::saveItemNutrients,
            onReset = vm::resetItemNutrient,
            onDismiss = vm::closeItemNutrients
        )
    }
    if (showAllPicks) {
        AllSmartPicksSheet(
            picks = state.allSmartPicks,
            deepPicks = state.deepSmartPicks,
            gaps = state.focusNow,
            loading = state.loading,
            onOpenPick = { smartPickDetail = it },
            onDismiss = { showAllPicks = false },
            knowledgeCount = state.knowledgeCount,
            researching = researching,
            onResearch = vm::researchMoreFoods
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
            // Startup UX fix (2026-11-16): with a cached snapshot the screen
            // is fully usable instantly; the live refresh continues in the
            // background and only this thin hint shows while it runs.
            if (state.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (state.fromCache) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Updating from your latest logs…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
        // (feedback 2026-11-17): lists EVERY nutrient that is chronically
        // low (7/30/90/365-day deficiency model), no count cap. Uniform
        // subsection height (2026-11-17): the inner list holds the SAME
        // fixed height as the symptoms section — taller lists scroll INSIDE.
        item {
            SectionHeader(
                "Focus now",
                "Chronically low nutrients, ranked by long-term gap."
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
                        val scroll = rememberScrollState()
                        Column(
                            Modifier
                                .fillMaxWidth()
                                // Uniform height — same as every other
                                // scrollable Home subsection.
                                .height(SUBSECTION_SCROLL_HEIGHT)
                                // Fling dies at the section edge — leftover
                                // momentum never chains to the dashboard list.
                                .killParentFling()
                                .verticalScroll(scroll)
                        ) {
                            state.focusNow.forEach { item ->
                                FocusNowRow(
                                    name = item.name,
                                    tier = item.tier,
                                    unit = item.unit,
                                    todayIntake = item.todayIntake,
                                    target = item.target,
                                    todayCoverage = item.todayCoverage,
                                    recentCoverage = item.recentCoverage,
                                    worstWindowDays = item.worstWindowDays,
                                    worstWindowAvgCoverage = item.worstWindowAvgCoverage,
                                    onClick = { onOpenNutrient(item.nutrientId) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- Score ring (labeled) + trend ---------------------------------
        item { ScoreCard(state, onOpenInsights, onExplain = { showScoreExplainer = true }) }

        // (feedback 2026-11-17) The separate "Energy & macros" quick-stats
        // card is GONE — the Consumed-so-far card at the top already shows
        // energy + macros, making this section redundant.

        // ---- All-tier nutrients ----------------------------------------------
        // (feedback 2026-11-17): one CONTINUOUS scrollable list — the old
        // Tier-2/3 expand button is gone; tiers stay ordered but scroll
        // inside the same uniform-height subsection as everything else.
        item {
            SectionHeader(
                "Today's nutrients",
                "Tier 1 = critical, 2 = important, 3 = nice to have."
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
                    } else {
                        val scroll = rememberScrollState()
                        Column(
                            Modifier
                                .fillMaxWidth()
                                // Uniform height — same as every other
                                // scrollable Home subsection.
                                .height(SUBSECTION_SCROLL_HEIGHT)
                                .killParentFling()
                                .verticalScroll(scroll)
                        ) {
                            (state.tier1 + state.otherTiers).forEach { comp ->
                                NutrientProgressRow(
                                    name = comp.name, tier = comp.tier, unit = comp.unit,
                                    intake = comp.intake, target = comp.target,
                                    coverage = comp.coverage, status = comp.status,
                                    emphasize = comp.tier == 1,
                                    onClick = { onOpenNutrient(comp.nutrientId) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- Likely symptoms (deficiency system) ------------------------------
        if (state.likelySymptoms.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    LikelySymptomsCard(
                        symptoms = state.likelySymptoms,
                        onOpenSymptom = { key ->
                            state.likelySymptoms.firstOrNull { it.symptomKey == key }
                                ?.let { symptomDetail = it }
                        }
                    )
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
                                .clickable { vm.openMealNutrients(meal) }
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
                                .clickable { vm.openSupplementNutrients(supp) }
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

