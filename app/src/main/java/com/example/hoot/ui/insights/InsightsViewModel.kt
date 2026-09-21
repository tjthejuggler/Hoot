package com.example.hoot.ui.insights

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hoot.appGraph
import com.example.hoot.data.local.entity.RecommendationEntity
import com.example.hoot.data.local.entity.ScoreSnapshotEntity
import com.example.hoot.domain.insights.CoachNote
import com.example.hoot.domain.insights.Insight
import com.example.hoot.domain.insights.InsightsEngine
import com.example.hoot.domain.insights.NutrientInsightDef
import com.example.hoot.domain.insights.WindowData
import com.example.hoot.ui.common.LIMIT_TRACKER_IDS
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

/** Time windows for the Insights screen. */
enum class InsightsWindow(val label: String, val days: Int) {
    D7("7d", 7), D30("30d", 30), D90("90d", 90)
}

/** Weekly aggregate bar for the score trend chart. */
data class WeekBar(val label: String, val avgScore: Double, val days: Int)

data class InsightsUiState(
    val window: InsightsWindow = InsightsWindow.D30,
    val scoreSeries: List<ScoreSnapshotEntity> = emptyList(),
    val prevScoreAvg: Double? = null,
    val weekBars: List<WeekBar> = emptyList(),
    val radar: List<Pair<String, Double>> = emptyList(),
    val insights: List<Insight> = emptyList(),
    /** nutrientId → tier, for all-tier deficiency/excess display + filter chips. */
    val tierById: Map<String, Int> = emptyMap(),
    /** Active tier filter; null = all tiers. */
    val tierFilter: Int? = null,
    val recommendations: List<RecommendationEntity> = emptyList(),
    val adherencePct: Int? = null,
    val coachNote: CoachNote.Result? = null,
    val coachLoading: Boolean = false,
    val loading: Boolean = true,
    /**
     * Active dietary filter (diet-fix hardening, 2026-09) — exposed so the
     * composables can run a render-boundary sanitize over any food text
     * (defense in depth against stale/omnivore-era strings).
     */
    val dietFilter: com.example.hoot.domain.insights.DietTextFilter =
        com.example.hoot.domain.insights.DietTextFilter()
)

