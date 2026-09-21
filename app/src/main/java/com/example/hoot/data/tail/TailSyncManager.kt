package com.example.hoot.data.tail

import com.example.hoot.data.local.entity.IngredientEntity
import com.example.hoot.data.local.entity.MealEntity
import com.example.hoot.data.local.entity.SupplementEntity
import com.example.hoot.data.local.entity.TailEntryEntity
import com.example.hoot.data.repository.TailEntryRepository
import com.example.hoot.data.repository.MealRepository
import com.example.hoot.domain.nutrition.DayKeys
import com.example.hoot.domain.nutrition.IngredientParser
import com.example.hoot.domain.nutrition.SupplementListSplitter
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
import java.time.Instant
import java.time.ZoneId

/**
 * UI-visible progress of the Tail sync pipeline.
 */
sealed interface TailSyncState {
    data object Idle : TailSyncState

    /** [firstRun] = full-backlog pass (no persisted cursor yet). */
    data class Syncing(val firstRun: Boolean) : TailSyncState

    /** [mealsInserted]/[supplementsInserted] are rows written (upserts counted). */
    data class Success(
        val mealsInserted: Int,
        val supplementsInserted: Int,
        val full: Boolean,
        /** True when Tail does not expose meal logs yet (see docs/TAIL_REQUEST.md). */
        val mealLogsUnavailable: Boolean,
        /** v5: water/misc habit rows written (0 when those habits are unmapped). */
        val waterInserted: Int = 0,
        val miscInserted: Int = 0
    ) : TailSyncState

    data class Error(val message: String) : TailSyncState
}

/** What kicked off a sync run (affects logging and broadcast replies). */
enum class TailSyncTrigger { MANUAL, STARTUP, PERIODIC, BROADCAST }

