package com.example.hoot.domain.nutrition

import android.util.Log
import com.example.hoot.data.local.SettingsRepository
import com.example.hoot.data.local.entity.MealEntity
import com.example.hoot.data.local.entity.NutrientIntakeEntity
import com.example.hoot.data.repository.MealRepository
import com.example.hoot.data.repository.NutrientRepository
import com.example.hoot.data.repository.TailEntryRepository
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

/**
 * Aggregates resolved meal ingredients + supplement contributions into the
 * per-nutrient-per-day intake ledger (docs/NUTRIENTS.md §7):
 *
 *   intake(D, N) = Σ meals: structured macros (Tail v2 rows / v6 captures)
 *                    — FACT: exactly what Tail displays; ingredients never
 *                    override or add to kcal/protein/carbs/fat
 *                + Σ meals: gramsEstimate(I)/perAmount × profile.valuesJson[N]
 *                    for every nutrient Tail does NOT assert (enrichment:
 *                    fiber, micronutrients, …)
 *                + Σ supplements: nutrientContributions (canonical)
 *                + water: Σ tail_entries(kind="water") amounts (L, canonical)
 *
 * Idempotent per day: the day's ledger rows are wiped before recompute, so
 * "new resolved ingredient" and "manual refresh" both converge by re-running
 * the affected days. Days with unresolved ingredients are simply under-
 * covered until the processor resolves them (visible state, no silent zeros).
 */
