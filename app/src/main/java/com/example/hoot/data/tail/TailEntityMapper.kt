package com.example.hoot.data.tail

import com.example.hoot.data.local.entity.IngredientEntity
import com.example.hoot.data.local.entity.MealEntity
import com.example.hoot.data.local.entity.SupplementEntity
import com.example.hoot.data.local.entity.TailEntryEntity
import com.example.hoot.domain.nutrition.DayKeys
import com.example.hoot.domain.nutrition.IngredientParser
import com.example.hoot.domain.nutrition.SupplementCompositionParser
import com.example.hoot.domain.nutrition.SupplementLabelParser
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
 *
 * Composition credits (iodine-drops bug, 2026-11-17): [labels] carries the
 * pills habit's option descriptions — the per-serving nutrient section the
 * user maintains in Tail (§3 `option_descriptions`). When an item matches a
 * described option, its description is stored AND parsed by
 * [SupplementCompositionParser] into canonical-unit `nutrientContributions`
 * right at mapping time — zero LLM calls, zero resolver queue, works for the
 * whole backlog on the next full pull. Items without a described option keep
 * the legacy unresolved mapping (label/typed-dose parse → LLM as before).
 */
/**
 * Mapping result for the pills habit. [component1]/[component2] keep the
 * historical `(rows, maxTs)` destructuring; [overrideIds]/[pruneIds] carry
 * the legacy-split cleanup plan (see [supplementEntities]).
 */
data class SupplementMapResult(
    val rows: List<SupplementEntity>,
    val maxTs: Long?,
    /** Positional row ids whose fresh payload must REPLACE stored resolution (stack-collapse). */
    val overrideIds: Set<String> = emptySet(),
    /** Legacy `#index` sibling rows to DELETE (pre-fix split artifacts). */
    val pruneIds: List<String> = emptyList()
)

internal fun supplementEntities(
    entries: List<TailTextEntry>,
    habitName: String,
    labels: List<TailLabel> = emptyList(),
    unitsById: Map<String, String> = emptyMap()
): SupplementMapResult {
    // label text (normalized) → parsed contributions; only options whose
    // description yields at least one tracked nutrient enter the map.
    val compositionByLabel = if (labels.isEmpty()) emptyMap() else
        labels.mapNotNull { l ->
            SupplementCompositionParser.contributionsJson(l.description ?: "", unitsById)
                ?.let { normalizeOptionText(l.labelText) to it }
        }.toMap()
    val descriptionByLabel = labels
        .filter { it.description != null }
        .associate { normalizeOptionText(it.labelText) to it.description }
    val originalByText = labels.associate { normalizeOptionText(it.labelText) to it.labelText }
    val compositionEnabled = compositionByLabel.isNotEmpty()

    var maxTs: Long? = null
    val rows = mutableListOf<SupplementEntity>()
    val overrideIds = mutableSetOf<String>()
    val pruneIds = mutableListOf<String>()

    for (e in entries) {
        if (e.timestampMs <= 0) continue
        if (maxTs == null || e.timestampMs > maxTs) maxTs = e.timestampMs
        // Echo filter (protocol v6): Hoot's own supplement push reflects
        // back through Tail's text log — skipping prevents duplicate rows
        // (Hoot already stored each item at capture time).
        if (EchoRegistry.isKnownTextEcho(habitName, e.timestampMs)) continue
        val baseKey = entryKey("pills", e.entryId, habitName, e.timestampRaw)

        // Itemization (catalog-aware, 2026-11-17): Tail pill-log submissions
        // are '\n'-joined lines where ONE line can BE a catalog pill whose
        // name is itself a comma list ("Calcium, Magnesium, Zinc, D3, K2,
        // Copper, Boron"). Split NEWLINE-first and whole-line-match each
        // against the catalog; unmatched lines fall back to the legacy
        // [SupplementListSplitter] (free text). The plain legacy split of
        // the whole text is kept when no compositions are known, so
        // pre-fix row ids stay byte-stable in that mode.
        var hadWholeLineCredit = false
        val items: List<String> = if (!compositionEnabled) {
            SupplementListSplitter.split(e.text)
        } else {
            e.text.split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .flatMap { line ->
                    val key = normalizeOptionText(line)
                    if (key in compositionByLabel) {
                        hadWholeLineCredit = true
                        listOf(originalByText[key] ?: line)
                    } else {
                        SupplementListSplitter.split(line)
                    }
                }
        }
        if (items.isEmpty()) continue

        // Per-item credits are EXACT-only: containment matching would
        // over-credit (every split mineral of a stack line matching the
        // stack's own catalog entry). Items keep their typed-dose parse and
        // fall through to the resolver when there is no exact hit.
        val creditMap: Map<String, String> =
            items.mapNotNull { item ->
                val key = normalizeOptionText(item)
                compositionByLabel[key]?.let { key to it }
            }.toMap()

        items.forEachIndexed { index, item ->
            val key = normalizeOptionText(item)
            val composition = creditMap[key]
            val typedDose = SupplementLabelParser.parse(item)
            rows += SupplementEntity(
                id = if (index == 0) baseKey else "$baseKey#$index",
                label = item.take(80),
                description = descriptionByLabel[key],
                resolvedFoodId = null,
                doseAmount = typedDose?.doseAmount,
                doseUnit = typedDose?.doseUnit,
                nutrientContributions = composition ?: "[]",
                tailHabitName = habitName,
                timestamp = e.timestampMs,
                day = dayKey(e.timestampMs),
                rawText = item,
                source = "tail"
            )
            if (hadWholeLineCredit) overrideIds += rows.last().id
        }

        // Legacy split-artifact cleanup: the pre-fix mapping split the WHOLE
        // text (commas included), so a stack entry produced N positional
        // rows where the catalog-aware mapping produces fewer (and better)
        // ones. Positional ids the new mapping no longer occupies are
        // deleted; occupied ones are OVERRIDE rows (fresh payload replaces
        // whatever junk resolution the legacy fragments carry) — otherwise
        // the collapsed stack would double-credit against its own leftover
        // per-mineral rows.
        if (compositionEnabled) {
            val legacyCount = SupplementListSplitter.split(e.text).size
            for (i in items.size until legacyCount) pruneIds += "$baseKey#$i"
        }
    }
    return SupplementMapResult(rows, maxTs, overrideIds, pruneIds)
}

/** Lowercase + whitespace/punctuation collapse for tolerant option matching. */
internal fun normalizeOptionText(s: String): String =
    s.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()

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
