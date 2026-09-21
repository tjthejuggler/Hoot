package com.example.hoot.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hoot.appGraph
import com.example.hoot.data.local.entity.MealEntity
import com.example.hoot.data.local.entity.ScoreSnapshotEntity
import com.example.hoot.data.local.entity.SupplementEntity
import com.example.hoot.data.local.entity.TailEntryEntity
import com.example.hoot.domain.insights.FocusNowEngine
import com.example.hoot.domain.insights.FocusNowInput
import com.example.hoot.domain.insights.FocusNowItem
import com.example.hoot.domain.insights.Insight
import com.example.hoot.domain.insights.InsightsEngine
import com.example.hoot.domain.insights.NutrientInsightDef
import com.example.hoot.domain.insights.SmartFoodPick
import com.example.hoot.domain.insights.WindowData
import com.example.hoot.domain.score.ScoreComponent
import com.example.hoot.domain.score.ScoreStatus
import com.example.hoot.ui.common.LIMIT_TRACKER_IDS
import com.example.hoot.ui.common.dayKeyMinusDays
import com.example.hoot.ui.common.effectiveTarget
import com.example.hoot.ui.common.todayKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import com.example.hoot.domain.insights.DietAwareSources
import com.example.hoot.domain.insights.DietTextFilter
import kotlinx.coroutines.launch

/** UI state of the Home dashboard (hybrid Today + Insights). */
data class HomeUiState(
    val day: String,
    val loading: Boolean = true,
    val score: ScoreSnapshotEntity? = null,
    /** Focus-now rows: lacking in the recent past AND still lacking today. */
    val focusNow: List<FocusNowItem> = emptyList(),
    /** Tier-1 nutrient rows (visible by default). */
    val tier1: List<ScoreComponent> = emptyList(),
    /** Tier-2 + Tier-3 rows (behind the expandable section). */
    val otherTiers: List<ScoreComponent> = emptyList(),
    val calories: Pair<Double, String>? = null,
    val macros: List<ScoreComponent> = emptyList(),
    val insights: List<Insight> = emptyList(),
    val sparkline: List<Double> = emptyList(),
    val unresolvedCount: Int = 0,
    /** Feature C — "Smart picks for you": multi-nutrient food matches. */
    val smartPicks: List<SmartFoodPick> = emptyList(),
    /** True when focus gaps exist but the cache is too cold to pick from. */
    val smartPicksCacheCold: Boolean = false
)