/**
 * Orchestrates Tail → Room ingestion:
 *
 *  1. Reads the singleton `tail_app_config` row (habit mapping + cursors).
 *  2. Meal habit: pulls `/v2/habits/{id}/entries` meal rows when Tail exposes
 *     them; otherwise gracefully falls back to text entries of the same habit
 *     (v1 surface) and flags [TailSyncState.Success.mealLogsUnavailable].
 *  3. Pills habit: text entries → [SupplementEntity] rows, raw text preserved
 *     (dose parsing is phase 3).
 *  4. Dedup by stable entry id (`tail:<entryId>`) with a
 *     `tail:<kind>:<habit>:<tsKey>` fallback when Tail has not shipped ids
 *     (R5) — the compact `knownEntryIdsJson` window covers legacy collisions.
 *  5. Advances `lastMealSyncAt` / `lastPillsSyncAt` cursors (protocol v5's
 *     `?after=` incremental pattern) only after a pass with no hard failure.
 *
 * Also runs a coroutine-based periodic loop honoring the DataStore
 * `syncEnabled` / `syncIntervalMinutes` settings.
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
                var supplementsInserted = 0
                var mealLogsUnavailable = false
                var waterInserted = 0
                var miscInserted = 0
                val syncedIds = mutableListOf<String>()

                // ── Meal habit ────────────────────────────────────────────
                val mealHabit = cfg.mealHabitName
                if (mealHabit != null) {
                    when (val result = tailClient.fetchMealLogs(pkg, mealHabit, cfg.lastMealSyncAt)) {
                        is MealLogsResult.Available -> {
                            val (rows, maxTs) = mealEntities(result.entries)
                            // Preserving ingest: re-served entry ids must not
                            // reset persisted resolution state (restart
                            // re-analysis bug). Ingredients parsed from the
                            // meal texts give the resolver/aggregator rows to
                            // work with (bug: Today showed 0 for meal macros).
                            val ings = mealIngredientEntities(result.entries)
                            meals.ingestPreservingResolution(rows, emptyList(), ings)
                            mealsInserted = rows.size
                            syncedIds += rows.map { it.id }
                            tailConfig.updateSyncCursor(maxTs, null)
                        }
                        MealLogsResult.Unavailable -> {
                            // v1 surface: ingest the meal habit's shared TEXT
                            // entries (if the user shares it) as raw-text meals.
                            mealLogsUnavailable = true
                            val history = tailClient.fetchFullHistory(pkg, mealHabit, cfg.lastMealSyncAt)
                            val (rows, maxTs) = textMealEntities(history.entries, mealHabit)
                            val ings = textMealIngredientEntities(history.entries, mealHabit)
                            meals.ingestPreservingResolution(rows, emptyList(), ings)
                            mealsInserted = rows.size
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
                    meals.ingestPreservingResolution(emptyList(), rows)
                    supplementsInserted = rows.size
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
                    val history = tailClient.fetchFullHistory(pkg, waterHabit, cfg.lastWaterSyncAt)
                    val (rows, maxTs) = waterEntities(history.entries, waterHabit)
                    tailEntries.upsertAll(rows)
                    waterInserted = rows.size
                    syncedIds += rows.map { it.id }
                    tailConfig.updateSyncCursor(null, null, maxTs, null)
                    if (rows.isNotEmpty()) {
                        val days = rows.map { it.day }.distinct()
                        runCatching { aggregator.recomputeDays(days) }
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

                val success = TailSyncState.Success(
                    mealsInserted = mealsInserted,
                    supplementsInserted = supplementsInserted,
                    full = firstRun,
                    mealLogsUnavailable = mealLogsUnavailable,
                    waterInserted = waterInserted,
                    miscInserted = miscInserted
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

    // ── Mapping helpers (pure-ish, JVM-testable) ─────────────────────────

    /**
     * v2 meal rows → [MealEntity]s + the new incremental cursor (max ts).
     * Echo filter (protocol v6): rows whose `entry_id` is a `hoot:…` id are
     * the Tail-side reflection of Hoot's own push — skipped, or Hoot would
     * double-count every meal it captured in-app. Cursor still advances past
     * them, so they are visited exactly once.
     */
    private fun mealEntities(entries: List<TailMealEntry>): Pair<List<MealEntity>, Long?> {
        var maxTs: Long? = null
        val rows = entries.mapNotNull { e ->
            if (e.timestamp <= 0) return@mapNotNull null
            if (maxTs == null || e.timestamp > maxTs!!) maxTs = e.timestamp
            if (EchoRegistry.isEchoEntryId(e.entryId)) return@mapNotNull null
            MealEntity(
                id = entryKey("meal", e.entryId, e.habitName, e.timestamp.toString()),
                tailHabitName = e.habitName,
                timestamp = e.timestamp,
                day = dayKey(e.timestamp),
                title = e.title ?: e.summary?.take(80),
                rawText = buildMealRawText(e),
                summary = e.summary,
                calories = e.calories ?: 0,
                proteinGrams = e.proteinGrams ?: 0.0,
                carbsGrams = e.carbsGrams ?: 0.0,
                fatGrams = e.fatGrams ?: 0.0,
                source = "tail"
            )
        }
        return rows to maxTs
    }

    /**
     * v1 text fallback for the meal habit → raw-text [MealEntity]s + cursor.
     * Echo filter: (habit, second-ts) registry hits are Hoot's own pushes
     * reflected back — skipped, cursor still advances.
     */
    private fun textMealEntities(
        entries: List<TailTextEntry>,
        habitName: String
    ): Pair<List<MealEntity>, Long?> {
        var maxTs: Long? = null
        val rows = entries.mapNotNull { e ->
            if (e.timestampMs <= 0) return@mapNotNull null
            if (maxTs == null || e.timestampMs > maxTs!!) maxTs = e.timestampMs
            if (EchoRegistry.isKnownTextEcho(habitName, e.timestampMs)) return@mapNotNull null
            MealEntity(
                id = entryKey("meal", e.entryId, habitName, e.timestampRaw),
                tailHabitName = habitName,
                timestamp = e.timestampMs,
                day = dayKey(e.timestampMs),
                title = null,
                rawText = e.text,
                source = "tail"
            )
        }
        return rows to maxTs
    }

    // supplementEntities/entryKey/dayKey are top-level internal functions at
    // the bottom of this file (pure JVM, unit-testable without Android).

    companion object {
        private const val TAG = "TailSync"
    }
}

