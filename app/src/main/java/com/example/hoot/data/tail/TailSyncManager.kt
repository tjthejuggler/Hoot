package com.example.hoot.data.tail

import com.example.hoot.data.local.entity.TailEntryEntity
import com.example.hoot.data.repository.TailEntryRepository
import com.example.hoot.data.repository.MealRepository
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
 * Also runs a coroutine-based periodic loop honoring the DataStore
 * `syncEnabled` / `syncIntervalMinutes` settings.
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
    private val scope: CoroutineScope
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
                val pillsHabit = cfg.pillsHabitName
                if (pillsHabit != null) {
                    val history = tailClient.fetchFullHistory(pkg, pillsHabit, cfg.lastPillsSyncAt)
                    val (rows, maxTs) = supplementEntities(history.entries, pillsHabit)
                    val counts = meals.ingestPreservingResolution(emptyList(), rows)
                    supplementsInserted = counts.supplements
                    syncedIds += rows.map { it.id }
                    tailConfig.updateSyncCursor(null, maxTs)
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
                    mealsUpdated = mealsUpdated
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

    /** Starts the coroutine periodic loop (no-op when already running). */
    fun startPeriodicSync() {
        if (periodicJob?.isActive == true) return
        periodicJob = scope.launch {
            while (true) {
                val s = runCatching { settings.current() }.getOrNull()
                val intervalMin = s?.syncIntervalMinutes ?: 60
                val enabled = s?.syncEnabled ?: true
                if (enabled) {
                    runCatching { sync(TailSyncTrigger.PERIODIC) }
                }
                delay(intervalMin * 60_000L)
            }
        }
    }

    fun stopPeriodicSync() {
        periodicJob?.cancel()
        periodicJob = null
    }

    companion object {
        private const val TAG = "TailSync"
    }
}
