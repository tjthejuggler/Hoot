package com.example.hoot.ui.nutrient

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hoot.appGraph
import com.example.hoot.data.local.entity.NutrientDefinitionEntity
import com.example.hoot.data.local.entity.RecommendationEntity
import com.example.hoot.domain.insights.DietAwareSources
import com.example.hoot.domain.insights.DietTextFilter
import com.example.hoot.domain.insights.NutrientGradeRow
import com.example.hoot.domain.insights.NutrientGrades
import com.example.hoot.domain.insights.NutrientSourceQuality
import com.example.hoot.domain.nutrition.FoodKnowledgeIndex
import com.example.hoot.ui.common.CoverageWindow
import com.example.hoot.ui.common.LIMIT_TRACKER_IDS
import com.example.hoot.ui.common.dayKeyMinusDays
import com.example.hoot.ui.common.dayKeyPlusDays
import com.example.hoot.ui.common.effectiveTarget
import com.example.hoot.ui.common.todayKey
import com.example.hoot.ui.common.windowDays
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray

/** One day of the intake series (null intake = nothing logged that day). */
data class DayPoint(val day: String, val intake: Double?)

/** Where a "new food idea" row came from. */
enum class FreshOrigin { KNOWLEDGE, CURATED, SEED }

/** A food you don't usually eat but that is rich in this nutrient. */
data class FreshFoodRow(
    val name: String,
    /** % of daily target covered by 100 g (null when no target/panel). */
    val pctPer100g: Int?,
    val origin: FreshOrigin
)

/** One of your usual sources of this nutrient (meal or supplement), 30-day window. */
data class UsualSourceRow(
    val name: String,
    val total: Double,
    /** Share of all logged intake of this nutrient, 0..100. */
    val sharePct: Int,
    val isSupplement: Boolean
)

/** State of the full-page nutrient detail (replaces the Insights bottom sheet). */
data class NutrientPageState(
    val nutrientId: String = "",
    val def: NutrientDefinitionEntity = NutrientDefinitionEntity(
        id = "", name = "", group = "", unit = "", rdaValue = null,
        ulValue = null, tier = 3, deficiencySymptoms = null,
        excessRisks = null, foodSources = null
    ),
    val target: Double = 0.0,
    val isLimitTracker: Boolean = false,
    val intakeToday: Double = 0.0,
    /** Today / 7-day / 30-day coverage summary rows. */
    val windows: List<CoverageWindow> = emptyList(),
    /** Visible chart window length in days; 0 = All (entire history). */
    val rangeDays: Int = 30,
    /** Days the visible window ends before today (0 = anchored at today). */
    val endOffset: Int = 0,
    /** Max valid endOffset for the current range (pan clamp; 0 when All). */
    val maxOffset: Int = 0,
    val series: List<DayPoint> = emptyList(),
    val seriesFrom: String = "",
    val seriesTo: String = "",
    val grade: NutrientGradeRow? = null,
    val avgPerDay: Double = 0.0,
    val daysTracked: Int = 0,
    val goodDays: Int = 0,
    val lowDays: Int = 0,
    /** Trailing consecutive on-target days (a day without data breaks it). */
    val streak: Int = 0,
    val usualSources: List<UsualSourceRow> = emptyList(),
    val freshFoods: List<FreshFoodRow> = emptyList(),
    val dietStyle: String = "omnivore",
    /** Diet-filtered seeded food_sources line. */
    val foodSourcesLine: String = "",
    val suggestions: List<RecommendationEntity> = emptyList(),
    val loadingMore: Boolean = false,
    val llmUnavailable: Boolean = false,
    val error: String? = null
) {
    val hasTarget: Boolean get() = target > 0
}

/**
 * Drives the full-screen nutrient detail page ([NutrientDetailScreen]):
 * everything the old bottom sheet showed plus a 7/30/90-day intake chart,
 * window grade, on-target streak, usual meal/supplement sources, diet-aware
 * "foods high in X you don't usually eat" (private food-knowledge base →
 * curated diet alternatives → seed sources) and the cache-first LLM
 * suggestion feed with "More suggestions".
 */
class NutrientPageViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app.appGraph
    private val day = todayKey()

    private val _state = MutableStateFlow(NutrientPageState())
    val state: StateFlow<NutrientPageState> = _state.asStateFlow()

    private var loaded = false

    /** Loads the page for one nutrient (idempotent; safe on recomposition). */
    fun open(nutrientId: String) {
        if (_state.value.nutrientId == nutrientId && loaded) return
        loaded = true
        viewModelScope.launch {
            val def = graph.nutrientDefinitions.definition(nutrientId) ?: return@launch
            val goal = graph.nutrientDefinitions.goal(nutrientId)?.targetValue
            val target = effectiveTarget(def, goal)
            val intakeToday = graph.intakeRepo.dailyTotals(day)
                .firstOrNull { it.nutrientId == nutrientId }?.total ?: 0.0
            val windows = listOf(7L to "This week", 30L to "This month").map { (days, label) ->
                val from = dayKeyMinusDays(day, days - 1)
                val totals = graph.intakeRepo.dailyTotalsForWindow(from, day)
                    .filter { it.nutrientId == nutrientId }
                CoverageWindow(
                    label = label,
                    intake = totals.map { it.total }.takeIf { it.isNotEmpty() }?.average() ?: 0.0,
                    target = target,
                    daysTracked = totals.size,
                    windowDays = days.toInt(),
                    unit = def.unit
                )
            }
            val filter = mergedDietFilter()
            _state.value = NutrientPageState(
                nutrientId = nutrientId,
                def = def,
                target = target,
                isLimitTracker = def.id in LIMIT_TRACKER_IDS,
                intakeToday = intakeToday,
                windows = windows,
                dietStyle = filter.dietStyle,
                foodSourcesLine = filter.filterList(def.foodSources ?: "", nutrientId)
            )
            loadSeries()
            loadSourcesAndIdeas(filter)
            generateMore(initial = true)
        }
    }

    /** Re-aggregates the chart/grade/stats for a new window length (0 = All). */
    fun setRange(days: Int) {
        if (_state.value.rangeDays == days) return
        _state.value = _state.value.copy(rangeDays = days, endOffset = 0)
        viewModelScope.launch { loadSeries() }
    }

    /** Pans the window BACK in time by half its length (swipe right / ‹). */
    fun panBack() {
        val s = _state.value
        if (s.rangeDays == 0 || s.endOffset >= s.maxOffset) return
        _state.value = s.copy(endOffset = (s.endOffset + stepFor(s.rangeDays)).coerceAtMost(s.maxOffset))
        viewModelScope.launch { loadSeries() }
    }

    /** Pans the window FORWARD (toward today) by half its length. */
    fun panForward() {
        val s = _state.value
        if (s.rangeDays == 0 || s.endOffset <= 0) return
        _state.value = s.copy(endOffset = (s.endOffset - stepFor(s.rangeDays)).coerceAtLeast(0))
        viewModelScope.launch { loadSeries() }
    }

    private fun stepFor(days: Int): Int = (days / 2).coerceAtLeast(1)

    /** "More suggestions": cache-first, then one scoped LLM call. */
    fun loadMore() {
        if (_state.value.loadingMore || !_state.value.hasTarget) return
        viewModelScope.launch { generateMore(initial = false) }
    }

    // ---- Internals ---------------------------------------------------------

    private suspend fun loadSeries() {
        val s = _state.value
        // Full-history read once: the earliest data day for THIS nutrient
        // drives the "All" span and the pan clamp.
        val allTotals = graph.intakeRepo.dailyTotalsForWindow("2000-01-01", day)
            .filter { it.nutrientId == s.nutrientId }
        val earliest = allTotals.mapNotNull { it.day?.takeIf { d -> d.isNotBlank() } }.minOrNull()
        val span = earliest?.let { windowDays(it, day).toInt() } ?: 0
        val days = if (s.rangeDays == 0) span.coerceAtLeast(1) else s.rangeDays
        val maxOffset = if (s.rangeDays == 0) 0 else (span - days).coerceAtLeast(0)
        val offset = if (s.rangeDays == 0) 0 else s.endOffset.coerceIn(0, maxOffset)
        val to = dayKeyMinusDays(day, offset.toLong())
        val from = dayKeyMinusDays(to, (days - 1).toLong())
        val byDay = allTotals.associate { (it.day ?: "") to it.total }
        val series = ArrayList<DayPoint>(days)
        var d = from
        repeat(days) {
            series += DayPoint(d, byDay[d])
            d = dayKeyPlusDays(d, 1)
        }
        val tracked = series.filter { it.intake != null }
        val avg = tracked.map { it.intake!! }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val good = tracked.count { isGoodDay(it.intake!!, s.target, s.isLimitTracker) }
        val low = tracked.size - good
        // Trailing streak: walk backwards while days have data AND are good.
        var streak = 0
        for (p in series.asReversed()) {
            val v = p.intake ?: break
            if (isGoodDay(v, s.target, s.isLimitTracker)) streak++ else break
        }
        val grade = if (s.hasTarget) {
            val coverages = tracked.map {
                dayCoverage(it.intake!!, s.target, s.isLimitTracker)
            }
            NutrientGrades.grade(
                nutrientId = s.nutrientId, name = s.def.name, tier = s.def.tier,
                unit = s.def.unit, isExcess = s.isLimitTracker,
                coverages = coverages, windowDays = days, isScoreable = true
            )
        } else null
        _state.value = _state.value.copy(
            series = series, grade = grade, avgPerDay = avg,
            daysTracked = tracked.size, goodDays = good, lowDays = low, streak = streak,
            endOffset = offset, maxOffset = maxOffset,
            seriesFrom = from, seriesTo = to
        )
    }

    private fun isGoodDay(intake: Double, target: Double, isLimit: Boolean): Boolean = when {
        isLimit -> intake <= target
        target <= 0 -> true
        else -> intake >= target * NutrientGrades.BAD_DAY_RATIO
    }

    /** Limit trackers: 1.0 = under cap, decaying as intake overshoots it. */
    private fun dayCoverage(intake: Double, target: Double, isLimit: Boolean): Double = when {
        isLimit -> if (intake <= target) 1.0 else (target / intake).coerceIn(0.0, 1.0)
        target <= 0 -> 1.0
        else -> intake / target
    }

    private suspend fun loadSourcesAndIdeas(filter: DietTextFilter) {
        val s = _state.value
        val nutrientId = s.nutrientId
        val from = dayKeyMinusDays(day, 29)
        // ---- Usual sources (meals + supplements, merged by display name) ---
        val mealTotals = graph.intakeRepo.mealContributions(s.nutrientId, from, day)
        val meals = graph.meals.mealsByIds(mealTotals.map { it.mealId }).associateBy { it.id }
        val suppTotals = graph.intakeRepo.supplementContributions(s.nutrientId, from, day)
        val supps = graph.meals.supplementsByIds(suppTotals.map { it.supplementId })
            .associateBy { it.id }
        data class Acc(var total: Double, var supplement: Boolean)
        val byName = LinkedHashMap<String, Acc>()
        mealTotals.forEach { mc ->
            val m = meals[mc.mealId] ?: return@forEach
            val name = (m.title ?: m.rawText).trim().takeIf { it.isNotBlank() } ?: return@forEach
            val key = name.lowercase().take(48)
            val acc = byName.getOrPut(key) { Acc(0.0, false) }
            acc.total += mc.total
        }
        suppTotals.forEach { sc ->
            val sup = supps[sc.supplementId] ?: return@forEach
            val name = sup.label.trim().takeIf { it.isNotBlank() } ?: return@forEach
            val key = "💊 " + name.lowercase().take(48)
            val acc = byName.getOrPut(key) { Acc(0.0, true) }
            acc.total += sc.total
        }
        val grand = byName.values.sumOf { it.total }.takeIf { it > 0 } ?: 0.0
        val usual = byName.entries
            .sortedByDescending { it.value.total }
            .take(6)
            .map { (key, acc) ->
                UsualSourceRow(
                    name = key.replaceFirstChar(Char::uppercase),
                    total = acc.total,
                    sharePct = if (grand > 0) (acc.total / grand * 100).toInt() else 0,
                    isSupplement = acc.supplement
                )
            }
        // ---- Fresh ideas: knowledge base → curated diet alternatives → seed --
        val usualNames = usual.map { it.name.lowercase() }
        fun isUsual(name: String): Boolean {
            val n = name.lowercase().trim()
            return usualNames.any { it.contains(n) || n.contains(it) }
        }
        val fresh = ArrayList<FreshFoodRow>()
        val seen = HashSet<String>()
        graph.foodKnowledge.all()
            .filter { nutrientId in FoodKnowledgeIndex.fromJson(it.highInJson) }
            .mapNotNull { row ->
                val per100 = FoodKnowledgeIndex.valuesFromJson(row.valuesJson)[nutrientId]
                    ?: return@mapNotNull null
                row.displayName to per100
            }
            .filter { (name, _) ->
                NutrientSourceQuality.isAcceptableSourceName(name) &&
                    filter.allows(name) && !isUsual(name)
            }
            .sortedByDescending { it.second }
            .forEach { (name, per100) ->
                if (seen.add(name.lowercase().trim())) {
                    fresh += FreshFoodRow(
                        name = name,
                        pctPer100g = if (s.hasTarget) (per100 / s.target * 100).toInt() else null,
                        origin = FreshOrigin.KNOWLEDGE
                    )
                }
            }
        val profile = filter.toProfile()
        (DietAwareSources.alternativesFor(nutrientId, profile) +
            DietAwareSources.tokenize(s.def.foodSources ?: ""))
            .filter { it.isNotBlank() }
            .filter { filter.allows(it) && NutrientSourceQuality.isAcceptableSourceName(it) }
            .filter { !isUsual(it) }
            .forEach { name ->
                if (seen.add(name.lowercase().trim())) {
                    fresh += FreshFoodRow(
                        name = name,
                        pctPer100g = null,
                        origin = if (DietAwareSources.alternativesFor(nutrientId, profile)
                            .any { it.equals(name, ignoreCase = true) }
                        ) FreshOrigin.CURATED else FreshOrigin.SEED
                    )
                }
            }
        _state.value = _state.value.copy(usualSources = usual, freshFoods = fresh.take(12))
    }

    private suspend fun generateMore(initial: Boolean) {
        _state.value = _state.value.copy(loadingMore = true, error = null)
        if (!_state.value.hasTarget) {
            _state.value = _state.value.copy(loadingMore = false)
            return
        }
        runCatching {
            graph.recommendationEngine.generateForNutrient(
                day = day,
                nutrientId = _state.value.nutrientId,
                count = 3,
                allowLlm = !initial
            )
        }.getOrElse {
            _state.value = _state.value.copy(
                loadingMore = false,
                error = "Couldn't fetch suggestions right now — check your connection and try again."
            )
            return
        }
        // Same render-boundary gates as the old sheet: diet filter + name
        // quality + coverage ordering, deduped by food, trailing 14-day pool.
        val s0 = graph.settings.current()
        val filter = mergedDietFilter()
        val windowFrom = dayKeyMinusDays(day, 13L)
        val recs = graph.recommendations.between(windowFrom, day)
            .filter { it.nutrientId == _state.value.nutrientId }
            .filter {
                filter.allows(it.foodName) && filter.allows(it.reasonText) &&
                    NutrientSourceQuality.isAcceptableSourceName(it.foodName)
            }
            .sortedWith(
                compareByDescending<RecommendationEntity> { it.day }
                    .thenByDescending { it.accepted == null }
                    .thenByDescending {
                        NutrientSourceQuality.parseCoveragePct(it.reasonText) ?: 0.0
                    }
                    .thenBy { it.foodName }
            )
            .distinctBy { it.foodName.lowercase().trim() }
            .take(10)
        _state.value = _state.value.copy(
            suggestions = recs,
            loadingMore = false,
            llmUnavailable = !s0.llmConfigured,
            error = when {
                recs.isEmpty() && !s0.llmConfigured ->
                    "No cached suggestions for ${_state.value.def.name} yet. " +
                        "Connect an LLM in Settings to generate ideas."
                recs.isEmpty() -> "No more suggestions found — try again later."
                else -> null
            }
        )
    }

    /** Safety-biased merge of DataStore + Room diet profiles (see DietTextFilter.merged). */
    private suspend fun mergedDietFilter(): DietTextFilter {
        val s = graph.settings.current()
        val room = runCatching { graph.tailConfig.dietaryProfile() }.getOrNull()
        return DietTextFilter.merged(
            datastoreStyle = s.dietStyle,
            datastoreAllergies = s.dietAllergies,
            datastoreDislikes = s.dietDislikes,
            roomStyle = room?.dietStyle,
            roomAllergies = jsonList(room?.allergiesJson),
            roomDislikes = jsonList(room?.dislikesJson)
        )
    }

    /** `["a","b"]` → [a, b]; tolerant of null/blank/invalid JSON. */
    private fun jsonList(raw: String?): List<String> = runCatching {
        val arr = JSONArray(raw ?: "[]")
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }.getOrDefault(emptyList())
}
