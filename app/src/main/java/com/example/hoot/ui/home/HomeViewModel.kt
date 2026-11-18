package com.example.hoot.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hoot.appGraph
import com.example.hoot.data.local.entity.MealEntity
import com.example.hoot.data.local.entity.NutrientDefinitionEntity
import com.example.hoot.data.local.entity.NutrientOverrideEntity
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
import com.example.hoot.domain.nutrition.NutrientKeys
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
                    // Supplemental-only ULs (magnesium): the NIH cap applies to
                    // pills, not food — food intake crossing it is NOT an
                    // excess (mirrors ScoreSnapshotter; feedback 2026-11-18).
                    val exceeded = isLimit && intake > target ||
                        def.ulValue?.let {
                            !isLimit && def.id !in NutrientKeys.SUPPLEMENTAL_ONLY_UL && intake > it
                        } == true
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

                // ---- Focus now + symptoms: shared multi-window stats -----
                // (feedback 2026-11-17): Focus-now now lists EVERY nutrient
                // whose chronic (7/30/90/365-day) deficiency likelihood
                // clears the model threshold — NO count cap; the Home card
                // scrolls internally instead. One stats pass feeds both the
                // focus list and the likely-symptoms ranking below.
                val windowTotals = rangeRows.groupBy { it.nutrientId }
                val windowStats = buildWindowStats(day, comps, rangeRows)
                val focusInputs = comps.filter { !it.isLimitTracker }.map { comp ->
                    val stats = windowStats[comp.nutrientId].orEmpty()
                    val week = stats.firstOrNull { it.days == FocusNowEngine.RECENT_DAYS }
                    FocusNowInput(
                        nutrientId = comp.nutrientId,
                        name = comp.name,
                        tier = comp.tier,
                        unit = comp.unit,
                        target = comp.target,
                        // 7-day average over LOGGED days (a logged day with
                        // no row for this nutrient counts as 0) — consistent
                        // with the deficiency windows instead of the old
                        // average-over-existing-rows that inflated coverage.
                        recentAvgIntake = week?.avgCoverage?.times(comp.target) ?: 0.0,
                        todayIntake = comp.intake,
                        windows = stats
                    )
                }
                val focus = FocusNowEngine.select(focusInputs)

                val byId = comps.associateBy { it.nutrientId }

                // ---- Likely symptoms (deficiency system, 2026-11-16) ------
                // Multi-window coverage → DeficiencyModel assessments →
                // SymptomMatcher ranking over seed+research associations.
                // Shares the stats pass built for Focus-now above.
                val likelySymptoms = runCatching { computeSymptoms(comps, windowStats) }
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
            // DAY GUARD (bug 2026-11-17 "11 g protein with nothing eaten"):
            // a cache written YESTERDAY carries yesterday's totals — without
            // this guard they render as if they were today's until the live
            // pass swaps them in. Only a TODAY cache may surface instantly;
            // any older cache is skipped (plain loading state instead).
            HomeStateCache.load(getApplication())
                ?.takeIf { it.day == today }
                ?.let { emit(it) }
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
     * Multi-window coverage facts per nutrient (7/30/90/365 days, shared by
     * Focus-now and the likely-symptoms ranking since 2026-11-17).
     *
     * Tracked-day convention: a day counts as tracked for EVERY nutrient
     * when the user logged ANY intake that day; a logged day with no row
     * for a nutrient means that nutrient's intake was 0 (closes the old
     * "iodine inconsistency" where never-logged nutrients looked perfect
     * to the symptoms model). Days with no logging at all are excluded —
     * they prove nothing.
     *
     * Startup-path fix (2026-11-16, kept): ONE ledger query per window,
     * sliced in memory — never one query per nutrient per window.
     */
    private suspend fun buildWindowStats(
        day: String,
        comps: List<ScoreComponent>,
        weekRows: List<NutrientDayTotal>
    ): Map<String, List<DeficiencyModel.WindowStat>> {
        val windows = intArrayOf(7, 30, 90, 365)
        // One bucket per window: logged days + per-nutrient (day, total) rows.
        class Bucket(
            val loggedDays: List<String>,
            val perNutrient: Map<String, List<Pair<String, Double>>>
        )
        val byWindow = HashMap<Int, Bucket>(windows.size)
        for (w in windows) {
            val rows = if (w == FocusNowEngine.RECENT_DAYS) {
                weekRows // already the trailing-7d observation
            } else {
                runCatching {
                    graph.nutrients.dailyTotalsForWindow(
                        dayKeyMinusDays(day, (w - 1).toLong()), day
                    )
                }.getOrDefault(emptyList())
            }.filter { !it.day.isNullOrBlank() }
            // Days the user actually logged something (any nutrient).
            val loggedDays = rows.mapNotNull { it.day }.distinct()
            val perNutrient = rows.groupBy({ it.nutrientId }) {
                (it.day ?: "") to it.total
            }
            byWindow[w] = Bucket(loggedDays, perNutrient)
        }
        val out = HashMap<String, List<DeficiencyModel.WindowStat>>(comps.size)
        for (comp in comps) {
            if (comp.target <= 0) continue
            val stats = windows.map { w ->
                val bucket = byWindow[w] ?: return@map DeficiencyModel.WindowStat(w, 0, 0, 1.0)
                if (bucket.loggedDays.isEmpty()) {
                    return@map DeficiencyModel.WindowStat(w, 0, 0, 1.0)
                }
                val totalsByDay = bucket.perNutrient[comp.nutrientId].orEmpty().toMap()
                var low = 0
                var covSum = 0.0
                for (d in bucket.loggedDays) {
                    val total = totalsByDay[d] ?: 0.0
                    val cov = if (comp.isLimitTracker) {
                        if (total <= comp.target) 1.0 else (comp.target / total)
                    } else (total / comp.target).coerceAtMost(1.0)
                    covSum += cov
                    if (cov < DeficiencyModel.LOW_RATIO) low++
                }
                DeficiencyModel.WindowStat(w, bucket.loggedDays.size, low, covSum / bucket.loggedDays.size)
            }
            out[comp.nutrientId] = stats
        }
        return out
    }

    /**
     * Deficiency assessments over the shared multi-window stats → ranked
     * symptom list (deficiency system, 2026-11-16). Pure read path; never
     * throws past the caller's runCatching.
     */
    private suspend fun computeSymptoms(
        comps: List<ScoreComponent>,
        windowStats: Map<String, List<DeficiencyModel.WindowStat>>
    ): List<SymptomMatcher.RankedSymptom> {
        val associations = graph.symptomAssociations.all()
        if (associations.isEmpty()) return emptyList()
        val inputs = ArrayList<DeficiencyModel.Input>(comps.size)
        for (comp in comps) {
            if (comp.target <= 0) continue
            inputs += DeficiencyModel.Input(
                nutrientId = comp.nutrientId,
                name = comp.name,
                tier = comp.tier,
                unit = comp.unit,
                target = comp.target,
                windows = windowStats[comp.nutrientId].orEmpty(),
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

    // ---- Per-item nutrient breakdown (feedback 2026-11-18) ----------------
    //
    // "Logged today" rows are tappable → sheet listing every nutrient
    // attributed to that meal/supplement on the selected day, each editable.
    // Edits are stored as overrides (survive ledger recomputes) and applied
    // by re-running the day's aggregation.
    //
    // UX refinement (feedback 2026-11-18 #2): only nutrients the item
    // actually contributes are listed; anything else is added on demand via
    // a searchable "+" picker. Field boxes are PRE-FILLED with the current
    // credited amount so a value is edited in place — untouched fields are
    // not saved as overrides (only changed ones are).

    /** Identifies the item whose nutrient breakdown is open. */
    data class ItemRef(val sourceType: String, val sourceId: String) {
        val isMeal: Boolean get() = sourceType == NutrientOverrideEntity.TYPE_MEAL
    }

    /** One editable nutrient row in the item sheet. */
    data class ItemNutrientRow(
        val nutrientId: String,
        val name: String,
        val unit: String,
        val computed: Double,
        /** Live manual override amount, or null when the value is computed. */
        val overrideAmount: Double?
    ) {
        /** What the ledger currently credits (override wins). */
        val effective: Double get() = overrideAmount ?: computed
        val isOverridden: Boolean get() = overrideAmount != null
    }

    /** Picker option for the "+" add-nutrient dialog. */
    data class NutrientOption(val id: String, val name: String, val unit: String)

    /** State of the per-item nutrient sheet. */
    data class ItemNutrientState(
        val ref: ItemRef? = null,
        val title: String = "",
        val rows: List<ItemNutrientRow> = emptyList(),
        /** Every addable nutrient (all defs minus water), for the picker. */
        val options: List<NutrientOption> = emptyList(),
        val loading: Boolean = false,
        /** Transient copy of unsaved edits: nutrientId → field text. */
        val drafts: Map<String, String> = emptyMap(),
        val saving: Boolean = false
    )

    private val _itemNutrients = MutableStateFlow(ItemNutrientState())
    val itemNutrients: StateFlow<ItemNutrientState> = _itemNutrients.asStateFlow()

    /** Opens the breakdown sheet for one meal on the selected day. */
    fun openMealNutrients(meal: MealEntity) {
        openItemNutrients(
            ItemRef(NutrientOverrideEntity.TYPE_MEAL, meal.id),
            meal.title ?: meal.rawText
        )
    }

    /** Opens the breakdown sheet for one supplement on the selected day. */
    fun openSupplementNutrients(supplement: SupplementEntity) {
        openItemNutrients(
            ItemRef(NutrientOverrideEntity.TYPE_SUPPLEMENT, supplement.id),
            supplement.label
        )
    }

    private fun openItemNutrients(ref: ItemRef, title: String) {
        _itemNutrients.value = ItemNutrientState(ref = ref, title = title, loading = true)
        viewModelScope.launch {
            val day = _day.value
            val totalsRaw = if (ref.isMeal) {
                graph.nutrients.mealNutrientTotals(day, ref.sourceId)
            } else {
                graph.nutrients.supplementNutrientTotals(day, ref.sourceId)
            }
            val totals = totalsRaw.associate { it.nutrientId to it.total }

            val defs: Map<String, NutrientDefinitionEntity> =
                graph.nutrients.definitionsAll().associateBy { it.id }
            val overrides = graph.nutrients.observeOverridesForSource(ref.sourceId)
                .first().associateBy { it.nutrientId }

            // Only nutrients the item actually contributes (ledger row OR an
            // existing manual edit — a 0-override still shows so Reset works).
            // Everything else is reachable through the "+" picker.
            val rows = defs.values
                .filter { it.id != "water" }
                .filter { totals.containsKey(it.id) || overrides.containsKey(it.id) }
                .sortedBy { it.name.lowercase() }
                .map { def ->
                    ItemNutrientRow(
                        nutrientId = def.id,
                        name = def.name,
                        unit = def.unit,
                        computed = totals[def.id] ?: 0.0,
                        overrideAmount = overrides[def.id]?.amount
                    )
                }
            val options = defs.values
                .filter { it.id != "water" }
                .sortedBy { it.name.lowercase() }
                .map { NutrientOption(it.id, it.name, it.unit) }
            _itemNutrients.value = _itemNutrients.value.copy(
                rows = rows, options = options, loading = false, drafts = emptyMap()
            )
        }
    }

    /** "+" picker selection: appends the nutrient as a new editable row. */
    fun addItemNutrientRow(nutrientId: String) {
        val state = _itemNutrients.value
        if (state.rows.any { it.nutrientId == nutrientId }) return
        val opt = state.options.firstOrNull { it.id == nutrientId } ?: return
        // An existing override for a never-computed nutrient (suppressed to 0
        // or added then removed) still applies — surface it instead of 0.
        viewModelScope.launch {
            val existing = graph.nutrients.observeOverridesForSource(
                state.ref?.sourceId ?: return@launch
            ).first().firstOrNull { it.nutrientId == nutrientId }
            _itemNutrients.value = _itemNutrients.value.copy(
                rows = _itemNutrients.value.rows +
                    ItemNutrientRow(
                        nutrientId = opt.id,
                        name = opt.name,
                        unit = opt.unit,
                        computed = 0.0,
                        overrideAmount = existing?.amount
                    )
            )
        }
    }

    /** User typed into one nutrient field (unsaved draft). */
    fun editItemNutrientDraft(nutrientId: String, text: String) {
        _itemNutrients.value = _itemNutrients.value.copy(
            drafts = _itemNutrients.value.drafts + (nutrientId to text)
        )
    }

    /**
     * Persists CHANGED fields as overrides (amount 0 = suppress) and
     * re-aggregates the day so totals/score update immediately. Fields are
     * pre-filled with the current credited amount, so only values that
     * actually differ from it are written; blank/invalid fields are skipped.
     */
    fun saveItemNutrients() {
        val state = _itemNutrients.value
        val ref = state.ref ?: return
        // Diff against each row's current credited value (override ?? computed).
        val byId = state.rows.associateBy { it.nutrientId }
        val changed = state.drafts.mapNotNull { (nutrientId, text) ->
            if (text.isBlank()) return@mapNotNull null
            val amount = text.trim().replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
            if (amount < 0) return@mapNotNull null
            val current = byId[nutrientId]?.effective
            if (current != null && amount == current) return@mapNotNull null // untouched
            nutrientId to amount
        }.toMap()
        val drafts = changed
        if (drafts.isEmpty()) {
            _itemNutrients.value = state.copy(drafts = emptyMap())
            return
        }
        _itemNutrients.value = state.copy(saving = true)
        viewModelScope.launch {
            try {
                val day = _day.value
                val now = System.currentTimeMillis()
                for ((nutrientId, amount) in drafts) {
                    graph.nutrients.saveOverride(
                        NutrientOverrideEntity(
                            id = NutrientOverrideEntity.buildId(ref.sourceType, ref.sourceId, nutrientId),
                            sourceType = ref.sourceType,
                            sourceId = ref.sourceId,
                            nutrientId = nutrientId,
                            day = day,
                            amount = amount,
                            updatedAt = now
                        )
                    )
                }
                // Re-aggregate: overrides are applied at the end of recompute.
                runCatching { graph.intakeAggregator.recomputeDays(listOf(day)) }
                _itemNutrients.value = _itemNutrients.value.copy(saving = false, drafts = emptyMap())
                // Re-open from the fresh ledger so rows reflect the edit.
                openItemNutrients(ref, state.title)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("HootItemNutrients", "save failed", e)
                _itemNutrients.value = _itemNutrients.value.copy(saving = false)
            }
        }
    }

    /** Reverts one nutrient to the computed value (drops the override). */
    fun resetItemNutrient(nutrientId: String) {
        val ref = _itemNutrients.value.ref ?: return
        viewModelScope.launch {
            val id = NutrientOverrideEntity.buildId(ref.sourceType, ref.sourceId, nutrientId)
            graph.nutrients.deleteOverride(id)
            runCatching { graph.intakeAggregator.recomputeDays(listOf(_day.value)) }
            openItemNutrients(ref, _itemNutrients.value.title)
        }
    }

    /** Closes the sheet (drafts are discarded). */
    fun closeItemNutrients() {
        _itemNutrients.value = ItemNutrientState()
    }
}
