package com.example.hoot.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hoot.appGraph
import com.example.hoot.data.local.entity.FoodEntity
import com.example.hoot.data.local.entity.NutrientDefinitionEntity
import com.example.hoot.data.local.entity.NutrientGoalEntity
import com.example.hoot.ui.common.dayKeyMinusDays
import com.example.hoot.ui.common.todayKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Time windows offered by the History screen. */
enum class HistoryWindow(val label: String, val days: Long?) {
    D7("7d", 7), D30("30d", 30), D90("90d", 90), D1Y("1y", 365), ALL("All", null)
}

/** One bar/point of the per-day nutrient series. */
data class DayPoint(val day: String, val intake: Double, val target: Double)

/** Top contributing food aggregate. */
data class FoodContributor(val food: FoodEntity, val total: Double, val pctOfWindow: Double)

/** Stats header for the selected nutrient/window. */
data class NutrientStats(
    val avg: Double,
    val daysMeeting: Int,
    val daysWithData: Int,
    val trendUp: Boolean?,
    val firstHalfAvg: Double,
    val secondHalfAvg: Double
)

data class HistoryUiState(
    val definitions: List<NutrientDefinitionEntity> = emptyList(),
    val goals: Map<String, NutrientGoalEntity> = emptyMap(),
    val selected: NutrientDefinitionEntity? = null,
    val window: HistoryWindow = HistoryWindow.D30,
    val search: String = "",
    val series: List<DayPoint> = emptyList(),
    val stats: NutrientStats? = null,
    val contributors: List<FoodContributor> = emptyList(),
    val loading: Boolean = true
)

/**
 * Nutrient-centric history (phase 4): per-day intake series vs the RDA
 * reference line, stats header, and top food contributors for the window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app.appGraph
    private val today = todayKey()

    private val _selected = MutableStateFlow<NutrientDefinitionEntity?>(null)
    private val _window = MutableStateFlow(HistoryWindow.D30)
    private val _search = MutableStateFlow("")
    private val _series = MutableStateFlow<List<DayPoint>>(emptyList())
    private val _stats = MutableStateFlow<NutrientStats?>(null)
    private val _contributors = MutableStateFlow<List<FoodContributor>>(emptyList())

    val window: StateFlow<HistoryWindow> = _window.asStateFlow()
    val search: StateFlow<String> = _search.asStateFlow()

    /** Searchable, tier-badged picker list. */
    val picker: StateFlow<List<NutrientDefinitionEntity>> =
        combine(graph.nutrients.observeDefinitions(), _search) { defs, q ->
            if (q.isBlank()) defs
            else defs.filter {
                it.name.contains(q, ignoreCase = true) || it.id.contains(q, ignoreCase = true)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val state: StateFlow<HistoryUiState> = combine(
        graph.nutrients.observeDefinitions(),
        graph.nutrients.observeGoals(),
        _selected,
        _window,
        _search
    ) { defs, goals, sel, win, q ->
        HistoryUiState(
            definitions = if (q.isBlank()) defs else defs.filter {
                it.name.contains(q, ignoreCase = true) || it.id.contains(q, ignoreCase = true)
            },
            goals = goals.associateBy { it.nutrientId },
            selected = sel ?: defs.firstOrNull(),
            window = win,
            search = q,
            series = _series.value,
            stats = _stats.value,
            contributors = _contributors.value,
            loading = false
        )
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        HistoryUiState(loading = true)
    )

    init {
        // Reload the detail data whenever selection or window changes.
        viewModelScope.launch {
            combine(_selected, _window) { sel, win -> sel to win }
                .collect { (sel, win) ->
                    if (sel == null) {
                        val first = graph.nutrients.definitionsAll().firstOrNull()
                        if (first != null) _selected.value = first
                        return@collect
                    }
                    loadDetail(sel, win)
                }
        }
    }

    fun select(def: NutrientDefinitionEntity) {
        _selected.value = def
    }

    fun setWindow(win: HistoryWindow) {
        _window.value = win
    }

    fun setSearch(q: String) {
        _search.value = q
    }

    private suspend fun loadDetail(def: NutrientDefinitionEntity, win: HistoryWindow) {
        val goal = graph.nutrients.goal(def.id)
        val target = com.example.hoot.ui.common.effectiveTarget(def, goal?.targetValue)
        val from = win.days?.let { dayKeyMinusDays(today, it - 1) } ?: "1970-01-01"
        val to = today

        // Reactive series would fight the manual loader; this is a snapshot read.
        val perDay = graph.nutrients.dailyTotalsForWindow(from, to)
        val byDay = perDay.filter { it.nutrientId == def.id }
            .associate { (it.day ?: "") to it.total }

        val days = byDay.keys.filter { it.isNotBlank() }.sorted()
        val series = days.map { DayPoint(it, byDay[it] ?: 0.0, target) }
        _series.value = series

        // Stats header: avg, % days meeting, trend (2nd half vs 1st half).
        val withData = series.filter { it.intake > 0.0 }
        val half = withData.size / 2
        val firstAvg = if (half > 0) withData.take(half).map { it.intake }.average() else 0.0
        val secondAvg = if (half > 0) withData.drop(half).map { it.intake }.average() else firstAvg
        _stats.value = if (withData.isEmpty()) null else NutrientStats(
            avg = withData.map { it.intake }.average(),
            daysMeeting = series.count { it.intake >= target },
            daysWithData = series.size,
            trendUp = if (half > 0) secondAvg > firstAvg else null,
            firstHalfAvg = firstAvg,
            secondHalfAvg = secondAvg
        )

        // Top food contributors: meal contributions split across the meal's
        // resolved ingredients (per-ingredient nutrient shares are not stored,
        // so an even split within the meal is the honest approximation).
        val contribs = graph.nutrients.mealContributions(def.id, from, to)
        val byFood = HashMap<String, Double>()
        val meals = graph.meals.mealsByIds(contribs.map { it.mealId }).associateBy { it.id }
        for (c in contribs) {
            val meal = meals[c.mealId] ?: continue
            val ings = graph.meals.ingredientsForMeal(meal.id).filter { it.foodId != null }
            if (ings.isEmpty()) continue
            val share = c.total / ings.size
            ings.forEach { ing -> byFood[ing.foodId!!] = (byFood[ing.foodId!!] ?: 0.0) + share }
        }
        val windowTotal = byFood.values.sum().takeIf { it > 0 } ?: 1.0
        _contributors.value = byFood.entries
            .sortedByDescending { it.value }
            .take(8)
            .mapNotNull { entry ->
                graph.nutrients.food(entry.key)?.let {
                    FoodContributor(it, entry.value, entry.value / windowTotal)
                }
            }
    }
}