/** Meal entry → the raw text Hoot stores on the meal row (title + kcal + ingredients + summary). */
internal fun buildMealRawText(e: TailMealEntry): String = buildString {
    append(e.title ?: "")
    if (e.calories != null) append(" (").append(e.calories).append(" kcal)")
    val ing = e.ingredientsDetected
    if (ing.isNotEmpty()) {
        if (isNotEmpty()) append("\n")
        append(ing.joinToString("\n"))
    }
    if (!e.summary.isNullOrBlank() && e.summary != e.title) {
        if (isNotEmpty()) append("\n")
        append(e.summary)
    }
}.ifBlank { "(empty meal entry)" }

/**
 * Text entries of "Took Pills" → one [SupplementEntity] PER ITEM.
 *
 * A single Tail entry often carries a multi-item list ("iron\nvitamin D\nfish
 * oil"); [SupplementListSplitter] splits it dose-safely. Dedup stays stable
 * across re-syncs:
 *  - item 0 keeps the LEGACY entry key (`tail:<entryId>` or the
 *    `tail:pills:<habit>:<ts>` fallback) so pre-fix rows are overwritten in
 *    place — never duplicated;
 *  - items 1..n append `#<index>` (deterministic → idempotent upserts).
 * `label`/`rawText` hold the individual item; `timestamp`/`day`/habit
 * provenance are shared with the originating entry.
 */
internal fun supplementEntities(
    entries: List<TailTextEntry>,
    habitName: String
): Pair<List<SupplementEntity>, Long?> {
    var maxTs: Long? = null
    val rows = mutableListOf<SupplementEntity>()
    for (e in entries) {
        if (e.timestampMs <= 0) continue
        if (maxTs == null || e.timestampMs > maxTs) maxTs = e.timestampMs
        // Echo filter (protocol v6): Hoot's own supplement push reflects
        // back through Tail's text log — skipping prevents duplicate rows
        // (Hoot already stored each item at capture time).
        if (EchoRegistry.isKnownTextEcho(habitName, e.timestampMs)) continue
        val items = SupplementListSplitter.split(e.text)
        if (items.isEmpty()) continue
        val baseKey = entryKey("pills", e.entryId, habitName, e.timestampRaw)
        items.forEachIndexed { index, item ->
            rows += SupplementEntity(
                id = if (index == 0) baseKey else "$baseKey#$index",
                label = item.take(80),
                description = null,
                resolvedFoodId = null,
                doseAmount = null,
                doseUnit = null,
                nutrientContributions = "[]",
                tailHabitName = habitName,
                timestamp = e.timestampMs,
                day = dayKey(e.timestampMs),
                rawText = item,
                source = "tail"
            )
        }
    }
    return rows to maxTs
}

/** Stable dedup key: entry id when Tail ships one (R5), habit+timestamp fallback otherwise. */
internal fun entryKey(kind: String, entryId: String?, habitName: String, tsKey: String): String =
    if (!entryId.isNullOrBlank()) "tail:$entryId"
    else "tail:$kind:${habitName.lowercase()}:$tsKey"

/**
 * Water text → (amount, normalized unit) when the text contains a parseable
 * number: "500 ml" → (500, "ml"), "1.5 l" → (1500, "ml"), "2 glasses"
 * → (2, null). Returns null for no-number texts ("drank water").
 */
internal fun parseWaterAmount(text: String): Pair<Double, String?>? {
    val trimmed = text.trim()
    val m = Regex("""(\d+(?:[.,]\d+)?)\s*(ml|milliliters?|l|liters?|oz|fl\s*oz)?""", RegexOption.IGNORE_CASE)
        .find(trimmed) ?: return null
    val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    return when (m.groupValues[2].lowercase().replace("milliliter", "ml").replace("liter", "l")) {
        "l" -> value * 1000.0 to "ml"
        "" -> value to null
        else -> value to "ml"   // ml / oz pass through with the ml bucket label
    }
}

/**
 * Water-habit text entries → [TailEntryEntity] rows (kind = "water") plus the
 * incremental cursor (max ts). Invalid timestamps are skipped; dedup keys are
 * the shared stable convention, so full-backlog re-pulls upsert in place.
 */
