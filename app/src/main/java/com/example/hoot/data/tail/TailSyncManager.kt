package com.example.hoot.data.tail

import com.example.hoot.data.local.entity.TailEntryEntity
import com.example.hoot.data.repository.TailEntryRepository
import com.example.hoot.data.repository.MealRepository
import com.example.hoot.data.repository.NutrientDefinitionRepository
import com.example.hoot.data.repository.TailConfigRepository
import com.example.hoot.data.local.SettingsRepository
import com.example.hoot.domain.nutrition.IntakeAggregator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orchestrates Tail → Room ingestion:
 *
 *  1. Reads the singleton `tail_app_config` row (habit mapping + cursors).
 *  2. Meal habit: pulls `/v2/habits/{id}/entries` meal rows when Tail exposes
 *     them; otherwise gracefully falls back to text entries of the same habit
 *     (v1 surface) and flags [TailSyncState.Success.mealLogsUnavailable].
 *  3. Pills habit: text entries → [com.example.hoot.data.local.entity.SupplementEntity]
 *     rows, raw text preserved (dose parsing is phase 3).
 *  4. Dedup by stable entry id (`tail:<entryId>`) with a
 *     `tail:<kind>:<habit>:<tsKey>` fallback when Tail has not shipped ids
 *     (R5) — the compact `knownEntryIdsJson` window covers legacy collisions.
 *  5. Advances `lastMealSyncAt` / `lastPillsSyncAt` cursors (protocol v5's
 *     `?after=` incremental pattern) only after a pass with no hard failure.
 *
 * Sync cadence is FOREGROUND-ONLY and demand-driven (battery fix,
 * 2026-11-19): there is no background loop anymore. `syncEnabled` /
 * `syncIntervalMinutes` gate and space on-demand runs — [onForeground] syncs
 * when a broadcast nudge was deferred while backgrounded or when the last
 * pass is older than the configured interval — and the periodic loop below
 * only ticks while the app is VISIBLE, pausing (not restarting) whenever the
 * UI goes away. A backgrounded Hoot performs zero ContentProvider pulls and
 * zero network calls; broadcasts merely set a persisted pending flag
 * (see [TailSyncReceiver]) consumed by the next foreground session.
 *
 * Row mapping (Tail API responses → Room entities) lives in
 * [TailEntityMapper] — pure JVM, covered by the `Tail*MappingTest` suites.
 */