/**
 * Insights screen state (phase 4): score trend (weekly bars + daily line),
 * Tier-1 radar, deficiency/excess lists, the informational food-suggestion
 * carousel, adherence summary, and the LLM coach note (diet-aware, template
 * fallback when the LLM is unconfigured/unreachable).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InsightsViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app.appGraph
    private val today = todayKey()

    private val _window = MutableStateFlow(InsightsWindow.D30)
    val window: StateFlow<InsightsWindow> = _window.asStateFlow()

    private val _tierFilter = MutableStateFlow<Int?>(null)

    private val _coach = MutableStateFlow<CoachNote.Result?>(null)
    private val _coachLoading = MutableStateFlow(false)

    /** Open + recently-answered recommendations (reactive). */
    val recommendations: StateFlow<List<RecommendationEntity>> =
        graph.nutrients.observeRecentRecommendations(30)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Screen state. The dietary-profile flow is part of the combine so the
     * analysis RE-RUNS whenever the diet changes (diet-fix hardening,
     * 2026-09) — previously the profile was read once with a one-shot query
     * and omnivore-era text stuck on screen until an unrelated refresh.
     * The filter merges DataStore + Room with a safety-biased rule
     * ([com.example.hoot.domain.insights.DietTextFilter.merged]) so a failed
     * mirror write can never downgrade "vegan" back to omnivore.
     */
    val state: StateFlow<InsightsUiState> =
        combine(
            _window, _tierFilter,
            graph.nutrients.observeScoreHistory("1970-01-01"),
            recommendations,
            // Both diet stores merged into ONE flow (5-way combine stays in
            // the typed-overload limit): any diet change re-runs buildState.
            combine(
                graph.tailConfig.observeDietaryProfile(),
                graph.settings.settings
            ) { room, s -> room to s }
        ) { win, tier, history, recs, (roomDiet, settings) ->
            buildState(win, tier, history, recs, roomDiet, settings)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, InsightsUiState(loading = true))

    private suspend fun buildState(
        win: InsightsWindow,
        tierFilter: Int?,
        history: List<ScoreSnapshotEntity>,
        recs: List<RecommendationEntity>,
        roomDiet: com.example.hoot.data.local.entity.DietaryProfileEntity?,
        settings: com.example.hoot.data.local.AppSettings
    ): InsightsUiState {
        val from = dayKeyMinusDays(today, win.days - 1L)
        val prevFrom = dayKeyMinusDays(from, win.days.toLong())

        val current = history.filter { it.day >= from }
        val previous = history.filter { it.day >= prevFrom && it.day < from }

        // Weekly bars (ISO weeks by day-of-year/7 buckets — good enough visually).
        val weekBars = current.groupBy { snap ->
            runCatching {
                val d = java.time.LocalDate.parse(snap.day)
                "W${(d.dayOfYear - 1) / 7 + 1}"
            }.getOrElse { snap.day }
        }.map { (label, snaps) ->
            WeekBar(label, snaps.map { it.score }.average(), snaps.size)
        }.takeLast(12)

        // Radar: avg %RDA over the window. Tier-1 + Tier-2 on the chart
        // (labeled axes); Tier-3 omitted to keep it readable.
        val defs = graph.nutrients.definitionsAll()
        val goals = graph.nutrients.goalsAll().associateBy { it.nutrientId }
        val dietFilter = com.example.hoot.domain.insights.DietTextFilter.merged(
            datastoreStyle = settings.dietStyle,
            datastoreAllergies = settings.dietAllergies,
            datastoreDislikes = settings.dietDislikes,
            roomStyle = roomDiet?.dietStyle,
            roomAllergies = jsonList(roomDiet?.allergiesJson),
            roomDislikes = jsonList(roomDiet?.dislikesJson)
        )
        val perDay = graph.nutrients.dailyTotalsForWindow(from, today)
            .filter { (it.day ?: "") >= from }
        val radar = defs
            .filter { (goals[it.id]?.priority ?: it.tier) <= 2 }
            .sortedWith(compareBy({ (goals[it.id]?.priority ?: it.tier) }, { it.name }))
            .take(8)
            .mapNotNull { def ->
                val target = com.example.hoot.ui.common.effectiveTarget(
                    def, goals[def.id]?.targetValue
                )
                if (target <= 0) return@mapNotNull null
                val pcts = perDay.filter { it.nutrientId == def.id }
                    .map { ((it.total / target).coerceIn(0.0, 1.0)) }
                if (pcts.isEmpty()) def.name to 0.0
                else def.name to pcts.average()
            }
        val tierById = defs.associate { it.id to (goals[it.id]?.priority ?: it.tier) }

        // Rule-based insights over the window.
        val intakeByDay = perDay.associate { (it.nutrientId to (it.day ?: "")) to it.total }
        val rawInsights = runCatching {
            InsightsEngine.analyze(
                WindowData(
                    from = from, to = today, intakeByDay = intakeByDay,
                    definitions = defs.associate {
                        it.id to NutrientInsightDef(
                            id = it.id, name = it.name, unit = it.unit, tier = it.tier,
                            rda = it.rdaValue, ul = it.ulValue, foodSources = it.foodSources,
                            isLimitTracker = it.id in LIMIT_TRACKER_IDS
                        )
                    },
                    goals = goals,
                    scoreSnapshots = current,
                    previousScoreSnapshots = previous,
                    dietFilter = dietFilter
                )
            )
        }.getOrDefault(emptyList())
        // Render-boundary defense in depth (diet-fix hardening, 2026-09):
        // re-filter every message on the way OUT, so text produced by an
        // omnivore-era analysis (or any future unfiltered producer) cannot
        // reach the composables even if generation-time filtering regresses.
        val insights = rawInsights.map {
            it.copy(
                message = com.example.hoot.domain.insights.DietAwareSources
                    .sanitizeForDisplay(it.message, dietFilter.toProfile())
            )
        }

        // Adherence over the window (accepted / answered).
        val windowRecs = recs.filter { it.day >= from }
        val answered = windowRecs.count { it.accepted != null }
        val accepted = windowRecs.count { it.accepted == true }
        val adherence = if (answered > 0) (accepted * 100 / answered) else null

        val filtered = if (tierFilter == null) insights
        else insights.filter { it.nutrientId == null || tierById[it.nutrientId] == tierFilter }

        // Carousel rows come from the PERSISTED recommendation_log — rows
        // issued before a diet change (or by a constraint-ignoring LLM) must
        // not render (diet-fix hardening, 2026-09).
        val safeRecs = recs.filter {
            dietFilter.allows(it.foodName) && dietFilter.allows(it.reasonText)
        }
        return InsightsUiState(
            window = win,
            scoreSeries = current.sortedBy { it.day },
            prevScoreAvg = previous.map { it.score }.takeIf { it.isNotEmpty() }?.average(),
            weekBars = weekBars,
            radar = radar,
            insights = filtered,
            tierById = tierById,
            tierFilter = tierFilter,
            recommendations = safeRecs,
            adherencePct = adherence,
            coachNote = _coach.value,
            coachLoading = _coachLoading.value,
            loading = false,
            dietFilter = dietFilter
        )
    }

    fun setWindow(win: InsightsWindow) {
        _window.value = win
    }

    /** "All / T1 / T2 / T3" filter for the deficiency-excess lists. */
    fun setTierFilter(tier: Int?) {
        _tierFilter.value = tier
    }

    /**
     * Generates the coach note (1 LLM call; template fallback). The prompt and
     * fallback are diet-aware: dietary_profile style/allergies/dislikes are
     * injected so advice never conflicts with the user's restrictions.
     */
    fun refreshCoachNote() {
        viewModelScope.launch {
            _coachLoading.value = true
            val settings = graph.settings.current()
            val roomDiet = runCatching { graph.tailConfig.dietaryProfile() }.getOrNull()
            val dietFilter = com.example.hoot.domain.insights.DietTextFilter.merged(
                datastoreStyle = settings.dietStyle,
                datastoreAllergies = settings.dietAllergies,
                datastoreDislikes = settings.dietDislikes,
                roomStyle = roomDiet?.dietStyle,
                roomAllergies = jsonList(roomDiet?.allergiesJson),
                roomDislikes = jsonList(roomDiet?.dislikesJson)
            )
            val dietContext = CoachNote.dietContext(
                style = dietFilter.dietStyle,
                allergies = dietFilter.allergies,
                dislikes = dietFilter.dislikes
            )
            val result = CoachNote.generate(
                llm = graph.llmClient.takeIf { settings.llmConfigured },
                cfg = com.example.hoot.data.remote.LlmConfig(
                    settings.baseUrl, settings.apiKey, settings.model
                ),
                disableThinking = settings.disableThinking,
                insights = state.value.insights,
                avgScore = state.value.scoreSeries.map { it.score }.takeIf { it.isNotEmpty() }?.average(),
                adherencePct = state.value.adherencePct,
                dietContext = dietContext
            )
            _coach.value = result
            _coachLoading.value = false
        }
    }

    /** `["a","b"]` → [a, b]; tolerant of null/blank/invalid JSON. */
    private fun jsonList(raw: String?): List<String> = runCatching {
        val arr = org.json.JSONArray(raw ?: "[]")
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }.getOrDefault(emptyList())
}
