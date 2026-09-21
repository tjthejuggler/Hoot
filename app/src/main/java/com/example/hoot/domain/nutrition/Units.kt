package com.example.hoot.domain.nutrition

/**
 * Measure-unit model for ingredients + nutrient unit canonicalization.
 * Pure JVM (no Android imports) — exhaustive unit tests live in
 * `app/src/test/…/UnitsTest.kt`. Rules follow `docs/NUTRIENTS.md` §6.
 */
object Units {

    // ── Ingredient quantity units ────────────────────────────────────────

    /** Canonical unit keys accepted from users/LLM/Tail (lowercase). */
    val KNOWN_UNITS: Set<String> = setOf(
        "g", "kg", "mg", "oz", "lb", "ml", "l", "floz",
        "cup", "tbsp", "tsp", "piece", "slice", "clove", "handful",
        "pinch", "can", "serving"
    )

    /** Mass units → grams. */
    private val MASS_TO_G = mapOf(
        "g" to 1.0, "kg" to 1000.0, "mg" to 0.001,
        "oz" to 28.3495, "ounce" to 28.3495, "lb" to 453.592, "pound" to 453.592
    )

    /** Volume units → milliliters (water-density ≈1 g/ml unless profile density known). */
    private val VOLUME_TO_ML = mapOf(
        "ml" to 1.0, "l" to 1000.0, "liter" to 1000.0, "litre" to 1000.0,
        "floz" to 29.5735, "cup" to 240.0, "tbsp" to 15.0, "tablespoon" to 15.0,
        "tsp" to 5.0, "teaspoon" to 5.0
    )

    /** Display aliases → canonical key (parser-level). */
    val UNIT_ALIASES: Map<String, String> = mapOf(
        "grams" to "g", "gram" to "g", "gr" to "g", "g." to "g",
        "kilograms" to "kg", "kilogram" to "kg", "kgs" to "kg",
        "milliliters" to "ml", "milliliter" to "ml", "millilitres" to "ml", "millilitre" to "ml",
        "liters" to "l", "liter" to "l", "litres" to "l", "litre" to "l",
        "ounces" to "oz", "ounce" to "oz", "oz." to "oz",
        "pounds" to "lb", "pound" to "lb", "lbs" to "lb",
        "cups" to "cup", "tablespoons" to "tbsp", "tablespoon" to "tbsp",
        "tbsps" to "tbsp", "tbs" to "tbsp",
        "teaspoons" to "tsp", "teaspoon" to "tsp", "tsps" to "tsp",
        "pieces" to "piece", "pc" to "piece", "pcs" to "piece",
        "slices" to "slice", "cloves" to "clove",
        "handfuls" to "handful", "pinches" to "pinch", "cans" to "can",
        "servings" to "serving"
    )

    /**
     * Resolves a (quantity, unit) pair to grams.
     * - mass: exact conversion
     * - volume: ml→g at water density (1.0) unless [densityGPerMl] given
     * - count units (piece/slice/clove/serving/…): uses [servingGrams] hint
     *   (per-item grams from the resolved profile), null when no hint exists.
     */
    fun toGrams(quantity: Double, unitRaw: String?, densityGPerMl: Double = 1.0): Double? {
        val unit = normalizeUnit(unitRaw) ?: return null
        MASS_TO_G[unit]?.let { return quantity * it }
        VOLUME_TO_ML[unit]?.let { return quantity * it * densityGPerMl }
        // Count-style units have no fixed gram weight; caller supplies the hint.
        return null
    }

    /** True when the unit is a count-style unit (needs a per-item gram hint). */
    fun isCountUnit(unitRaw: String?): Boolean {
        val unit = normalizeUnit(unitRaw) ?: return false
        return unit in setOf("piece", "slice", "clove", "handful", "pinch", "can", "serving")
    }

    /** Alias folding + canonicalization; null for unknown units. */
    fun normalizeUnit(unitRaw: String?): String? {
        if (unitRaw.isNullOrBlank()) return null
        val u = unitRaw.trim().lowercase().trimEnd('.')
        return UNIT_ALIASES[u] ?: if (u in KNOWN_UNITS) u else null
    }

    // ── Nutrient canonicalization (docs/NUTRIENTS.md §6) ─────────────────

