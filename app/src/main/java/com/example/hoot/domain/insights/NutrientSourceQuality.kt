package com.example.hoot.domain.insights

/**
 * Shared food-suggestion QUALITY engine (feedback 2026-09-23: "herbs and
 * seasonings" at 1% DV next to chia seeds at 17% — one vague, one weak).
 *
 * Three deterministic gates applied identically everywhere suggestions are
 * produced OR displayed (RecommendationEngine cache/LLM paths, the nutrient
 * detail sheet, the insights carousel):
 *
 *  1. NAME PRECISION — [isAcceptableSourceName]: the existing
 *     [SmartFoodMatcher.isPlausibleFoodName] gate PLUS a vague-category
 *     blacklist ("herbs and seasonings", "spices", "vegetables", …). A
 *     category that names no concrete food is useless as a suggestion even
 *     when a nutrient panel exists for it.
 *  2. DENSITY FLOOR — [meetsDensityFloor]: a food must cover a meaningful
 *     share of the daily target in ONE realistic serving (default ≥ 10%).
 *     Sub-10% foods merely pad the list and crowd out better options.
 *  3. PER-SERVING RANKING — [rankingCoverage]: sort by % of the daily target
 *     delivered by the food's typical serving (per-100g × serving/100),
 *     NOT by raw per-100g values (100 g of herbs beats 100 g of cheese on
 *     paper, but nobody eats 100 g of herbs).
 *
 * Pure JVM + unit-testable.
 */
object NutrientSourceQuality {

    /** Min share of the daily target one serving must cover (0..1). */
    const val MIN_SERVING_COVERAGE = 0.10

    /** Vague umbrella/category names — never concrete enough to recommend. */
    private val VAGUE_NAMES: Set<String> = setOf(
        "herbs", "herb", "seasonings", "seasoning", "spices", "spice",
        "herbs and seasonings", "mixed herbs", "dried herbs",
        "vegetables", "fruits", "greens", "salad", "salads",
        "grains", "cereal", "cereals", "nuts", "seeds", "beans", "legumes",
        "dairy", "seafood", "meat", "poultry", "supplements", "supplement",
        "misc", "other", "assorted", "variety", "mixed"
    )

    /** Connector words that don't carry food identity ("herbs AND seasonings"). */
    private val CONNECTORS: Set<String> = setOf("and", "or", "n", "with", "the", "of")

    /**
     * True when the name is precise enough to recommend: passes the shared
     * [SmartFoodMatcher.isPlausibleFoodName] gate AND names a concrete food.
     * A name is VAGUE when the full phrase is a category word, or when EVERY
     * meaningful token is a category word ("Herbs and seasonings", "Seeds"
     * fail; "Chia seeds" passes — "chia" carries the food identity).
     */
    fun isAcceptableSourceName(raw: String): Boolean {
        if (!SmartFoodMatcher.isPlausibleFoodName(raw)) return false
        val words = raw.lowercase().split(Regex("[^a-z]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return false
        if (words.joinToString(" ") in VAGUE_NAMES) return false
        val meaningful = words.filter { it !in CONNECTORS }
        if (meaningful.isEmpty()) return false
        return meaningful.any { it !in VAGUE_NAMES }
    }

    /**
     * % of the daily [target] delivered by ONE serving: per-100 g value
     * scaled to the food's typical serving grams (fallback 100 g when the
     * serving is unknown — honest default, never inflated).
     */
    fun servingCoverage(per100: Double, servingGrams: Double?, target: Double): Double {
        if (per100 <= 0 || target <= 0) return 0.0
        val grams = servingGrams?.takeIf { it > 0 } ?: 100.0
        return (per100 * grams / 100.0) / target
    }

    /** True when one serving covers at least [MIN_SERVING_COVERAGE] of target. */
    fun meetsDensityFloor(servingCoverage: Double): Boolean =
        servingCoverage >= MIN_SERVING_COVERAGE

    /**
     * Parses the legacy reason template ("…covers about N% of your X target
     * per portion…") to recover the stored coverage when re-ranking
     * persisted rows. Null when the text does not match the template
     * (LLM-authored reasons etc.).
     */
    fun parseCoveragePct(reasonText: String): Double? {
        val m = Regex("covers about (\\d+)%").find(reasonText) ?: return null
        return m.groupValues[1].toIntOrNull()?.toDouble()
    }
}
