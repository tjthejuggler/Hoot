package com.example.hoot.domain.nutrition

import android.util.Log
import com.example.hoot.data.repository.MealRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * UI-visible processor state: drains the pending-ingredient/supplement queue
 * PER DISTINCT FOOD (grouped by normalized foodKey) with [PARALLEL_WORKERS]
 * workers and batched LLM calls, exposing what's left for the Today status
 * chip ("Analyzing nutrition… n foods left" — n = distinct foods, not rows).
 */
sealed interface NutritionProcessState {
    data object Idle : NutritionProcessState
    data class Processing(
        val pending: Int,         // distinct unresolved foods (+supplement groups)
        val current: String,      // short label of the food being resolved
        val resolved: Int,        // distinct foods resolved this run
        val failed: Int           // distinct foods failed this run (stay retryable)
    ) : NutritionProcessState
}

/**
 * Background wiring for the resolution engine (per-FOOD overhaul):
 *
 *   1. GROUP unresolved ingredient rows by normalized foodKey (891 rows →
 *      ~74 distinct foods); supplements group by normalized name + dose.
 *   2. CACHE-FIRST: every distinct key is checked against the LookupCache once;
 *      a hit bulk-applies the cached profile to ALL rows of that key — zero
 *      LLM calls for the bulk of the backlog.
 *   3. BATCH LLM: cache misses are resolved with ONE multi-food call per
 *      [batchSize] foods (default 8); individually-unparseable foods fall
 *      back to a single-food call; foods failing both count as attempt-capped
 *      failures exactly like before (resolveAttempts semantics preserved).
 *   4. CONCURRENCY: 2-3 workers over distinct-food batches (semaphore),
 *      ONE re-aggregation of the affected days per drain — not per row.
 *   5. RATE GUARD: sliding-minute LLM call budget (default 20/min) pauses the
 *      loop until the window resets; the UI just keeps showing the count.
 *
 * Locking: the drain [Mutex] coalesces whole drain runs only — it is NEVER
 * taken a second time inside the running drain (kotlinx Mutex is not
 * reentrant; nesting deadlocks). Per-food progress counters use their own
 * internal monitor via [Progress].
 */
