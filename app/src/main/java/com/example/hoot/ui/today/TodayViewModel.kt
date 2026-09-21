package com.example.hoot.ui.today

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hoot.appGraph
import com.example.hoot.data.local.entity.MealEntity
import com.example.hoot.data.local.entity.ScoreSnapshotEntity
import com.example.hoot.data.local.entity.SupplementEntity
import com.example.hoot.domain.insights.DietAwareSources
import com.example.hoot.domain.insights.DietTextFilter
import com.example.hoot.domain.insights.Insight
import com.example.hoot.domain.insights.InsightsEngine
import com.example.hoot.domain.insights.NutrientInsightDef
import com.example.hoot.domain.insights.WindowData
import com.example.hoot.domain.score.ScoreComponent
import com.example.hoot.domain.score.ScoreStatus
import com.example.hoot.ui.common.LIMIT_TRACKER_IDS
import com.example.hoot.ui.common.dayKeyMinusDays
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
import kotlinx.coroutines.launch

/** UI state of the Today dashboard. */
data class TodayUiState(
    val day: String,
    val loading: Boolean = true,
    val score: ScoreSnapshotEntity? = null,
    val tier1: List<ScoreComponent> = emptyList(),
    val otherComponents: List<ScoreComponent> = emptyList(),
    val calories: Pair<Double, String>? = null,
    val macros: List<ScoreComponent> = emptyList(),
    val insights: List<Insight> = emptyList(),
    val sparkline: List<Double> = emptyList(),
    val unresolvedCount: Int = 0
)

/**
 * Today dashboard (phase 4): observes today's intake ledger reactively,
 * recomputes the (idempotent) daily score snapshot whenever it changes, and
 * derives the Tier-1 breakdown + rule-based insight summary for the cards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app.appGraph
    private val day = todayKey()

    private val _insights = MutableStateFlow<List<Insight>>(emptyList())
    private val _unresolved = MutableStateFlow(0)

    val state: StateFlow<TodayUiState> =
        graph.nutrients.observeDailyTotals(day)
            .distinctUntilChanged()
            .flatMapLatest { totals ->
                // Ledger changed → refresh the score snapshot (cheap, idempotent).
                if (totals.isNotEmpty()) {
                    runCatching { graph.scoreSnapshotter.recomputeDay(day) }
                }
                val snapshot = graph.nutrients.snapshot(day)
                val defs = graph.nutrients.definitionsAll()
                val goals = graph.nutrients.goalsAll().associateBy { it.nutrientId }
                val totalMap = totals.associate { it.nutrientId to it.total }

                val comps = defs.mapNotNull { def ->
                    val isLimit = def.id in LIMIT_TRACKER_IDS
                    val target = com.example.hoot.ui.common.effectiveTarget(
                        def, goals[def.id]?.targetValue
                    )
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

                val byId = comps.associateBy { it.nutrientId }
                val ui = TodayUiState(
                    day = day,
                    loading = false,
                    score = snapshot,
                    tier1 = comps.filter { it.tier == 1 }.sortedBy { it.coverage },
                    otherComponents = comps.filter { it.tier != 1 }
                        .sortedWith(compareBy({ it.tier }, { it.coverage })),
                    calories = byId["calories"]?.let { it.intake to it.unit },
                    macros = listOf("protein", "carbohydrates", "total_fat")
                        .mapNotNull { byId[it] },
                    insights = _insights.value,
                    sparkline = _sparkValues(),
                    unresolvedCount = _unresolved.value
                )
                kotlinx.coroutines.flow.flowOf(ui)
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, TodayUiState(day = day, loading = true))

    /** Meals + supplements logged today (reactive, for the logged-items card). */
    val meals: StateFlow<List<MealEntity>> = graph.meals.observeByDay(day)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val supplements: StateFlow<List<SupplementEntity>> = graph.meals.observeSupplementsByDay(day)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Unresolved items awaiting nutrition resolution (retry chip). */
    val unresolved: StateFlow<Int> = _unresolved.asStateFlow()

    init {
        // Refresh the rule-based insight summary whenever history/ledger moves.
        viewModelScope.launch {
            graph.nutrients.observeDailyTotalsRange(dayKeyMinusDays(day, 6), day)
                .collect { refreshInsights() }
        }
        // Diet changes must RE-RUN the analysis (diet-fix hardening,
        // 2026-09): previously the profile was only re-read on intake
        // changes, so text generated under the old diet stuck on screen.
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
        // Persisted pending count for the retry affordance (cheap COUNT
        // queries; reflects the same filter the processor enqueues).
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
        // Diet restrictions MUST gate the "Good sources" / "Foods high in X"
        // text on the Today dashboard too (diet-fix, 2026-09). Merged
        // DataStore+Room filter (safety-biased — see DietTextFilter.merged).
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
