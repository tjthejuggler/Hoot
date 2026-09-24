package com.example.hoot.di

import com.example.hoot.data.local.SettingsRepository
import com.example.hoot.data.local.jsonList
import com.example.hoot.data.repository.NutrientRepository
import com.example.hoot.data.repository.TailConfigRepository
import com.example.hoot.data.tail.TailSyncManager
import com.example.hoot.data.tail.TailSyncState
import com.example.hoot.domain.insights.RecommendationEngine
import com.example.hoot.domain.nutrition.DayKeys
import com.example.hoot.domain.nutrition.IntakeAggregator
import com.example.hoot.domain.nutrition.NutritionProcessState
import com.example.hoot.domain.nutrition.NutritionProcessor
import com.example.hoot.domain.score.ScoreSnapshotter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Cross-engine reactions of the nutrition pipeline (extracted from AppGraph's
 * init so the DI graph stays wiring-only — refactor 2026-09-22, P6):
 *
 *  1. sync → ingest: a Tail sync that inserted rows kicks the resolver queue.
 *  2. drain → refresh: when the queue drains, recompute score snapshots for
 *     all known days and re-issue today's recommendations.
 *  3. Startup self-heals: ingredient backfill + full-ledger recompute.
 *  4. Diet guard: on every dietary-profile change, purge persisted
 *     recommendations that violate the CURRENT profile and regenerate.
 *
 * All reactions are idempotent and never throw past a log line.
 */
internal class NutritionPipeline(
    private val scope: CoroutineScope,
    private val tailSync: TailSyncManager,
    private val nutritionProcessor: NutritionProcessor,
    private val intakeAggregator: IntakeAggregator,
    private val scoreSnapshotter: ScoreSnapshotter,
    private val settings: SettingsRepository,
    private val tailConfig: TailConfigRepository,
    private val nutrients: NutrientRepository,
    private val recommendationEngine: RecommendationEngine
) {
    /** Launches every collector. Call once, after all dependencies are wired. */
    fun start() {
        // After each Tail sync that ingested rows, resolve what's pending.
        // Updated rows count too (hollow-meal refresh fix, 2026-09-23):
        // Tail rewrites placeholder meals in-place with the analyzed
        // payload — their replacement ingredient rows need the resolver.
        scope.launch {
            tailSync.syncState.collect { state ->
                val ingested = state as? TailSyncState.Success ?: return@collect
                if (ingested.mealsInserted > 0 || ingested.supplementsInserted > 0 ||
                    ingested.mealsUpdated > 0
                ) {
                    nutritionProcessor.kick()
                }
            }
        }
        // Phase 4: whenever the nutrition queue drains, refresh score
        // snapshots for all known days (idempotent) and re-issue open
        // recommendations for today's gaps.
        scope.launch {
            var wasProcessing = false
            nutritionProcessor.state.collect { state ->
                val processing = state is NutritionProcessState.Processing
                if (wasProcessing && !processing) {
                    runCatching {
                        scoreSnapshotter.recomputeAll()
                        recommendForToday()
                    }.onFailure { android.util.Log.e(TAG, "post-drain refresh failed", it) }
                }
                wasProcessing = processing
            }
        }
        // Legacy-backlog self-heal: parse ingredient rows for Tail meals that
        // predate the ingredient-derivation fix (their sync cursors already
        // advanced past them), then kick() queues the new rows for resolution.
        // Cheap + idempotent: the orphan query returns nothing once repaired.
        scope.launch {
            runCatching {
                nutritionProcessor.backfillMissingIngredients()
            }.onFailure { android.util.Log.e(TAG, "ingredient backfill failed", it) }
        }
        // Ledger self-heal (water + fiber/iodine backfill): recomputes every
        // known day once per install of this build so days whose sync cursors
        // already advanced past their water rows (and profiles carrying
        // legacy "fibre"/"iodide" keys) get correct ledger values without any
        // user action. Idempotent — the per-day wipe-and-rewrite converges.
        scope.launch {
            runCatching {
                intakeAggregator.recomputeDays(null)
                scoreSnapshotter.recomputeAll()
            }.onFailure { android.util.Log.e(TAG, "ledger self-heal failed", it) }
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
        scope.launch {
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
                    // Ledger hygiene (feedback 2026-09-23 round 2): rows
                    // persisted by older builds that fail the CURRENT quality
                    // gates ("Vegan Brunch Spread", "Plus Seaweed Sheets")
                    // are deleted once at startup / diet change; the engine
                    // re-issues specific replacements right after.
                    nutrients.purgeLowQualityRecommendations()
                    nutrients.purgeDietViolatingRecommendations(filter.toProfile())
                    recommendForToday()
                }.onFailure { android.util.Log.e(TAG, "diet guard refresh failed", it) }
            }
        }
    }

    /**
     * Issues/refreshes today's recommendations — cache-first foods, plus one
     * batched LLM call only when the LLM is configured. Never throws;
     * callers log failures.
     */
    suspend fun recommendForToday() {
        runCatching {
            recommendationEngine.generateForDay(DayKeys.todayKey())
        }
    }

    companion object {
        private const val TAG = "HootGraph"
    }
}