class NutritionProcessor(
    private val resolver: NutritionResolver,
    private val aggregator: IntakeAggregator,
    private val meals: MealRepository,
    private val settings: com.example.hoot.data.local.SettingsRepository,
    private val scope: CoroutineScope
) {
    private val drainMutex = Mutex()
    private var worker: Job? = null

    private val _state = kotlinx.coroutines.flow.MutableStateFlow<NutritionProcessState>(NutritionProcessState.Idle)
    val state: kotlinx.coroutines.flow.StateFlow<NutritionProcessState> = _state

    /** Shared drain-progress counters (internal monitor, deadlock-free). */
    private inner class Progress(val total: Int) {
        private val lock = Any()
        private var processed = 0
        private var resolved = 0
        private var failed = 0
        private val days = LinkedHashSet<String>()

        /** Records one finished group; publishes the chip state. */
        fun finish(ok: Boolean, label: String, groupDays: Set<String>) {
            synchronized(lock) {
                processed++
                if (ok) resolved++ else failed++
                days += groupDays
            }
            publish(label)
        }

        /** Publishes the chip without changing counters (current label only). */
        fun publish(label: String) {
            val (p, r, f) = synchronized(lock) { Triple(processed, resolved, failed) }
            _state.value = NutritionProcessState.Processing(
                pending = FoodGrouper.remainingCount(total, p),
                current = label.take(40), resolved = r, failed = f
            )
        }

        /** Distinct days touched by resolved groups this run. */
        fun snapshotDays(): Set<String> = synchronized(lock) { days.toSet() }

        /** (processed, resolved, failed) triple for the final log line. */
        fun snapshotCounts(): Triple<Int, Int, Int> = synchronized(lock) { Triple(processed, resolved, failed) }
    }

    /** Distinct unresolved foods + supplement name-groups (the chip number). */
    suspend fun pendingCount(): Int = withContext(Dispatchers.IO) {
        FoodGrouper.groupIngredients(unresolvedIngredientRows()).size +
            FoodGrouper.groupSupplements(unresolvedSupplementRows()).size
    }

    /** Fire-and-forget enqueue (UI/Tail-sync call sites). */
    fun kick() {
        if (worker?.isActive == true) {
            Log.d(TAG, "kick ignored — drain already running")
            return
        }
        worker = scope.launch {
            runCatching { drainPending() }
                .onFailure { Log.e(TAG, "drain crashed", it); _state.value = NutritionProcessState.Idle }
        }
    }

    /**
     * User-visible retry (Today chip): resets failure counters, re-queues
     * everything and re-aggregates all known days — same semantics as before,
     * now per-food.
     */
    fun retryAll() {
        scope.launch {
            runCatching {
                meals.resetResolveAttempts()
                drainPending()
                aggregator.recomputeDays(null)
            }.onFailure { Log.e(TAG, "retryAll failed", it) }
        }
    }

    /** Manual refresh: drain pending + re-aggregate every known day. */
    fun refreshAll() {
        scope.launch {
            runCatching {
                drainPending()
                aggregator.recomputeDays(null)
            }.onFailure { Log.e(TAG, "refreshAll failed", it) }
        }
    }

    /**
     * Startup self-heal for the legacy Tail backlog: meals ingested BEFORE the
     * ingredient-derivation fix have no `ingredients` rows. Parses each orphan
     * meal's raw text into rows (deterministic ids → idempotent), re-aggregates
     * the touched days and triggers one drain. No per-row queueing: the drain
     * re-groups from the DB.
     */
    suspend fun backfillMissingIngredients(): Int {
        val orphans = runCatching { meals.tailMealsWithoutIngredients() }.getOrDefault(emptyList())
        if (orphans.isEmpty()) return 0
        val days = LinkedHashSet<String>()
        var inserted = 0
        for (meal in orphans) {
            val ings = IngredientParser.parse(meal.rawText).map { parsed ->
                com.example.hoot.data.local.entity.IngredientEntity(
                    id = "${meal.id}#${parsed.rawText.trim().lowercase().hashCode().toUInt()}",
                    mealId = meal.id,
                    rawText = parsed.rawText,
                    foodId = null,
                    amount = parsed.quantity,
                    unit = parsed.unit,
                    gramsEstimate = null
                )
            }
            if (ings.isEmpty()) continue
            runCatching { meals.replaceIngredients(meal.id, ings) }
            inserted += ings.size
            days += meal.day
        }
        if (days.isNotEmpty()) {
            runCatching { aggregator.recomputeDays(days.toList()) }
            kick()
        }
        Log.i(TAG, "backfilled $inserted ingredient rows across ${days.size} legacy Tail meal day(s)")
        return days.size
    }

    // ── Row hydration ────────────────────────────────────────────────────

    private suspend fun unresolvedIngredientRows(): List<FoodGrouper.IngredientRow> {
        val rows = meals.unresolvedIngredients()
        // ONE batched meal lookup for the day map (never per-row: 869 rows
        // × 1 query each starves Room's executor and stalls the drain).
        val dayByMealId = meals.mealsByIds(rows.map { it.mealId }.distinct())
            .associate { it.id to it.day }
        return rows.map {
            FoodGrouper.IngredientRow(
                id = it.id,
                rawText = it.rawText,
                mealDay = dayByMealId[it.mealId]
            )
        }
    }

    private suspend fun unresolvedSupplementRows(): List<FoodGrouper.SupplementRow> =
        meals.unresolvedSupplements().map {
            FoodGrouper.SupplementRow(
                id = it.id, label = it.label, rawText = it.rawText,
                day = it.day.ifBlank { DayKeys.todayKey() }
            )
        }

    // ── The per-food drain ───────────────────────────────────────────────

    private suspend fun drainPending() {
        drainMutex.withLock {
            val ingredientGroups = FoodGrouper.groupIngredients(unresolvedIngredientRows())
            val supplementGroups = FoodGrouper.groupSupplements(unresolvedSupplementRows())
            val total = ingredientGroups.size + supplementGroups.size
            if (total == 0) {
                _state.value = NutritionProcessState.Idle
                return
            }
            Log.i(
                TAG,
                "drain start: ${ingredientGroups.sumOf { it.ingredientIds.size }} ingredient rows → " +
                    "${ingredientGroups.size} foods; " +
                    "${supplementGroups.sumOf { it.supplementIds.size }} supplement rows → " +
                    "${supplementGroups.size} groups"
            )
            val progress = Progress(total)
            _state.value = NutritionProcessState.Processing(
                pending = total, current = "", resolved = 0, failed = 0
            )

            val s = settings.current()
            val batchSize = s.llmBatchSize.coerceAtLeast(1)
            val rateGuard = LlmRateGuard(s.llmCallsPerMinute) { delayMs -> delay(delayMs) }

            // ── Phase 1: cache sweep over ALL distinct keys (0 LLM calls) ──
            val cached = resolver.cachedProfiles(
                ingredientGroups.map { it.foodKey } + supplementGroups.map { it.groupKey }
            )
            for (group in ingredientGroups) {
                val hit = cached[group.foodKey] ?: continue
                val applied = resolver.applyProfileToGroup(hit.first, hit.second, group.ingredientIds)
                progress.finish(applied > 0, group.displayName, group.touchedDays)
            }
            for (group in supplementGroups) {
                // Free direct-label parse first (no LLM, no rate slot).
                val outcome = resolver.resolveSupplementGroupSingle(
                    group.supplementIds, rateGuard = null, allowLlm = false
                )
                if (outcome is NutritionResolver.ResolveOutcome.Resolved) {
                    progress.finish(true, group.displayName, group.touchedDays)
                }
                // Not-yet-resolved groups fall through to the Phase-2 LLM
                // batches; nothing is stranded here anymore.
            }
            Log.i(TAG, "cache+label sweep done: ${cached.size} cache keys hit; 0 LLM calls so far")

            // ── Phase 2: re-hydrate what still needs an LLM panel ──────────
            // NO cache-key filter: a cache-hit group whose profile could not
            // be fully applied (key drift, partial bulk-apply, supplement
            // group keys colliding with cached FOOD names) must still reach
            // the LLM. The old `!in cached.keys` filter stranded those rows
            // with no worker and no attempt-cap, so they re-queued on every
            // fresh launch ("Analyzing nutrition… N foods left" chip with
            // nothing new consumed — feedback 2026-09). Fully-applied groups
            // drop out naturally: their rows are no longer unresolved.
            val remainingFoods = FoodGrouper.groupIngredients(unresolvedIngredientRows())
                .sortedByDescending { it.ingredientIds.size }   // biggest groups first
            val remainingSupps = FoodGrouper.groupSupplements(unresolvedSupplementRows())
            val foodBatches = FoodGrouper.batch(remainingFoods, batchSize)
            val suppBatches = FoodGrouper.batch(remainingSupps, batchSize)
            Log.i(
                TAG,
                "LLM phase: ${remainingFoods.size} foods in ${foodBatches.size} batched calls; " +
                    "${remainingSupps.size} supplement groups in ${suppBatches.size} batched calls"
            )

            // ── Phase 3: parallel workers over batches (semaphore) ─────────
            val semaphore = Semaphore(PARALLEL_WORKERS)
            coroutineScope {
                (foodBatches.map { Batches.Foods(it) } + suppBatches.map { Batches.Supps(it) })
                    .map { batch ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                when (batch) {
                                    is Batches.Foods -> runBatch { resolveFoodBatch(batch.groups, progress, rateGuard) }
                                    is Batches.Supps -> runBatch { resolveSuppBatch(batch.groups, progress, rateGuard) }
                                }
                            }
                        }
                    }.awaitAll()
            }

            // ── ONE re-aggregation per drain for every touched day ────────
            val touchedDays = progress.snapshotDays()
            if (touchedDays.isNotEmpty()) {
                runCatching { aggregator.recomputeDays(touchedDays.toList()) }
                    .onFailure { Log.e(TAG, "batch re-aggregation failed", it) }
            }
            _state.value = NutritionProcessState.Idle
            val snap = progress.snapshotCounts()
            Log.i(
                TAG,
                "drain done: distinct foods resolved=${snap.second} failed=${snap.third} " +
                    "of $total; days recomputed=${touchedDays.size}"
            )
        }
    }

    private sealed interface Batches {
        data class Foods(val groups: List<FoodGrouper.FoodGroup>) : Batches
        data class Supps(val groups: List<FoodGrouper.SupplementGroup>) : Batches
    }

    private inline fun runBatch(block: () -> Unit) {
        runCatching(block).onFailure { Log.e(TAG, "batch crashed (rows stay retryable)", it) }
    }

    /**
     * SEED-first batch resolution: bundled LUT panels persist with zero LLM
     * spend; the remainder goes out as ONE batched LLM call → persist each
     * parsed panel once → bulk-apply to every row of the key. Foods missing
     * from the reply (parse failure / dropped / seed-covered) get ONE
     * single-food fallback (seed check + LLM + confidence-gated web
     * escalation); failing both marks the group's rows failed (attempt-cap
     * bookkeeping, unchanged semantics).
     */
    private suspend fun resolveFoodBatch(
        groups: List<FoodGrouper.FoodGroup>,
        progress: Progress,
        rateGuard: LlmRateGuard
    ) {
        // (a2) bundled seed LUT — resolves whole common foods for free.
        val seedPanels = resolver.seedPanelsFor(groups.map { it.foodKey to it.displayName })
        val llmGroups = mutableListOf<FoodGrouper.FoodGroup>()
        for (group in groups) {
            val seedPanel = seedPanels[group.foodKey]
            if (seedPanel != null && resolver.persistFoodPanel(group.foodKey, group.displayName, seedPanel)) {
                val ok = applyFoodPanel(group)
                progress.finish(ok, group.displayName, group.touchedDays)
                if (!ok) llmGroups += group
            } else {
                llmGroups += group
            }
        }
        if (llmGroups.isEmpty()) return
        val panels = resolver.resolveFoodsBatch(
            llmGroups.map { it.foodKey to it.displayName }, rateGuard
        )
        val missing = mutableListOf<FoodGrouper.FoodGroup>()
        for (group in llmGroups) {
            val panel = panels[group.foodKey]
            val ok = panel != null && resolver.persistFoodPanel(group.foodKey, group.displayName, panel) &&
                applyFoodPanel(group)
            progress.finish(ok, group.displayName, group.touchedDays)
            if (!ok) missing += group
        }
        // Individual fallback: only the foods the batch failed (spec).
        for (group in missing) {
            val outcome = resolver.resolveSingleFood(group.foodKey, group.displayName, rateGuard)
            val ok = when (outcome) {
                is NutritionResolver.ResolveOutcome.Resolved -> applyFoodPanel(group)
                else -> false
            }
            if (!ok) resolver.markFoodGroupFailed(group)
            progress.finish(ok, group.displayName, group.touchedDays)
        }
    }

    /** Persists the already-resolved profile onto all rows of a group. */
    private suspend fun applyFoodPanel(group: FoodGrouper.FoodGroup): Boolean {
        val hit = resolver.cachedProfiles(listOf(group.foodKey))[group.foodKey] ?: return false
        return resolver.applyProfileToGroup(hit.first, hit.second, group.ingredientIds) > 0
    }

    /**
     * ONE batched supplement call (per-serving panels keyed by group) → apply
     * to every row of each parsed group; groups missing from the reply get the
     * single-group fallback (direct-label parse + one LLM panel).
     */
    private suspend fun resolveSuppBatch(
        groups: List<FoodGrouper.SupplementGroup>,
        progress: Progress,
        rateGuard: LlmRateGuard
    ) {
        val panels = resolver.resolveSupplementGroupsBatch(
            groups.map { it.groupKey to it.displayName }, rateGuard
        )
        val missing = mutableListOf<FoodGrouper.SupplementGroup>()
        for (group in groups) {
            val panel = panels[group.groupKey]
            val ok = panel != null &&
                resolver.applySupplementPanel(group.supplementIds, panel) > 0
            progress.finish(ok, group.displayName, group.touchedDays)
            if (!ok) missing += group
        }
        for (group in missing) {
            val outcome = resolver.resolveSupplementGroupSingle(group.supplementIds, rateGuard)
            val ok = outcome is NutritionResolver.ResolveOutcome.Resolved ||
                outcome is NutritionResolver.ResolveOutcome.Missing
            // Attempt-cap EVERY terminal failure — including NotConfigured
            // (LLM off). The old Failed-only guard left unresolvable groups
            // at resolveAttempts=0 forever, so they re-queued on EVERY app
            // open: the "Analyzing nutrition… N foods left" chip appeared on
            // each fresh launch even with nothing new consumed (feedback
            // 2026-09). Manual retry still resets the counters.
            if (!ok) resolver.markSupplementGroupFailed(group)
            progress.finish(ok, group.displayName, group.touchedDays)
        }
    }

    companion object {
        private const val TAG = "HootNutrition"

        /** Parallel resolver workers over distinct-food batches (spec: 2-3). */
        const val PARALLEL_WORKERS = 3
    }
}
