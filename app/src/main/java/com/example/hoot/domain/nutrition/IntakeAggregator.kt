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
 *   intake(D, N) = Σ meals: gramsEstimate(I)/perAmount × profile.valuesJson[N]
 *                + Σ meals' structured macros (Tail v2 rows / v6 captures)
 *                  for nutrients that meal's ingredients left uncovered
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
                // Canonical nutrient ids THIS meal's ingredient rows actually
                // produced (per-meal set — the structured-macro fill below
                // must never double-count a nutrient the resolver credited).
                val coveredByIngredients = HashSet<String>()
                for (ing in meals.ingredientsForMeal(meal.id)) {
                    val profile = ing.foodId?.let { nutrients.profileForFood(it) } ?: continue
                    // gramsEstimate is written by the resolver at resolution
                    // time; when a legacy row is missing it, fall back to the
                    // food's own serving hint / portion default so count-unit
                    // ingredients no longer silently contribute 0 (bug: Today
                    // showed 0 for everything except iron).
                    val grams = ing.gramsEstimate ?: ing.foodId
                        ?.let { nutrients.food(it)?.typicalServingGrams }
                        ?.let { IngredientParser.gramsEstimate(ing.amount, ing.unit, it, null) }
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
                        rows += NutrientIntakeEntity(
                            id = UUID.randomUUID().toString(),
                            nutrientId = nutrientId,
                            day = day,
                            amount = canonical,
                            sourceMealId = meal.id,
                            sourceSupplementId = null
                        )
                        coveredByIngredients += nutrientId
                    }
                }

                // ── Structured-macro fill (Tail-sync bug 2026-09-21): Tail v2
                // meal rows + v6 captures carry authoritative meal macros
                // (kcal/protein/carbs/fat) that this aggregator previously
                // IGNORED — a Tail meal whose text produced no resolvable
                // ingredients credited ZERO protein/carbs, so Home showed
                // "0 g carbs / 10 g protein" while Tail showed 105 g / 28 g.
                // Credit each structured macro ONLY when the meal's
                // ingredients produced nothing for that nutrient — no double
                // count when both paths have data.
                rows += structuredMacroRows(meal, day, coveredByIngredients, unitsById)
            }

            // ── Supplements: contributions already per-serving canonical ───
            // ENERGY MACROS ARE SKIPPED (user feedback 2026-09-23): LLM
            // supplement panels report trivial per-serving energy ("Glutamine
            // 10 g" → 40 kcal, omega-3 → 10 kcal) which previously summed
            // into the same Energy figure as meals — a day with ONE 850 kcal
            // meal displayed 910 kcal and the user counted the phantom 60.
            // The energy/macros card reflects FOOD; pills contribute
            // micronutrients (their macro content is derivatve noise, not a
            // food the user ate). Protein from meal-replacement shakes still
            // counts via its panel `protein` key — only `calories` is dropped.
            for (supp in meals.supplementsByDay(day)) {
                val values = runCatching { JSONObject(supp.nutrientContributions) }.getOrNull() ?: continue
                for (rawKey in values.keys()) {
                    val nutrientId = NutrientKeys.canonicalId(rawKey) ?: rawKey
                    if (nutrientId == "calories") continue   // see block comment
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

            if (rows.isNotEmpty()) nutrients.logIntake(rows)
            written += rows.size
            Log.d(TAG, "recompute($day): ${rows.size} ledger rows (water=$waterLiters L)")
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
         * Structured-macro ledger rows for one meal (pure, JVM-testable):
         * Tail v2 meal rows and v6 in-app captures store authoritative
         * calories/protein/carbohydrates/total-fat directly on the meal row.
         * Only nutrients with amount > 0 that [coveredByIngredients] does NOT
         * already contain are credited (fill-missing, never double-count),
         * each under a deterministic id so re-runs stay idempotent.
         */
        fun structuredMacroRows(
            meal: MealEntity,
            day: String,
            coveredByIngredients: Set<String>,
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
                if (amount <= 0.0) continue                    // 0 = unknown (entity KDoc)
                if (nutrientId in coveredByIngredients) continue
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