class TailSyncManager(
    private val tailClient: TailClient,
    private val tailConfig: TailConfigRepository,
    private val settings: SettingsRepository,
    private val meals: MealRepository,
    private val tailEntries: TailEntryRepository,
    private val aggregator: IntakeAggregator,
    private val scope: CoroutineScope,
    /** Canonical units for parsing Tail option descriptions at mapping time. */
    private val nutrientDefinitions: NutrientDefinitionRepository? = null
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<TailSyncState>(TailSyncState.Idle)
    val syncState: StateFlow<TailSyncState> = _state.asStateFlow()

    private var periodicJob: Job? = null

    /**
     * One sync pass. Concurrent callers coalesce: the second waits on the
     * mutex and returns the fresh state instead of double-fetching.
     */
    suspend fun sync(trigger: TailSyncTrigger = TailSyncTrigger.MANUAL): TailSyncState {
        maybeRunPillsResync()
        val config = tailConfig.tailConfig()
        if (config == null || !config.integrationEnabled ||
            (config.mealHabitName == null && config.pillsHabitName == null &&
                config.waterHabitName == null && config.miscHabitNames.isEmpty())
        ) {
            val idle = TailSyncState.Error("Tail not configured — run setup first")
            _state.value = idle
            return idle
        }
        val appSettings = settings.current()
        val pkg = appSettings.tailPackage.ifBlank { TailClient.CANONICAL_TAIL_PACKAGE }
        val firstRun = config.lastMealSyncAt == null && config.lastPillsSyncAt == null &&
            config.lastWaterSyncAt == null && config.lastMiscSyncAt == null
        _state.value = TailSyncState.Syncing(firstRun = firstRun)

        return mutex.withLock {
            // Re-read after acquiring: an earlier queued run may have advanced cursors.
            val cfg = tailConfig.tailConfig() ?: config
            try {
                var mealsInserted = 0
                var mealsUpdated = 0
                var supplementsInserted = 0
                var supplementsUpdated = 0
                var mealLogsUnavailable = false
                var waterInserted = 0
                var miscInserted = 0
                val syncedIds = mutableListOf<String>()

                // ── Meal habit ────────────────────────────────────────────
                // ALWAYS full-pull (hollow-meal refresh fix, 2026-09-23): Tail
                // creates meal rows as sparse placeholders ("Meal", 0 kcal,
                // no ingredients) and fills them IN-PLACE when its async
                // analysis lands — rewriting the creation timestamp to the
                // canonical (EARLIER) log instant. The old `?after=`
                // incremental pull (strictly-greater filter) therefore never
                // re-served the enriched row: Hoot kept the hollow row
                // forever, so "Consumed so far today" showed no meal / wrong
                // macros while Tail showed the analyzed meal (and hollow
                // texts fed junk entries into the resolution queue). Stable
                // entry-id dedup keys make the full pull idempotent; changed
                // payloads are detected per row by ingest.
                var mealDays: Set<String> = emptySet()
                val mealHabit = cfg.mealHabitName
                if (mealHabit != null) {
                    when (val result = tailClient.fetchMealLogs(pkg, mealHabit, null)) {
                        is MealLogsResult.Available -> {
                            val (rows, maxTs) = mealEntities(result.entries)
                            // Preserving ingest: re-served entry ids must not
                            // reset persisted resolution state (restart
                            // re-analysis bug). Ingredients parsed from the
                            // meal texts give the resolver/aggregator rows to
                            // work with (bug: Home showed 0 for meal macros).
                            val ings = mealIngredientEntities(result.entries)
                            val counts = meals.ingestPreservingResolution(rows, emptyList(), ings)
                            mealsInserted = counts.meals
                            mealsUpdated = counts.mealsUpdated
                            mealDays = counts.changedDays.toSet()
                            syncedIds += rows.map { it.id }
                            tailConfig.updateSyncCursor(maxTs, null)
                        }
                        MealLogsResult.Unavailable -> {
                            // v1 surface: ingest the meal habit's shared TEXT
                            // entries (if the user shares it) as raw-text meals.
                            mealLogsUnavailable = true
                            val history = tailClient.fetchFullHistory(pkg, mealHabit, null)
                            val (rows, maxTs) = textMealEntities(history.entries, mealHabit)
                            val ings = textMealIngredientEntities(history.entries, mealHabit)
                            val counts = meals.ingestPreservingResolution(rows, emptyList(), ings)
                            mealsInserted = counts.meals
                            mealsUpdated = counts.mealsUpdated
                            mealDays = counts.changedDays.toSet()
                            syncedIds += rows.map { it.id }
                            tailConfig.updateSyncCursor(maxTs, null)
                        }
                        is MealLogsResult.Error -> throw IllegalStateException(result.message)
                    }
                }

                // ── Pills habit ───────────────────────────────────────────
                // ALWAYS full-pull (iodine-fix, 2026-11-17): Tail option
                // descriptions — the per-serving nutrient section maintained
                // in Tail's supplement editor — are RETROACTIVE (an edit or
                // rename rewrites history) and the labels endpoint has no
                // change cursor, so a `?after=` incremental pull would never
                // re-serve rows whose composition credit just became
                // available. Stable entry-id dedup keys keep the full pull
                // idempotent; the ingest layer detects rows whose
                // contributions/description/dose CHANGED and reports their
                // days for the ledger recompute below (backlog refill).
                val pillsHabit = cfg.pillsHabitName
                var suppChangedDays: Set<String> = emptySet()
                if (pillsHabit != null) {
                    val labels = runCatching { tailClient.fetchHabitLabels(pkg, pillsHabit) }
                        .onFailure { android.util.Log.w(TAG, "pills labels fetch failed: ${it.message}") }
                        .getOrDefault(emptyList())
                    val unitsById = nutrientUnits()
                    val history = tailClient.fetchFullHistory(pkg, pillsHabit, null)
                    val (rows, maxTs, overrideIds, pruneIds) =
                        supplementEntities(history.entries, pillsHabit, labels, unitsById)
                    val counts = meals.ingestPreservingResolution(
                        emptyList(), rows, overrideIds = overrideIds, pruneIds = pruneIds
                    )
                    supplementsInserted = counts.supplements
                    supplementsUpdated = counts.supplementsUpdated
                    suppChangedDays = counts.changedDays.toSet()
                    syncedIds += rows.map { it.id }
                    tailConfig.updateSyncCursor(null, maxTs)
                    if (suppChangedDays.isNotEmpty()) {
                        runCatching { aggregator.recomputeDays(suppChangedDays.toList()) }
                            .onFailure { android.util.Log.e(TAG, "supplement ledger recompute failed", it) }
                    }
                }

                // ── Water habit (v5) ──────────────────────────────────────
                // BUG (water always 0 L): synced water rows landed in
                // `tail_entries` only — the intake ledger never saw them, so
                // Home's water stat + 7-day average read 0. After a pass that
                // ingested water rows, recompute those days' ledger so the
                // "water" nutrient row (canonical L) exists immediately.
                val waterHabit = cfg.waterHabitName
                if (waterHabit != null) {
                    // ALWAYS full-pull (counter-water bug fix, 2026-09): the
                    // cursor's `?after=` would (a) never re-serve the ~139
                    // rows ingested before the value-column fix (they sit in
                    // tail_entries with NULL amounts), and (b) miss same-day
                    // counter mutations — a counter row's entry_ts stays at
                    // midnight while its value grows, so strictly-greater
                    // incremental filters skip every increment. A full pull
                    // is ~140 rows, and stable dedup keys make the upserts
                    // idempotent (today's row simply rewrites with the new
                    // total). The cursor is still tracked for diagnostics.
                    val history = tailClient.fetchFullHistory(pkg, waterHabit, null)
                    val (rows, maxTs) = waterEntities(history.entries, waterHabit)
                    tailEntries.upsertAll(rows)
                    waterInserted = rows.size
                    syncedIds += rows.map { it.id }
                    tailConfig.updateSyncCursor(null, null, maxTs, null)
                    if (rows.isNotEmpty()) {
                        // Recompute EVERY known water day: repaired rows live
                        // anywhere in the backlog, not just this pass's days.
                        runCatching { aggregator.recomputeDays(tailEntries.distinctWaterDays()) }
                            .onFailure { android.util.Log.e(TAG, "water ledger recompute failed", it) }
                    }
                }

                // ── Miscellaneous habits (v5, N habits, raw-text entries) ─
                val miscHabits = cfg.miscHabitNames
                if (miscHabits.isNotEmpty()) {
                    val rows = mutableListOf<TailEntryEntity>()
                    var maxMiscTs: Long? = null
                    for (habit in miscHabits) {
                        val history = tailClient.fetchFullHistory(pkg, habit, cfg.lastMiscSyncAt)
                        val (habitRows, maxTs) = miscEntities(history.entries, habit)
                        rows += habitRows
                        if (maxTs != null && (maxMiscTs == null || maxTs > maxMiscTs!!)) {
                            maxMiscTs = maxTs
                        }
                    }
                    tailEntries.upsertAll(rows)
                    miscInserted = rows.size
                    syncedIds += rows.map { it.id }
                    tailConfig.updateSyncCursor(null, null, null, maxMiscTs)
                }

                // Changed meal payloads (hollow placeholders rewritten by
                // Tail's async analysis) alter rawText/macros → the affected
                // days' ledgers must be recomputed NOW, not on the next
                // generic refresh; new ingredient rows also need the
                // resolver queue drained.
                if (mealDays.isNotEmpty()) {
                    runCatching { aggregator.recomputeDays(mealDays.toList()) }
                        .onFailure { android.util.Log.e(TAG, "meal-change ledger recompute failed", it) }
                }
                val success = TailSyncState.Success(
                    mealsInserted = mealsInserted,
                    supplementsInserted = supplementsInserted,
                    full = firstRun,
                    mealLogsUnavailable = mealLogsUnavailable,
                    waterInserted = waterInserted,
                    miscInserted = miscInserted,
                    mealsUpdated = mealsUpdated,
                    supplementsUpdated = supplementsUpdated
                )
                _state.value = success
                if (syncedIds.isNotEmpty()) tailConfig.rememberEntryIds(syncedIds)
                success
            } catch (e: Exception) {
                val err = TailSyncState.Error(e.message ?: "Tail sync failed")
                _state.value = err
                err
            }
        }.also { state ->
            // Bookkeeping for the foreground-only cadence (battery fix,
            // 2026-11-19): every completed pass — manual, broadcast (while
            // foregrounded), periodic tick or foreground starter — refreshes
            // the last-run stamp and clears the deferred-nudge flag.
            runCatching {
                settings.markTailSyncRan()
                settings.markTailSyncPending(false)
            }
            android.util.Log.d(
                TAG, "sync($trigger): ${state::class.simpleName} " +
                    (state as? TailSyncState.Success)?.let {
                        "m=${it.mealsInserted} s=${it.supplementsInserted} " +
                            "w=${it.waterInserted} x=${it.miscInserted}"
                    } ?: ""
            )
        }
    }

    /**
     * One-time backfill for the multi-item pills fix: the pre-fix pipeline
     * stored only the FIRST item of each "Took Pills" entry. Clearing the
     * pills cursor exactly once forces a full-backlog re-pull; item 0 keeps
     * the legacy row id, so the truncated rows are overwritten in place (no
     * duplicates) while items 1..n arrive as new stable `#index` rows.
     */
    private suspend fun maybeRunPillsResync() {
        if (settings.pillsSplitResyncDone()) return
        // Flag flips only after a successful reset, so a DB hiccup retries
        // on the next sync instead of silently skipping the backfill.
        runCatching {
            tailConfig.resetPillsCursor()
            settings.markPillsSplitResyncDone()
        }
    }

    /** Canonical nutrient units (id → unit); empty when defs are unavailable. */
    private suspend fun nutrientUnits(): Map<String, String> =
        runCatching { nutrientDefinitions?.definitionsAll().orEmpty().associate { it.id to it.unit } }
            .getOrDefault(emptyMap())

    /**
     * Foreground session starter (battery fix, 2026-11-19). Called on every
     * 0→1 activity transition. Syncs only when there is actually something
     * to do:
     *  - a Tail broadcast was deferred while backgrounded (persisted pending
     *    flag), or
     *  - the last pass is older than the user's `syncIntervalMinutes`
     *    (catches data logged directly in Tail while Hoot was closed).
     * Otherwise returns without a single provider query or network call.
     */
    suspend fun onForeground() {
        val s = runCatching { settings.current() }.getOrNull() ?: return
        if (!s.syncEnabled) return
        val pending = runCatching { settings.tailSyncPending() }.getOrDefault(false)
        val lastRun = runCatching { settings.tailSyncLastRun() }.getOrDefault(0L)
        val stale = System.currentTimeMillis() - lastRun >= s.syncIntervalMinutes * 60_000L
        if (pending || stale) {
            runCatching { sync(TailSyncTrigger.STARTUP) }
                .onFailure { android.util.Log.w(TAG, "foreground sync failed: ${it.message}") }
        }
    }

    /**
     * Foreground-visible periodic loop. Same tick body as before — honors
     * `syncEnabled` / `syncIntervalMinutes` — but the loop EXITS when the app
     * leaves the foreground instead of ticking into a dead process: a
     * backgrounded Hoot held a live `while(true)` job (delay wakeups +
     * full-pull provider reads every hour, on top of the broadcast-triggered
     * cold starts) that never ended because nothing ever cancelled it.
     * [restartPeriodicSyncIfVisible] is invoked on each foreground entry to
     * relaunch it.
     */
    fun startPeriodicSync(visible: () -> Boolean) {
        if (periodicJob?.isActive == true) return
        periodicJob = scope.launch {
            // Delay-first: the foreground entry's own demand sync
            // ([onForeground]) already covered "just became visible", so the
            // loop's first tick waits a full interval — no double pull.
            while (visible()) {
                val s = runCatching { settings.current() }.getOrNull()
                val intervalMin = s?.syncIntervalMinutes ?: 60
                delay(intervalMin * 60_000L)
                if (!visible()) break
                if (s?.syncEnabled ?: true) {
                    runCatching { sync(TailSyncTrigger.PERIODIC) }
                }
            }
        }
    }

    /** Cancels the periodic loop if it is somehow still running. */
    fun stopPeriodicSync() {
        periodicJob?.cancel()
        periodicJob = null
    }

    companion object {
        private const val TAG = "TailSync"
    }
}
