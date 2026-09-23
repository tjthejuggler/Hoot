package com.example.hoot.data.tail

import com.example.hoot.data.local.entity.IngredientEntity
import com.example.hoot.data.local.entity.MealEntity
import com.example.hoot.data.local.entity.SupplementEntity
import com.example.hoot.data.local.entity.TailEntryEntity
import com.example.hoot.domain.nutrition.DayKeys
import com.example.hoot.domain.nutrition.IngredientParser
import com.example.hoot.domain.nutrition.SupplementListSplitter
import com.example.hoot.domain.nutrition.parseWaterAmount

/**
 * Pure Tail → Room mapping layer (extracted from [TailSyncManager] so the
 * sync orchestrator stays orchestration-only). Every function here is pure
 * JVM code — no Android imports — and is covered by the three `Tail*MappingTest`
 * suites.
 */

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
 * v2 meal rows → [MealEntity]s + the new incremental cursor (max ts).
 * Echo filter (protocol v6): rows whose `entry_id` is a `hoot:…` id are
 * the Tail-side reflection of Hoot's own push — skipped, or Hoot would
 * double-count every meal it captured in-app. Cursor still advances past
 * them, so they are visited exactly once.
 */
internal fun mealEntities(entries: List<TailMealEntry>): Pair<List<MealEntity>, Long?> {
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
internal fun textMealEntities(
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
 * Water-habit entries → [TailEntryEntity] rows (kind = "water") plus the
 * incremental cursor (max ts). Invalid timestamps are skipped; dedup keys are
 * the shared stable convention, so full-backlog re-pulls upsert in place.
 *
 * TWO source shapes (counter-water bug fix, 2026-09):
 *  - TEXT rows ("250 ml") → [parseWaterAmount] as before;
 *  - COUNTER rows (the user's habit: value column, empty text) → the daily
 *    count IS the amount, stored unitless so the water-unit mode interprets
 *    it (Tail logs raw ml — "2500" = 2.5 L).
 * Counter rows also mutate in place during the day (count increments), so
 * their day key rides the ts: upserts rewrite today's row on every pass.
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
        val (amount, unit) = when {
            e.text.isNotBlank() -> parseWaterAmount(e.text) ?: (null to null)
            e.value != null && e.value > 0 -> e.value to null   // counter daily total
            else -> null to null
        }
        TailEntryEntity(
            id = entryKey("water", e.entryId, habitName, e.timestampRaw),
            kind = KIND_WATER,
            habitName = habitName,
            timestamp = e.timestampMs,
            day = dayKey(e.timestampMs),
            text = e.text.ifBlank { e.value?.let { "Water count: ${it.toInt()}" } ?: "" },
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
