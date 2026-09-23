package com.example.hoot.di

import android.content.Context
import com.example.hoot.data.local.HootDatabase
import com.example.hoot.data.local.SettingsRepository
import com.example.hoot.data.remote.LlmClient
import com.example.hoot.data.repository.FoodRepository
import com.example.hoot.data.repository.IntakeRepository
import com.example.hoot.data.repository.LookupCacheRepository
import com.example.hoot.data.repository.LookupRepository
import com.example.hoot.data.repository.MealRepository
import com.example.hoot.data.repository.NutrientDefinitionRepository
import com.example.hoot.data.repository.NutrientRepository
import com.example.hoot.data.repository.RecommendationRepository
import com.example.hoot.data.repository.ScoreSnapshotRepository
import com.example.hoot.data.intake.IntakeCaptureService
import com.example.hoot.data.repository.SupplementRepository
import com.example.hoot.data.repository.TailConfigRepository
import com.example.hoot.data.repository.TailEntryRepository
import com.example.hoot.data.tail.EchoRegistry
import com.example.hoot.data.tail.TailClient
import com.example.hoot.data.tail.TailPushClient
import com.example.hoot.data.tail.TailSyncManager
import com.example.hoot.domain.insights.RecommendationEngine
import com.example.hoot.domain.insights.SmartFoodProvider
import com.example.hoot.domain.nutrition.IntakeAggregator
import com.example.hoot.domain.nutrition.NutritionProcessor
import com.example.hoot.domain.nutrition.NutritionResolver
import com.example.hoot.domain.score.ScoreSnapshotter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled DI graph — small app, no framework needed (mirrors Inuit).
 *
 * Wiring only: every `val` constructs one collaborator; cross-engine
 * reactions (sync→ingest, drain→refresh, self-heals, diet guard) live in
 * [NutritionPipeline] and are started at the bottom of the constructor.
 */
class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** DataStore-backed settings (LLM/MCP/Tail/diet/user-profile keys). */
    val settings = SettingsRepository(appContext)

    /** Room database — builds once per process; seeds nutrients on first create. */
    val database: HootDatabase = HootDatabase.build(appContext, appScope)

    init {
        // Echo-registry warm-up BEFORE any sync/pull can run: the filter set
        // is what keeps Hoot's own pushed rows from re-ingesting as echoes.
        EchoRegistry.init(appContext)
    }

    // ---- Repositories (thin, over DAOs / DataStore) ----------------------
    val meals = MealRepository(
        database.mealDao(),
        database.ingredientDao(),
        database.supplementDao()
    )

    // Concern-scoped nutrient-domain repositories (refactor 2026-09-22, P2).
    val nutrientDefinitions = NutrientDefinitionRepository(
        database.nutrientDao(),
        database.nutrientGoalDao()
    )
    val intakeRepo = IntakeRepository(database.intakeDao())
    val foods = FoodRepository(database.foodDao(), database.profileDao())
    val lookupCache = LookupCacheRepository(database.lookupCacheDao(), database.sourceDao())
    val supplements = SupplementRepository(database.supplementDao())
    val recommendations = RecommendationRepository(database.recommendationDao())
    val scoreSnapshots = ScoreSnapshotRepository(database.scoreSnapshotDao())

    /**
     * Deprecated catch-all facade over the seven repositories above. Kept
     * only while call sites migrate; new code should take the narrow repo.
     */
    @Deprecated("Use the concern-scoped repositories above")
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
        tailEntries = tailEntries,
        settings = settings
    )

    // ---- Tail integration (phase 2) — after its repository dependencies --
    /** Soft-fail ContentProvider client for `com.example.tail.provider`. */
    val tailClient = TailClient(appContext)

    /**
     * Joint-habit write path (protocol v6): pushes Hoot-side captures
     * (meals, supplements, water) INTO Tail so a change in either app
     * reflects in the other for that day. Registry init MUST precede the
     * first sync — the echo filter is what keeps pushed rows from coming
     * back as duplicate Tail data.
     */
    val tailPush = TailPushClient(appContext)

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
        aggregator = intakeAggregator,
        tailPush = tailPush
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
     * the local cache (instant, deterministic); a thin cache is topped up
     * from the bundled [com.example.hoot.domain.nutrition.SeedFoodLibrary]
     * LUT — zero LLM calls (LLM_AUDIT 2026-09).
     */
    val smartFoodProvider = SmartFoodProvider(
        nutrients = nutrients,
        tailConfig = tailConfig,
        settings = settings
    )

    /**
     * Per-DISTINCT-FOOD background queue draining unresolved ingredients +
     * supplements (grouped by normalized foodKey, cache-first, batched LLM
     * calls, 3 parallel workers, sliding-minute rate guard); exposes
     * [NutritionProcessor.state] for the processing chip.
     */
    val nutritionProcessor = NutritionProcessor(
        resolver = nutritionResolver,
        aggregator = intakeAggregator,
        meals = meals,
        settings = settings,
        scope = appScope
    )

    /**
     * Cross-engine reactions (sync→ingest, drain→refresh, startup self-heals,
     * diet guard) — constructed here and STARTED below (pipeline-start fix,
     * 2026-09-23): the refactoring that extracted [NutritionPipeline] dropped
     * its [NutritionPipeline.start] call, so the startup resolution drain,
     * the sync→kick reaction and every self-heal were dead code. Unresolved
     * ingredient/supplement rows then sat in the DB forever and the Today
     * chip read "N foods need analysis — tap to retry" on EVERY fresh launch
     * even though resolution state was supposed to persist.
     */
    private val nutritionPipeline = NutritionPipeline(
        scope = appScope,
        tailSync = tailSync,
        nutritionProcessor = nutritionProcessor,
        intakeAggregator = intakeAggregator,
        scoreSnapshotter = scoreSnapshotter,
        settings = settings,
        tailConfig = tailConfig,
        nutrients = nutrients,
        recommendationEngine = recommendationEngine
    )

    init {
        // Property initializers (including `nutritionPipeline`) have run by
        // the time this executes — safe to wire the collectors.
        nutritionPipeline.start()
    }

    /**
     * Issues/refreshes today's recommendations (delegates to the pipeline).
     * Called by [com.example.hoot.ui.settings.SettingsViewModel] after goal /
     * profile edits. Never throws.
     */
    suspend fun recommendForToday() = nutritionPipeline.recommendForToday()
}