internal fun waterEntities(
    entries: List<TailTextEntry>,
    habitName: String
): Pair<List<TailEntryEntity>, Long?> {
    var maxTs: Long? = null
    val rows = entries.mapNotNull { e ->
        if (e.timestampMs <= 0) return@mapNotNull null
        if (maxTs == null || e.timestampMs > maxTs) maxTs = e.timestampMs
        // Echo filter (protocol v6): skip Hoot's own water pushes reflected
        // back — the local tail_entries row already exists, a re-ingest
        // would double-count the day's water total.
        if (EchoRegistry.isKnownTextEcho(habitName, e.timestampMs)) return@mapNotNull null
        val (amount, unit) = parseWaterAmount(e.text) ?: (null to null)
        TailEntryEntity(
            id = entryKey("water", e.entryId, habitName, e.timestampRaw),
            kind = KIND_WATER,
            habitName = habitName,
            timestamp = e.timestampMs,
            day = dayKey(e.timestampMs),
            text = e.text,
            amount = amount,
            unit = unit
        )
    }
    return rows to maxTs
}

/**
 * Misc-habit text entries → raw-text [TailEntryEntity] rows (kind = "misc");
 * amount/unit stay null (resolution is a later phase). Same cursor/dedup rules.
 */
internal fun miscEntities(
    entries: List<TailTextEntry>,
    habitName: String
): Pair<List<TailEntryEntity>, Long?> {
    var maxTs: Long? = null
    val rows = entries.mapNotNull { e ->
        if (e.timestampMs <= 0) return@mapNotNull null
        if (maxTs == null || e.timestampMs > maxTs) maxTs = e.timestampMs
        // Echo filter (protocol v6): same rule as water/misc pushes.
        if (EchoRegistry.isKnownTextEcho(habitName, e.timestampMs)) return@mapNotNull null
        TailEntryEntity(
            id = entryKey("misc", e.entryId, habitName, e.timestampRaw),
            kind = KIND_MISC,
            habitName = habitName,
            timestamp = e.timestampMs,
            day = dayKey(e.timestampMs),
            text = e.text,
            amount = null,
            unit = null
        )
    }
    return rows to maxTs
}

/** [TailEntryEntity.kind] value for mapped water-habit entries. */
const val KIND_WATER = "water"

/** [TailEntryEntity.kind] value for miscellaneous-habit entries. */
const val KIND_MISC = "misc"

/**
 * Meal texts → [IngredientEntity] rows (bug fix: Today showed 0 for every
 * meal nutrient because Tail meals had no ingredient children — the
 * aggregator walks ingredient rows, never the meal's raw text).
 *
 * Deterministic ids (`<mealId>#<sanitized rawText hash>`) make re-ingestion
 * idempotent; `gramsEstimate`/`foodId` stay null until the resolver links a
 * food. `rawText` preserves the exact segment for the resolver's parser.
 */
internal fun ingredientEntitiesFor(mealId: String, rawText: String): List<IngredientEntity> =
    IngredientParser.parse(rawText).map { parsed ->
        IngredientEntity(
            id = "$mealId#${ingredientRowKey(parsed.rawText)}",
            mealId = mealId,
            rawText = parsed.rawText,
            foodId = null,               // pending resolution (resolver links food)
            amount = parsed.quantity,
            unit = parsed.unit,
            gramsEstimate = null
        )
    }

/** Stable short key for an ingredient segment (idempotent across re-syncs). */
internal fun ingredientRowKey(rawText: String): String =
    rawText.trim().lowercase().hashCode().toUInt().toString()

/**
 * v2 meal rows → their ingredient rows (invalid timestamps skipped, mirroring
 * mealEntities). Only the structured `ingredientsDetected` lines are parsed —
 * never the title/kcal (same rule as manual entry, where the title is stored
 * on the meal but excluded from ingredient parsing).
 */
internal fun mealIngredientEntities(entries: List<TailMealEntry>): List<IngredientEntity> =
    entries.filter { it.timestamp > 0 }.flatMap { e ->
        ingredientEntitiesFor(
            entryKey("meal", e.entryId, e.habitName, e.timestamp.toString()),
            e.ingredientsDetected.joinToString("\n")
        )
    }

/** v1 text-fallback meals → their ingredient rows. */
internal fun textMealIngredientEntities(
    entries: List<TailTextEntry>,
    habitName: String
): List<IngredientEntity> = entries.filter { it.timestampMs > 0 }.flatMap { e ->
    ingredientEntitiesFor(entryKey("meal", e.entryId, habitName, e.timestampRaw), e.text)
}

internal fun dayKey(ts: Long): String = DayKeys.fromEpoch(ts)