/**
 * Home dashboard (overhaul feedback #4): merges Today's ring/quick-stats/
 * meals with Insights' actionability. Primary section is "Focus now" —
 * nutrients lacking over the trailing 7 days AND still lacking so far today,
 * each tappable into the actionable [com.example.hoot.ui.common.NutrientDetailSheet].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app.appGraph
    private val day = todayKey()

    private val _insights = MutableStateFlow<List<Insight>>(emptyList())
    private val _unresolved = MutableStateFlow(0)

    val state: StateFlow<HomeUiState> =
        graph.nutrients.observeDailyTotals(day)
            .distinctUntilChanged()
            .flatMapLatest { totals ->
                // Ledger changed → refresh the (idempotent) score snapshot.
                if (totals.isNotEmpty()) {
                    runCatching { graph.scoreSnapshotter.recomputeDay(day) }
                }
                val snapshot = graph.nutrients.snapshot(day)
                val defs = graph.nutrients.definitionsAll()
                val goals = graph.nutrients.goalsAll().associateBy { it.nutrientId }
                val totalMap = totals.associate { it.nutrientId to it.total }

                // ---- All-tier nutrient components ------------------------
                val comps = defs.mapNotNull { def ->
                    val isLimit = def.id in LIMIT_TRACKER_IDS
                    val target = effectiveTarget(def, goals[def.id]?.targetValue)
                    if (target <= 0.0) return@mapNotNull null
                    val intake = totalMap[def.id] ?: 0.0
                    val coverage = if (isLimit) {
                        if (intake <= target) 1.0
                        else (1.0 - (intake - target) / (target * 0.5)).coerceIn(0.0, 1.0)
                    } else (intake / target).coerceIn(0.0, 1.0)
                    val exceeded = isLimit && intake > target ||
                        def.ulValue?.let { !isLimit && intake > it } == true
                    ScoreComponent(
                        nutrientId = def.id, name = def.name,
                        tier = goals[def.id]?.priority ?: def.tier,
                        unit = def.unit, intake = intake, target = target,
                        coverage = coverage, isLimitTracker = isLimit,
                        ulExceeded = exceeded,
                        status = when {
                            exceeded -> ScoreStatus.EXCESS
                            coverage >= 1.0 -> ScoreStatus.MET
                            coverage >= 0.75 -> ScoreStatus.CLOSE
                            else -> ScoreStatus.LOW
                        }
                    )
                }

                // ---- Focus now: trailing-7d average AND today gaps --------
                val recentFrom = dayKeyMinusDays(day, (FocusNowEngine.RECENT_DAYS - 1).toLong())
                val windowTotals = graph.nutrients
                    .observeDailyTotalsRange(recentFrom, day).first()
                    .groupBy { it.nutrientId }
                val focusInputs = comps.filter { !it.isLimitTracker }.map { comp ->
                    val series = windowTotals[comp.nutrientId].orEmpty()
                        .filter { (it.day ?: "") >= recentFrom }
                    FocusNowInput(
                        nutrientId = comp.nutrientId,
                        name = comp.name,
                        tier = comp.tier,
                        unit = comp.unit,
                        target = comp.target,
                        recentAvgIntake = series.map { it.total }.takeIf { it.isNotEmpty() }
                            ?.average() ?: 0.0,
                        todayIntake = comp.intake
                    )
                }
                val focus = FocusNowEngine.select(focusInputs)

                val byId = comps.associateBy { it.nutrientId }

                // ---- Smart picks (feature C): multi-nutrient food matching ----
                // Flow-driven: recomputes whenever today's totals (and hence
                // the focus list) change; cache-only → instant.
                val smartResult = runCatching {
                    graph.smartFoodProvider.refresh(
                        day = day,
                        gaps = focus,
                        windowAvg = windowTotals.mapValues { (_, rows) ->
                            rows.map { it.total }.average()
                        },
                        effectiveLimit = defs.associate {
                            it.id to effectiveTarget(it, goals[it.id]?.targetValue)
                        }
                    )
                }.getOrNull()

                val ui = HomeUiState(
                    day = day,
                    loading = false,
                    score = snapshot,
                    focusNow = focus,
                    tier1 = comps.filter { it.tier == 1 }.sortedBy { it.coverage },
                    otherTiers = comps.filter { it.tier != 1 }
                        .sortedWith(compareBy({ it.tier }, { it.coverage })),
                    calories = byId["calories"]?.let { it.intake to it.unit },
                    macros = listOf("protein", "carbohydrates", "total_fat")
                        .mapNotNull { byId[it] },
                    insights = _insights.value,
                    sparkline = _sparkValues(),
                    unresolvedCount = _unresolved.value,
                    smartPicks = smartResult?.picks ?: emptyList(),
                    smartPicksCacheCold = focus.isNotEmpty() &&
                        (smartResult == null || smartResult.cacheCandidates <
                            com.example.hoot.domain.insights.SmartFoodProvider.MIN_CANDIDATES)
                )
                kotlinx.coroutines.flow.flowOf(ui)
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState(day = day, loading = true))

    /** Meals + supplements logged today (reactive). */
    val meals: StateFlow<List<MealEntity>> = graph.meals.observeByDay(day)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val supplements: StateFlow<List<SupplementEntity>> = graph.meals.observeSupplementsByDay(day)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Tail water/misc habit entries logged today (v5) — minimal display rows
     * in Home's "today" list; no analytics yet.
     */
    val tailEntries: StateFlow<List<TailEntryEntity>> = graph.tailEntries.observeByDay(day)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Unresolved items awaiting nutrition resolution (retry chip). */
    val unresolved: StateFlow<Int> = _unresolved.asStateFlow()

    init {
        viewModelScope.launch {
            graph.nutrients.observeDailyTotalsRange(dayKeyMinusDays(day, 6), day)
                .collect { refreshInsights() }
        }
        // Diet changes must RE-RUN the analysis (diet-fix hardening,
        // 2026-09): previously refreshInsights fired only on intake changes,
        // so "Good sources" text generated under the old diet stuck on the
        // dashboard until an unrelated refresh happened to occur.
        viewModelScope.launch {
            combine(
                graph.tailConfig.observeDietaryProfile(),
                graph.settings.settings
            ) { room, s -> room to s }.collect { (room, s) ->
                _dietFilter = DietTextFilter.merged(
                    datastoreStyle = s.dietStyle,
                    datastoreAllergies = s.dietAllergies,
                    datastoreDislikes = s.dietDislikes,
                    roomStyle = room?.dietStyle,
                    roomAllergies = jsonList(room?.allergiesJson),
                    roomDislikes = jsonList(room?.dislikesJson)
                )
                refreshInsights()
            }
        }
        viewModelScope.launch {
            graph.nutritionProcessor.state.collect {
                _unresolved.value = runCatching {
                    graph.nutritionProcessor.pendingCount()
                }.getOrDefault(_unresolved.value)
            }
        }
    }

    /** Current diet gate (set in init; consumed by refreshInsights). */
    @Volatile
    private var _dietFilter: DietTextFilter = DietTextFilter()

    private suspend fun refreshInsights() {
        val defs = graph.nutrients.definitionsAll()
        val perDay = graph.nutrients.observeDailyTotalsRange(dayKeyMinusDays(day, 6), day).first()
        val intakeByDay = perDay.associate { (it.nutrientId to (it.day ?: "")) to it.total }
        val snapshots = graph.nutrients.observeScoreHistory(dayKeyMinusDays(day, 13)).first()
        val current = snapshots.filter { it.day >= dayKeyMinusDays(day, 6) }
        val previous = snapshots.filter { it.day < dayKeyMinusDays(day, 6) }
        val dietFilter = _dietFilter
        val window = WindowData(
            from = dayKeyMinusDays(day, 6), to = day,
            intakeByDay = intakeByDay,
            definitions = defs.associate {
                it.id to NutrientInsightDef(
                    id = it.id, name = it.name, unit = it.unit, tier = it.tier,
                    rda = it.rdaValue, ul = it.ulValue, foodSources = it.foodSources,
                    isLimitTracker = it.id in LIMIT_TRACKER_IDS
                )
            },
            goals = graph.nutrients.goalsAll().associateBy { it.nutrientId },
            scoreSnapshots = current,
            previousScoreSnapshots = previous,
            dietFilter = dietFilter
        )
        // Render-boundary defense in depth (diet-fix hardening, 2026-09):
        // messages re-filtered on the way out regardless of producer.
        _insights.value = runCatching { InsightsEngine.analyze(window) }
            .getOrDefault(emptyList())
            .map {
                it.copy(
                    message = DietAwareSources.sanitizeForDisplay(
                        it.message, dietFilter.toProfile()
                    )
                )
            }
    }

    /** `["a","b"]` → [a, b]; tolerant of null/blank/invalid JSON. */
    private fun jsonList(raw: String?): List<String> = runCatching {
        val arr = org.json.JSONArray(raw ?: "[]")
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }.getOrDefault(emptyList())

    private suspend fun _sparkValues(): List<Double> =
        graph.nutrients.observeScoreHistory(dayKeyMinusDays(day, 6)).first()
            .sortedBy { it.day }.map { it.score }

    /** Retry affordance: resets failure counters and re-resolves + re-aggregates. */
    fun retryUnresolved() = graph.nutritionProcessor.retryAll()
}