class IntakeAggregator(
    private val nutrients: NutrientRepository,
    private val meals: MealRepository,
    private val tailEntries: TailEntryRepository,
    /** Read per recompute so a water-unit change applies on the next pass. */
    private val settings: SettingsRepository? = null
) {

    /**
     * Serializes recompute runs (race fix 2026-09): the clear→compute→log
     * sequence is NOT transactional, so two concurrent runs of the same day
     * interleaved ("A.clear, B.clear, A.log, B.log") and duplicated the
     * UUID-keyed meal rows — day totals double-counted. The water full-pull
     * recomputes all known days concurrently with the post-drain refresh,
     * making the race likely; a mutex makes every run read the ledger only
     * after the previous one finished writing.
     */
    private val recomputeMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Recomputes the ledger for [days] (all known days when null).
     * Returns the number of ledger rows written.
     */
    suspend fun recomputeDays(days: Collection<String>? = null): Int = recomputeMutex.withLock {
        val unitsById = nutrients.definitionsAll().associate { it.id to it.unit }
        val dayKeys = (days ?: affectedDays()).sorted()
        var written = 0
        for (day in dayKeys) {
            nutrients.clearIntakeForDay(day)
            val rows = ArrayList<NutrientIntakeEntity>()

            // ── Meals: ingredient grams × per-100 g profile ─────────────
            for (meal in meals.mealsByDay(day)) {
                // TAIL IS FACT (feedback 2026-11-17): when the meal row
                // carries structured macros (Tail v2 rows / v6 captures),
                // those kcal/protein/carbs/fat ARE the ground truth Tail
                // displays — Hoot's ingredient estimates must NOT override
                // or add to them. Ingredients still enrich every OTHER
                // nutrient (fiber, iron, micronutrients, …) Tail doesn't
                // carry. Meals WITHOUT structured macros (v1 text fallback,
                // manual entry) keep ingredient-driven aggregation.
                val authoritativeMacros = structuredMacroNutrientIds(meal)
                for (ing in meals.ingredientsForMeal(meal.id)) {
                    val profile = ing.foodId?.let { nutrients.profileForFood(it) } ?: continue
                    // gramsEstimate is written by the resolver at resolution
                    // time; when a legacy row is missing it, fall back to the
                    // food's own serving hint / portion default so count-unit
                    // ingredients no longer silently contribute 0 (bug: Today
                    // showed 0 for everything except iron). The serving hint is
                    // ALSO the portion default: Tail-synced ingredient rows
                    // often carry no amount/unit at all, and passing null here
                    // dropped the entire ingredient — silently zeroing every
                    // nutrient only that ingredient supplied (added sugars,
                    // iodine, chloride… "no data" in week/month windows,
                    // bug 2026-09-29).
                    val grams = ing.gramsEstimate ?: ing.foodId
                        ?.let { nutrients.food(it)?.typicalServingGrams }
                        ?.let { IngredientParser.gramsEstimate(ing.amount, ing.unit, it, it) }
                        ?: continue
                    val values = runCatching { JSONObject(profile.valuesJson) }.getOrNull() ?: continue
                    val scale = grams / profile.perAmount
                    for (rawKey in values.keys()) {
                        val amount = values.optDouble(rawKey, Double.NaN)
                        if (amount.isNaN() || amount <= 0) continue
                        // Alias fold (fiber/iodine bug): legacy profiles may
                        // carry LLM key variants ("fibre", "dietary_fiber",
                        // "iodide") — canonicalize BEFORE the units lookup so
                        // those values stop silently dropping to 0.
                        val nutrientId = NutrientKeys.canonicalId(rawKey) ?: rawKey
                        val canonical = Units.canonicalNutrientAmount(
                            nutrientId, amount * scale, null, unitsById[nutrientId] ?: continue
                        ) ?: continue
                        // Tail-authoritative macro? Skip the resolver's own
                        // estimate for it — Hoot fills in ONLY what Tail
                        // doesn't explicitly give.
                        if (nutrientId in authoritativeMacros) continue
                        rows += NutrientIntakeEntity(
                            id = UUID.randomUUID().toString(),
                            nutrientId = nutrientId,
                            day = day,
                            amount = canonical,
                            sourceMealId = meal.id,
                            sourceSupplementId = null
                        )
                    }
                }

                // ── Structured macros (precedence FIX 2026-11-17): Tail v2
                // meal rows + v6 captures carry the authoritative
                // kcal/protein/carbs/fat — the exact numbers Tail itself
                // displays. Previously these were credited ONLY as
                // gap-filler beneath Hoot's own ingredient estimates, so any
                // day where the resolver produced different macros disagreed
                // with Tail (feedback: "calories for today are not the same
                // in Hoot as in Tail"). Now structured macros are credited
                // unconditionally and the ingredient loop above SKIPS
                // exactly those nutrients — Tail is fact; Hoot only enriches
                // what Tail doesn't explicitly give.
                rows += structuredMacroRows(meal, day, unitsById)
            }

            // ── Supplements: contributions already per-serving canonical ───
            // ALL ENERGY MACROS ARE SKIPPED (user feedback 2026-09-23, widened
            // 2026-11-17): LLM supplement panels report macro content of the
            // PILL itself ("Glutamine 10 g" → 10 g protein + 40 kcal,
            // collagen → 11 g protein), which previously summed into the same
            // figures as FOOD — a day with NOTHING eaten yet displayed
            // "11 g protein" the moment the user logged their morning pills.
            // The energy/macros figures reflect FOOD; pills contribute
            // micronutrients only (their macro content is derivative filler,
            // not a food the user ate). [supplementLedgerRows] pins this.
            for (supp in meals.supplementsByDay(day)) {
                val values = runCatching { JSONObject(supp.nutrientContributions) }.getOrNull() ?: continue
                for (rawKey in values.keys()) {
                    val nutrientId = NutrientKeys.canonicalId(rawKey) ?: rawKey
                    if (nutrientId in SUPPLEMENT_MACRO_SKIP) continue   // see block comment
                    val amount = values.optDouble(rawKey, Double.NaN)
                    if (amount.isNaN() || amount <= 0) continue
                    val canonical = Units.canonicalNutrientAmount(
                        nutrientId, amount, null, unitsById[nutrientId] ?: continue
                    ) ?: continue
                    rows += NutrientIntakeEntity(
                        id = UUID.randomUUID().toString(),
                        nutrientId = nutrientId,
                        day = day,
                        amount = canonical,
                        sourceMealId = null,
                        sourceSupplementId = supp.id
                    )
                }
            }

            // ── Water: tail_entries (Tail water habit + in-app quick-adds) ──
            // BUG: Home showed "0 L" and a 0 % water 7-day average although
            // the user logs water daily — this third source never reached the
            // ledger (only meals + supplements were aggregated). Amounts carry
            // their TEXT unit ("ml"/"l"/"oz"/null); [WaterIntake.liters]
            // converts with the user's water-unit mode (feedback 2026-09:
            // Tail logs bare ml values — "2500" = 2.5 L — so the mode is read
            // per recompute and a setting change rewrites history correctly).
            val unitMode = settings?.runCatching { current().waterUnitMode }
                ?.getOrNull() ?: "auto"
            val waterLiters = WaterIntake.liters(
                tailEntries.byKindAndDay(WaterIntake.KIND_WATER, day),
                unitMode
            )
            if (waterLiters > 0) {
                rows += NutrientIntakeEntity(
                    id = "water:$day",
                    nutrientId = "water",
                    day = day,
                    amount = waterLiters,
                    sourceMealId = null,
                    sourceSupplementId = null
                )
            }

            // ── Manual overrides (feedback 2026-11-18): the user's per-item  ──
            // edits REPLACE the computed rows for that (item, nutrient). Applied
            // LAST so they survive every wipe-and-rebuild above; amount 0 fully
            // suppresses the nutrient for the item. See [applyOverrides].
            val finalRows = applyOverrides(
                rows,
                nutrients.overridesForDay(day),
                day
            )

            if (finalRows.isNotEmpty()) nutrients.logIntake(finalRows)
            written += finalRows.size
            Log.d(TAG, "recompute($day): ${finalRows.size} ledger rows (water=$waterLiters L)")
        }
        written
    }

    /** All days that could contribute: meal ∪ supplement ∪ water-entry days. */
    private suspend fun affectedDays(): Set<String> =
        meals.distinctMealDays().toSet() + meals.distinctSupplementDays().toSet() +
            tailEntries.distinctWaterDays().toSet()

    companion object {
        private const val TAG = "HootAggregator"

        /**
         * Energy-macro ids supplements must NEVER credit to the day ledger
         * (bug 2026-11-17: "11 g protein today" with zero food eaten — the
         * collagen panel's protein key). Pure JVM — pinned by
         * `IntakeAggregatorStructuredMacroTest`.
         */
        val SUPPLEMENT_MACRO_SKIP: Set<String> =
            setOf("calories", "protein", "carbohydrates", "total_fat")

        /**
         * Manual-override application (feedback 2026-11-18), pure + JVM-testable:
         * each [NutrientOverrideEntity] for the day REPLACES every computed row
         * of its (source item, nutrient) pair — computed rows are dropped, and
         * an override with amount > 0 emits exactly one authoritative row
         * (deterministic `override:` id keeps re-runs idempotent). Amount 0
         * fully suppresses the nutrient for that item. An override may also
         * ADD a nutrient the resolver never produced for the item — the
         * user's assertion wins. Guards: overrides only apply to sources that
         * still HAVE computed rows on the day (a deleted meal/supplement must
         * not keep crediting ghost rows), and water rows (no source ids) are
         * never touched.
         */
        fun applyOverrides(
            computed: List<NutrientIntakeEntity>,
            overrides: List<com.example.hoot.data.local.entity.NutrientOverrideEntity>,
            day: String
        ): List<NutrientIntakeEntity> {
            if (overrides.isEmpty()) return computed
            data class Key(val sourceType: String, val sourceId: String, val nutrientId: String)

            fun keyOf(row: NutrientIntakeEntity): Key? = when {
                row.sourceMealId != null -> Key("meal", row.sourceMealId, row.nutrientId)
                row.sourceSupplementId != null ->
                    Key("supplement", row.sourceSupplementId, row.nutrientId)
                else -> null // water/manual day rows — never overridable
            }

            // Sources present in the day's computed rows (meal/supplement ids).
            // An override for an absent source is skipped: the item was
            // deleted, so its edit must not resurrect ghost contributions.
            val presentSources = HashSet<String>(computed.size)
            for (row in computed) {
                when {
                    row.sourceMealId != null -> presentSources += "meal:${row.sourceMealId}"
                    row.sourceSupplementId != null ->
                        presentSources += "supplement:${row.sourceSupplementId}"
                }
            }

            val active = overrides.associateBy { Key(it.sourceType, it.sourceId, it.nutrientId) }
            val out = ArrayList<NutrientIntakeEntity>(computed.size)
            for (row in computed) {
                val key = keyOf(row)
                if (key == null || key !in active) {
                    out += row // untouched (water) or not overridden
                }
                // else: replaced below (or suppressed)
            }
            for ((key, ov) in active) {
                if (ov.amount <= 0.0) continue // suppression — nothing to add
                if ("${ov.sourceType}:${ov.sourceId}" !in presentSources) continue
                out += NutrientIntakeEntity(
                    id = "override:${ov.id}",
                    nutrientId = key.nutrientId,
                    day = day,
                    amount = ov.amount,
                    sourceMealId = if (ov.sourceType == "meal") ov.sourceId else null,
                    sourceSupplementId = if (ov.sourceType == "supplement") ov.sourceId else null
                )
            }
            return out
        }

        /**
         * Nutrient ids the meal row asserts as structured facts (Tail v2 rows
         * / v6 captures). These kcal/protein/carbs/fat values are what Tail
         * itself displays — Hoot's ingredient estimates must NEVER override
         * or add to them (see recomputeDays). Pure, JVM-testable.
         */
        fun structuredMacroNutrientIds(meal: MealEntity): Set<String> = buildSet {
            if (meal.calories > 0) add("calories")
            if (meal.proteinGrams > 0) add("protein")
            if (meal.carbsGrams > 0) add("carbohydrates")
            if (meal.fatGrams > 0) add("total_fat")
        }

        /**
         * Structured-macro ledger rows for one meal (pure, JVM-testable):
         * Tail v2 meal rows and v6 in-app captures store authoritative
         * calories/protein/carbohydrates/total-fat directly on the meal row.
         * PRECEDENCE FIX (feedback 2026-11-17): structured macros are now
         * credited UNCONDITIONALLY (amount > 0) — they are Tail's facts —
         * while the ingredient loop in [recomputeDays] skips exactly these
         * nutrients so nothing double-counts. The former fill-missing
         * semantics (ingredient estimates win, macros fill gaps) produced
         * daily totals that disagreed with Tail whenever the resolver's
         * estimates differed. Each row keeps a deterministic id so re-runs
         * stay idempotent.
         */
        fun structuredMacroRows(
            meal: MealEntity,
            day: String,
            canonicalUnits: Map<String, String>
        ): List<NutrientIntakeEntity> {
            val structured = listOf(
                "calories" to meal.calories.toDouble(),
                "protein" to meal.proteinGrams,
                "carbohydrates" to meal.carbsGrams,
                "total_fat" to meal.fatGrams
            )
            val out = ArrayList<NutrientIntakeEntity>(structured.size)
            for ((nutrientId, amount) in structured) {
                if (amount <= 0.0) continue          // 0 = unknown (entity KDoc)
                val unit = canonicalUnits[nutrientId] ?: continue
                val canonical = Units.canonicalNutrientAmount(nutrientId, amount, null, unit) ?: continue
                out += NutrientIntakeEntity(
                    id = "macro:${meal.id}:$nutrientId",
                    nutrientId = nutrientId,
                    day = day,
                    amount = canonical,
                    sourceMealId = meal.id,
                    sourceSupplementId = null
                )
            }
            return out
        }
    }
}