    /** IU → canonical special cases keyed by nutrient id (already canonical out). */
    private val IU_PER_NUTRIENT: Map<String, (Double) -> Double> = mapOf(
        // Vitamin D: 1 IU = 0.025 µg → ÷40 (docs §6)
        "vitamin_d" to { iu -> iu / 40.0 },
        // Vitamin A: retinol 1 IU = 0.3 µg RAE → ÷3.33 (docs §6)
        "vitamin_a" to { iu -> iu * 0.3 },
        // Vitamin E: natural α-tocopherol 1 IU = 0.67 mg → ÷1.49 (docs §6)
        "vitamin_e" to { iu -> iu / 1.49 }
    )

    /** Mass-unit ladder in mg equivalents. */
    private val MASS_FACTOR_TO_MG = mapOf("kg" to 1e6, "g" to 1000.0, "mg" to 1.0, "mcg" to 0.001, "µg" to 0.001, "ug" to 0.001)

    /**
     * Converts a reported nutrient amount to the canonical unit.
     * - identity families: kcal, L, NE (→mg), DFE (→mcg), ratio
     * - mass ladder: kg/g/mg/mcg in either direction
     - IU: special-cased per nutrient (null when no rule for that nutrient)
     * Returns null when the conversion is not defined.
     */
    fun canonicalNutrientAmount(nutrientId: String, amount: Double, unitRaw: String?, canonicalUnit: String): Double? {
        if (amount.isNaN()) return null
        val u = unitRaw?.trim()?.lowercase() ?: return amount // already canonical by convention
        // Identity for the canonical unit itself.
        if (u == canonicalUnit.lowercase()) return amount
        val canon = canonicalUnit.lowercase()
        // IU rules — the table already outputs the canonical magnitude
        // (vitamin D/E → mcg/mg respectively; vitamin A → mcg RAE).
        if (u == "iu") {
            val converted = IU_PER_NUTRIENT[nutrientId]?.invoke(amount) ?: return null
            return if (canon == "mcg") converted else toCanonMass(converted, canon)
        }
        // NE (niacin equivalents) → mg identity.
        if (u == "ne" && canon == "mg") return amount
        // DFE (folate) → mcg identity.
        if (u == "dfe" && canon == "mcg") return amount
        // Mass ladder.
        val fromFactor = MASS_FACTOR_TO_MG[u]
        if (fromFactor != null) {
            val asMg = amount * fromFactor
            val toFactor = MASS_FACTOR_TO_MG[canon] ?: return null
            return asMg / toFactor
        }
        // Water: L↔ml.
        if ((u == "ml" || u == "milliliter") && canon == "l") return amount / 1000.0
        if (u == "l" && canon == "ml") return amount * 1000.0
        return null
    }

    /** mg-value → canonical mass unit (mcg/mg). */
    private fun toCanonMass(valueMg: Double, canon: String): Double? {
        val toFactor = MASS_FACTOR_TO_MG[canon] ?: return null
        return valueMg / toFactor
    }

    /** Quantity parse: "1", "1.5", "½", "1/2", "1-2" (ranges → max, documented). */
    fun parseQuantity(text: String): Double? {
        val t = text.trim()
        VULGAR_FRACTIONS[t]?.let { return it }
        t.indexOf('/').takeIf { it > 0 }?.let {
            val num = t.substring(0, it).trim().toDoubleOrNull()
            val den = t.substring(it + 1).trim().toDoubleOrNull()
            if (num != null && den != null && den != 0.0) return num / den
        }
        t.toDoubleOrNull()?.let { return it }
        WORD_NUMBERS[t]?.let { return it }
        return null
    }

    val VULGAR_FRACTIONS: Map<String, Double> = mapOf(
        "½" to 0.5, "⅓" to 1.0 / 3, "⅔" to 2.0 / 3, "¼" to 0.25, "¾" to 0.75,
        "⅕" to 0.2, "⅖" to 0.4, "⅗" to 0.6, "⅛" to 0.125, "⅜" to 0.375
    )

    val WORD_NUMBERS: Map<String, Double> = mapOf(
        "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0,
        "six" to 6.0, "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0,
        "eleven" to 11.0, "twelve" to 12.0,
        "a" to 1.0, "an" to 1.0, "half" to 0.5
    )
}
