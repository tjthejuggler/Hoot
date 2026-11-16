package com.example.hoot.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hoot.appGraph
import com.example.hoot.data.local.entity.MealEntity
import com.example.hoot.data.local.entity.ScoreSnapshotEntity
import com.example.hoot.data.local.entity.SupplementEntity
import com.example.hoot.data.local.entity.TailEntryEntity
import com.example.hoot.data.local.dao.NutrientDayTotal
import com.example.hoot.domain.insights.DeficiencyModel
import com.example.hoot.domain.insights.FocusNowEngine
import com.example.hoot.domain.insights.FocusNowInput
import com.example.hoot.domain.insights.FocusNowItem
import com.example.hoot.domain.insights.SymptomMatcher
import com.example.hoot.domain.insights.Insight
import com.example.hoot.domain.insights.InsightsEngine
import com.example.hoot.domain.insights.NutrientInsightDef
import com.example.hoot.domain.insights.SmartFoodPick
import com.example.hoot.domain.insights.WindowData
import com.example.hoot.domain.score.ScoreComponent
import com.example.hoot.domain.score.ScoreStatus
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onEach
import com.example.hoot.ui.common.LIMIT_TRACKER_IDS
import com.example.hoot.ui.common.dayKeyMinusDays
import com.example.hoot.ui.common.dayKeyPlusDays
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
    /**
     * Full candidate ranking (feedback 2026-09 "see all"): the same matcher
     * without the Home cap — every food that covers ≥1 current gap, worst
     * first. Shown in [AllSmartPicksSheet]; [smartPicks] stays the curated
     * top slice for the dashboard.
     */
    val allSmartPicks: List<SmartFoodPick> = emptyList(),
    /**
     * Deepest ranking (feedback 2026-09-21 "generate even more"): quality
     * floor fully relaxed — strict superset of [allSmartPicks]. The
     * [AllSmartPicksSheet] hands this out only after the standard ranking is
     * exhausted. Always precomputed (zero-LLM, memoized).
     */
    val deepSmartPicks: List<SmartFoodPick> = emptyList(),
    /** Likely symptoms from the deficiency model (deficiency system, 2026-11-16). */
    val likelySymptoms: List<SymptomMatcher.RankedSymptom> = emptyList(),
    /** True when focus gaps exist but the cache is too cold to pick from. */
    val smartPicksCacheCold: Boolean = false,
    /** Size of the permanent food-knowledge base (feedback 2026-09-27). */
    val knowledgeCount: Int = 0,
    /**
     * Startup UX fix (2026-11-16): this state came from the last-session
     * cache — the live DB pipeline is still refreshing it in the background.
     * Shows the "updating" hint instead of a full-screen loading state.
     */
    val fromCache: Boolean = false
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
    private val today = todayKey()

    /** Selected day key ("yyyy-MM-dd") — header arrows / date picker navigate this. */
    private val _day = MutableStateFlow(todayKey())
    val day: StateFlow<String> = _day.asStateFlow()

    private val _insights = MutableStateFlow<List<Insight>>(emptyList())
    private val _unresolved = MutableStateFlow(0)

    /** Gap-targeted online research in flight (button state, 2026-09-27). */
    private val _researching = MutableStateFlow(false)
    val researching: StateFlow<Boolean> = _researching.asStateFlow()

    /**
     * Refresh tick (knowledge base, 2026-09-27): bumped after a research
     * pass adds knowledge so the memoized smart-picks recompute immediately
     * without waiting for an unrelated ledger emission.
     */
    private val _refreshTick = MutableStateFlow(0)

    /**
     * Research tick (deficiency system): bumped whenever a research pass
     * lands new associations/foods, so the symptoms + picks sections
     * recompute from FRESH facts without waiting for a ledger change.
     */
    private val _researchTick = MutableStateFlow(0)

    /** Called by the nutrient page VM after a research pass (same process). */
    fun onResearchLanded() {
        _researchTick.value += 1
    }

    init {
        // Research integration: any completed research pass re-ranks the
        // likely-symptoms + smart-picks sections with the fresh facts.
        viewModelScope.launch {
            graph.researchEvents.collect { _researchTick.value += 1 }
        }
    }

    val state: StateFlow<HomeUiState> = combine(_day, _refreshTick, _researchTick) { d, _, _ -> d }
        .flatMapLatest { day ->
            // Reactive over BOTH the selected day AND the trailing-7d range:
            // backfills that touch only HISTORICAL days (supplement
            // self-heal, re-resolved ingredients) previously left the
            // Focus-now 7-day average stale — today's flow never re-emitted,
            // so Home kept a pre-repair "2%" while the nutrient page, which
            // computes on open, showed 167% (feedback 2026-11-16).
            val recentFrom = dayKeyMinusDays(day, (FocusNowEngine.RECENT_DAYS - 1).toLong())
            combine(
                graph.nutrients.observeDailyTotals(day).distinctUntilChanged(),
                graph.nutrients.observeDailyTotalsRange(recentFrom, day).distinctUntilChanged()
            ) { totals, rangeRows -> totals to rangeRows }
                .flatMapLatest { (totals, rangeRows) ->
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
                    // 0-kcal bug fix (2026-09-21): the old `target <= 0 → drop`
                    // filter silently discarded calories/total_fat (their seed
                    // RDA is null → effectiveTarget 0) although the ledger had
                    // real values — the Energy card read "No calories logged
                    // yet" on every day. Keep a row when EITHER side exists;
                    // coverage math guards the target below.
                    if (target <= 0.0 && totalMap[def.id] == null) return@mapNotNull null
                    val intake = totalMap[def.id] ?: 0.0
                    val coverage = when {
                        target <= 0.0 -> 0.0        // no goal set — display-only row
                        isLimit -> if (intake <= target) 1.0
                        else (1.0 - (intake - target) / (target * 0.5)).coerceIn(0.0, 1.0)
                        else -> (intake / target).coerceIn(0.0, 1.0)
                    }
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
                val windowTotals = rangeRows.groupBy { it.nutrientId }
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

                // ---- Likely symptoms (deficiency system, 2026-11-16) ------
                // Multi-window coverage → DeficiencyModel assessments →
                // SymptomMatcher ranking over seed+research associations.
                val likelySymptoms = runCatching { computeSymptoms(day, comps, windowTotals) }
                    .onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        android.util.Log.e("HootSymptoms", "symptom ranking failed", it)
                    }
                    .getOrDefault(emptyList())

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
                }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    // Silent-swallow fix (2026-09-21): a failing refresh left
                    // picks empty AND flagged the section "cache cold",
                    // pinning the misleading "Building your smart picks"
                    // placeholder with no diagnostic trail. Log it loudly; the
                    // flag below stays false on failure (an error is not a
                    // cold cache). CancellationException is rethrown —
                    // flatMapLatest legitimately cancels superseded runs and
                    // that is not an error.
                    android.util.Log.e("HootSmartPicks", "smart picks refresh failed", it)
                }.getOrNull()

                val ui = HomeUiState(
                    day = day,
                    loading = false,
                    score = snapshot,
                    focusNow = focus,
                    // Tier bars only for rows with a real goal; display-only
                    // rows (calories/total_fat without a target) just feed the
                    // Energy & macros card above.
                    tier1 = comps.filter { it.tier == 1 && it.target > 0 }
                        .sortedBy { it.coverage },
                    otherTiers = comps.filter { it.tier != 1 && it.target > 0 }
                        .sortedWith(compareBy({ it.tier }, { it.coverage })),
                    calories = byId["calories"]?.let { it.intake to it.unit },
                    macros = listOf("protein", "carbohydrates", "total_fat")
                        .mapNotNull { byId[it] },
                    insights = _insights.value,
                    sparkline = _sparkValues(),
                    likelySymptoms = likelySymptoms,
                    unresolvedCount = _unresolved.value,
                    smartPicks = smartResult?.picks ?: emptyList(),
                    allSmartPicks = smartResult?.allPicks ?: emptyList(),
                    deepSmartPicks = smartResult?.deepPicks ?: emptyList(),
                    // "Cache cold" now keys off the REAL scoreable pool (DB
                    // cache + seed top-up): the raw DB-candidate count kept
                    // the placeholder up although the bundled seed pool was
                    // scoring fine (bug 2026-09-21).
                    smartPicksCacheCold = focus.isNotEmpty() && smartResult != null &&
                        smartResult.poolSize <
                            com.example.hoot.domain.insights.SmartFoodProvider.MIN_CANDIDATES,
                    knowledgeCount = runCatching { graph.foodKnowledge.count() }.getOrDefault(0)
                )
                kotlinx.coroutines.flow.flowOf(ui)
            }
        }
        .onStart {
            // Cache-first startup (2026-11-16): emit the last session's
            // dashboard IMMEDIATELY while the live pipeline runs. The first
            // live emission replaces it wholesale (fromCache=false there);
            // until then the user sees yesterday's-real data instead of a
            // spinner that, during startup self-heal ledger churn, could
            // spin for a very long time with nothing on screen.
            HomeStateCache.load(getApplication())?.let { emit(it) }
        }
        .onEach { state ->
            // Persist only LIVE results (a cached emission would just
            // rewrite the same file). Skip the placeholder initial state.
            if (!state.fromCache && !state.loading) {
                HomeStateCache.store(getApplication(), state)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState(day = todayKey(), loading = true))

    /**
     * "Find more foods online for these gaps" (feedback 2026-09-27): ONE
     * gap-targeted LLM discovery pass; findings resolve through the standard
     * pipeline and land in the permanent knowledge base, from where the
     * refresh tick rebuilds the recommendation list immediately.
     */
    fun researchMoreFoods() {
        if (_researching.value) return
        viewModelScope.launch {
            _researching.value = true
            try {
                val focus = state.value.focusNow
                val known = state.value.allSmartPicks.mapTo(HashSet()) { pick ->
                    com.example.hoot.domain.nutrition.FoodNormalizer.normalize(pick.displayName)
                }
                runCatching {
                    graph.gapResearcher.researchForGaps(focus, known)
                }.onFailure {
                    android.util.Log.e("HootSmartPicks", "gap research failed", it)
                }
            } finally {
                _researching.value = false
                _refreshTick.value = _refreshTick.value + 1
            }
        }
    }

    /** Meals + supplements logged on the selected day (reactive). */
    val meals: StateFlow<List<MealEntity>> = _day
        .flatMapLatest { graph.meals.observeByDay(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val supplements: StateFlow<List<SupplementEntity>> = _day
        .flatMapLatest { graph.meals.observeSupplementsByDay(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Tail water/misc habit entries logged today (v5) — minimal display rows
     * in Home's "today" list; no analytics yet.
     */
    val tailEntries: StateFlow<List<TailEntryEntity>> = _day
        .flatMapLatest { graph.tailEntries.observeByDay(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Unresolved items awaiting nutrition resolution (retry chip). */
    val unresolved: StateFlow<Int> = _unresolved.asStateFlow()

    init {
        viewModelScope.launch {
            graph.nutrients.observeDailyTotalsRange(dayKeyMinusDays(today, 6), today)
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
        val perDay = graph.nutrients.observeDailyTotalsRange(dayKeyMinusDays(today, 6), today).first()
        val intakeByDay = perDay.associate { (it.nutrientId to (it.day ?: "")) to it.total }
        val snapshots = graph.nutrients.observeScoreHistory(dayKeyMinusDays(today, 13)).first()
        val current = snapshots.filter { it.day >= dayKeyMinusDays(today, 6) }
        val previous = snapshots.filter { it.day < dayKeyMinusDays(today, 6) }
        val dietFilter = _dietFilter
        val window = WindowData(
            from = dayKeyMinusDays(today, 6), to = today,
            intakeByDay = intakeByDay,
            definitions = defs.associate {
                it.id to NutrientInsightDef(
                    id = it.id, name = it.name, unit = it.unit, tier = it.tier,
                    rda = it.rdaValue, ul = it.ulValue, foodSources = it.foodSources,
                    deficiencySymptoms = it.deficiencySymptoms,
                    excessRisks = it.excessRisks,
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

    /**
     * Deficiency assessments over 7/30/90/365-day windows → ranked symptom
     * list (deficiency system, 2026-11-16). Pure read path; never throws
     * past the caller's runCatching.
     */
    private suspend fun computeSymptoms(
        day: String,
        comps: List<ScoreComponent>,
        weekTotals: Map<String, List<NutrientDayTotal>>
    ): List<SymptomMatcher.RankedSymptom> {
        val associations = graph.symptomAssociations.all()
        if (associations.isEmpty()) return emptyList()
        val windows = intArrayOf(7, 30, 90, 365)
        // Startup-path fix (2026-11-16): the old loop issued ONE
        // dailyTotalsForWindow query PER NUTRIENT PER WINDOW (~37 nutrients ×
        // 3 extra windows ≈ 111 full-window GROUP BY scans), each re-reading
        // the same ledger. Right after launch the self-heals re-trigger this
        // repeatedly, so Home's spinner could spin for a very long time.
        // Pre-fetch each window ONCE and slice in memory — same output,
        // 3 queries total.
        val windowRows = HashMap<Int, Map<String, List<NutrientDayTotal>>>(windows.size)
        for (w in windows) {
            if (w == FocusNowEngine.RECENT_DAYS) continue // reuse weekTotals
            windowRows[w] = runCatching {
                graph.nutrients.dailyTotalsForWindow(dayKeyMinusDays(day, (w - 1).toLong()), day)
            }.getOrDefault(emptyList()).groupBy { it.nutrientId }
        }
        val inputs = ArrayList<DeficiencyModel.Input>(comps.size)
        for (comp in comps) {
            if (comp.target <= 0) continue
            val stats = windows.map { w ->
                val from = dayKeyMinusDays(day, (w - 1).toLong())
                val totals = if (w == FocusNowEngine.RECENT_DAYS) {
                    // Reuse the already-fetched 7-day rows.
                    weekTotals[comp.nutrientId].orEmpty().filter { (it.day ?: "") >= from }
                } else {
                    windowRows[w]?.get(comp.nutrientId).orEmpty()
                        .filter { (it.day ?: "") >= from }
                }
                val tracked = totals.size
                if (tracked == 0) return@map DeficiencyModel.WindowStat(w, 0, 0, 1.0)
                var low = 0
                var covSum = 0.0
                for (t in totals) {
                    val cov = if (comp.isLimitTracker) {
                        if (t.total <= comp.target) 1.0 else (comp.target / t.total)
                    } else (t.total / comp.target).coerceAtMost(1.0)
                    covSum += cov
                    if (cov < DeficiencyModel.LOW_RATIO) low++
                }
                DeficiencyModel.WindowStat(w, tracked, low, covSum / tracked)
            }
            inputs += DeficiencyModel.Input(
                nutrientId = comp.nutrientId,
                name = comp.name,
                tier = comp.tier,
                unit = comp.unit,
                target = comp.target,
                windows = stats,
                todayCoverage = comp.coverage
            )
        }
        val assessments = inputs.map { DeficiencyModel.assess(it) }
            .filter { it.likelihood >= DeficiencyModel.REPORT_THRESHOLD }
        return SymptomMatcher.rank(
            deficiencies = assessments,
            max = 25,
            associations = associations.map {
                SymptomMatcher.Association(
                    nutrientId = it.nutrientId, symptomKey = it.symptomKey,
                    displayName = it.displayName, kind = it.kind,
                    confidence = it.confidence
                )
            }
        )
    }

    /** `["a","b"]` → [a, b]; tolerant of null/blank/invalid JSON. */
    private fun jsonList(raw: String?): List<String> = runCatching {
        val arr = org.json.JSONArray(raw ?: "[]")
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }.getOrDefault(emptyList())

    private suspend fun _sparkValues(): List<Double> =
        graph.nutrients.observeScoreHistory(dayKeyMinusDays(today, 6)).first()
            .sortedBy { it.day }.map { it.score }

    // ---- Day navigation (Home header arrows + date picker) ----------------

    /** Step the dashboard back one calendar day. */
    fun goBackDay() {
        _day.value = dayKeyMinusDays(_day.value, 1)
    }

    /** Step forward one day — never beyond today (no future logging). */
    fun goForwardDay() {
        val next = dayKeyPlusDays(_day.value, 1)
        if (next <= today) _day.value = next
    }

    /** Jump to an arbitrary day (date picker); future days are ignored. */
    fun selectDay(key: String) {
        if (key.isNotBlank() && key <= today) _day.value = key
    }

    /** Return the dashboard to today. */
    fun jumpToToday() {
        _day.value = today
    }

    /** Retry affordance: resets failure counters and re-resolves + re-aggregates. */
    fun retryUnresolved() = graph.nutritionProcessor.retryAll()
}
