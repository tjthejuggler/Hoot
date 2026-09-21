package com.example.hoot.di

import android.content.Context
import com.example.hoot.data.local.HootDatabase
import com.example.hoot.data.local.SettingsRepository
import com.example.hoot.data.remote.LlmClient
import com.example.hoot.data.repository.LookupRepository
import com.example.hoot.data.repository.MealRepository
import com.example.hoot.data.repository.NutrientRepository
import com.example.hoot.data.intake.IntakeCaptureService
import com.example.hoot.data.repository.TailConfigRepository
import com.example.hoot.data.repository.TailEntryRepository
import com.example.hoot.data.tail.TailClient
import com.example.hoot.data.tail.TailSyncManager
import com.example.hoot.data.tail.TailSyncState
import com.example.hoot.domain.insights.RecommendationEngine
import com.example.hoot.domain.insights.SmartFoodProvider
import com.example.hoot.domain.nutrition.IntakeAggregator
import com.example.hoot.domain.nutrition.NutritionProcessState
import com.example.hoot.domain.nutrition.NutritionProcessor
import com.example.hoot.domain.nutrition.NutritionResolver
import com.example.hoot.domain.score.ScoreSnapshotter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Hand-rolled DI graph — small app, no framework needed (mirrors Inuit). */
class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** DataStore-backed settings (LLM/MCP/Tail/diet/user-profile keys). */
    val settings = SettingsRepository(appContext)

    /** Room database — builds once per process; seeds nutrients on first create. */
    val database: HootDatabase = HootDatabase.build(appContext, appScope)

    // ---- Repositories (thin, over DAOs / DataStore) ----------------------
    val meals = MealRepository(
        database.mealDao(),
        database.ingredientDao(),
        database.supplementDao()
    )
    val nutrients = NutrientRepository(
        database.nutrientDao(),
        database.nutrientGoalDao(),
        database.intakeDao(),
        database.foodDao(),
        database.profileDao(),
        database.lookupCacheDao(),
        database.sourceDao(),
        database.supplementDao(),
        database.recommendationDao(),
        database.scoreSnapshotDao()
    )
    val lookups = LookupRepository(
        database.lookupCacheDao(),
        database.profileDao(),
        database.foodDao(),
        database.sourceDao(),
        database.ingredientDao()
    )
    val tailConfig = TailConfigRepository(
        database.tailConfigDao(),
        database.dietaryProfileDao()
    )

    /** Tail water/misc habit log entries (+ in-app water quick-adds since v6). */
    val tailEntries = TailEntryRepository(database.tailEntryDao())

    /**
     * Per-nutrient-per-day ledger recompute (idempotent per day). Declared
     * BEFORE [tailSync] — the sync manager recomputes water days right after
     * ingestion, and Kotlin property initializers run top-down.
     */
    val intakeAggregator = IntakeAggregator(
        nutrients = nutrients,
        meals = meals,
        tailEntries = tailEntries
    )

    // ---- Tail integration (phase 2) — after its repository dependencies --
    /** Soft-fail ContentProvider client for `com.example.tail.provider`. */
    val tailClient = TailClient(appContext)

    /**
     * Tail → Room sync pipeline (full backlog on first run, `?after=`
     * incremental afterwards, coroutine periodic loop).
     */
    val tailSync = TailSyncManager(
        tailClient = tailClient,
        tailConfig = tailConfig,
        settings = settings,
        meals = meals,
        tailEntries = tailEntries,
        aggregator = intakeAggregator,
        scope = appScope
    )

    /** OpenAI-compatible chat completions client (Inuit port). */
    val llmClient = LlmClient()

    /**
     * Intake capture pipeline (v6): text / voice / photo → LLM →
     * Tail-compatible [com.example.hoot.domain.intake.CapturedMeal] →
     * MealEntity + ingredients (+ supplement/water quick-modes).
     */
    val intakeCapture = IntakeCaptureService(
        meals = meals,
        tailEntries = tailEntries,
        settings = settings,
        tailConfig = tailConfig,
        llm = llmClient,
        aggregator = intakeAggregator
    )

    // ---- Nutrition resolution engine (phase 3) ---------------------------

    /**
     * cache → LLM → MCP-web resolution pipeline over ingredient/supplement
     * text; persists Food + Profile + LookupCache + Sources on success.
     */
    val nutritionResolver = NutritionResolver(
        nutrients = nutrients,
        meals = meals,
        settings = settings,
        llm = llmClient
    )

    /**
     * Daily score snapshots (phase 4): ScoreEngine pipeline over the intake
     * ledger → idempotent [com.example.hoot.data.local.entity.ScoreSnapshotEntity].
     */
    val scoreSnapshotter = ScoreSnapshotter(
        nutrients = nutrients,
        meals = meals,
        tailConfig = tailConfig
    )

    /**
     * Deficiency-driven food recommendations (phase 4): cache-first foods,
     * one batched LLM call for shortfalls; persisted in recommendation_log.
     */
    val recommendationEngine = RecommendationEngine(
        nutrients = nutrients,
        meals = meals,
        tailConfig = tailConfig,
        llm = llmClient,
        settings = settings
    )

    /**
     * Feature C — "Smart picks for you": multi-nutrient food matching over
     * the local cache (instant, deterministic) with an optional single LLM
     * enhancement batch when the cache is thin.
     */
    val smartFoodProvider = SmartFoodProvider(
        nutrients = nutrients,
        tailConfig = tailConfig,
        settings = settings,
        llm = llmClient
    )

    /**
     * Per-DISTINCT-FOOD background queue draining unresolved ingredients +
     * supplements (grouped by normalized foodKey, cache-first, batched LLM
     * calls, 3 parallel workers, sliding-minute rate guard); exposes
     * [NutritionProcessor.state] for the Today chip.
     */
    val nutritionProcessor = NutritionProcessor(
        resolver = nutritionResolver,
        aggregator = intakeAggregator,
        meals = meals,
        settings = settings,
        scope = appScope
    )

    init {
        // After each Tail sync that ingested rows, resolve what's pending.
        appScope.launch {
            tailSync.syncState.collect { state ->
                val ingested = state as? TailSyncState.Success ?: return@collect
                if (ingested.mealsInserted > 0 || ingested.supplementsInserted > 0) {
                    nutritionProcessor.kick()
                }
            }
        }
        // Phase 4: whenever the nutrition queue drains, refresh score
        // snapshots for all known days (idempotent) and re-issue open
        // recommendations for today's gaps.
        appScope.launch {
            var wasProcessing = false
            nutritionProcessor.state.collect { state ->
                val processing = state is NutritionProcessState.Processing
                if (wasProcessing && !processing) {
                    runCatching {
                        scoreSnapshotter.recomputeAll()
                        recommendForToday()
                    }.onFailure { android.util.Log.e("HootGraph", "post-drain refresh failed", it) }
                }
                wasProcessing = processing
            }
        }
        // Legacy-backlog self-heal: parse ingredient rows for Tail meals that
        // predate the ingredient-derivation fix (their sync cursors already
        // advanced past them), then kick() queues the new rows for resolution.
        // Cheap + idempotent: the orphan query returns nothing once repaired.
        appScope.launch {
            runCatching {
                nutritionProcessor.backfillMissingIngredients()
            }.onFailure { android.util.Log.e("HootGraph", "ingredient backfill failed", it) }
        }
        // Ledger self-heal (water + fiber/iodine backfill): recomputes every
        // known day once per install of this build so days whose sync cursors
        // already advanced past their water rows (and profiles carrying
        // legacy "fibre"/"iodide" keys) get correct ledger values without any
        // user action. Idempotent — the per-day wipe-and-rewrite converges.
        appScope.launch {
            runCatching {
                intakeAggregator.recomputeDays(null)
                scoreSnapshotter.recomputeAll()
            }.onFailure { android.util.Log.e("HootGraph", "ledger self-heal failed", it) }
        }
        // Drain anything left unresolved from previous runs at startup. With
        // resolution state persisted (and preserving re-ingest), this enqueues
        // ZERO items when everything is already resolved — no network work on
        // restart; only genuinely-pending rows (< attempt cap) are queued.
        nutritionProcessor.kick()
        // Diet guard (diet-fix hardening, 2026-09): on STARTUP and on EVERY
        // dietary-profile change (DataStore or Room mirror), purge persisted
        // `recommendation_log` rows whose food/reason text violates the
        // CURRENT profile (pre-fix rows, or LLM output that ignored the
        // constraints) and regenerate today's recommendations against it.
        // Without this, stale omnivore suggestions leak onto the Insights
        // carousel and the nutrient detail sheet forever.
        appScope.launch {
            combine(settings.settings, tailConfig.observeDietaryProfile()) { s, room ->
                com.example.hoot.domain.insights.DietTextFilter.merged(
                    datastoreStyle = s.dietStyle,
                    datastoreAllergies = s.dietAllergies,
                    datastoreDislikes = s.dietDislikes,
                    roomStyle = room?.dietStyle,
                    roomAllergies = jsonList(room?.allergiesJson),
                    roomDislikes = jsonList(room?.dislikesJson)
                )
            }.distinctUntilChanged().collect { filter ->
                runCatching {
                    nutrients.purgeDietViolatingRecommendations(filter.toProfile())
                    recommendForToday()
                }.onFailure { android.util.Log.e("HootGraph", "diet guard refresh failed", it) }
            }
        }
    }

    /** `["a","b"]` → [a, b]; tolerant of null/blank/invalid JSON. */
    private fun jsonList(raw: String?): List<String> = runCatching {
        val arr = org.json.JSONArray(raw ?: "[]")
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }.getOrDefault(emptyList())

    /**
     * Issues/refreshes today's recommendations — cache-first foods, plus one
     * batched LLM call only when the LLM is configured. Never throws;
     * callers log failures.
     */
    suspend fun recommendForToday() {
        runCatching {
            recommendationEngine.generateForDay(
                com.example.hoot.domain.nutrition.DayKeys.todayKey()
            )
        }
    }
}
