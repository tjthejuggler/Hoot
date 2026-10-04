package com.example.hoot.domain.insights

import com.example.hoot.domain.nutrition.SeedFoodLibrary
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression pin for the recurring "Focus Now shows no foods" bug: the seed
 * candidate pool behind RecommendationEngine.findCachedSources must keep
 * yielding a meaningful number of quality-gated sources for the nutrients
 * Focus Now typically surfaces. The 2026-09-29 offline fix added the seed
 * fallback; this test guards both the seed values AND the quality gates
 * (name precision, density floor) so neither can silently empty the pool
 * again.
 */
class SeedSourceCoverageTest {

    /** Seed RDA-style targets (NutrientSeed.kt). */
    private val targets = mapOf(
        "magnesium" to 400.0,
        "fiber" to 38.0,
        "vitamin_e" to 15.0,
        "potassium" to 3400.0,
        "vitamin_c" to 90.0,
        "calcium" to 1000.0,
        "vitamin_a" to 900.0,
        "iron" to 18.0,
        "zinc" to 11.0,
        "vitamin_b9" to 400.0
    )

    @Test
    fun seedPoolSurvivesQualityGatesForCommonGaps() {
        val shortfalls = ArrayList<String>()
        for ((nutrientId, target) in targets) {
            val passing = SeedFoodLibrary.foods.values
                .distinctBy { it.key }
                .count { seed ->
                    NutrientSourceQuality.isAcceptableSourceName(seed.displayName) &&
                        (seed.per100[nutrientId] ?: 0.0) > 0 &&
                        NutrientSourceQuality.meetsDensityFloor(
                            NutrientSourceQuality.servingCoverage(
                                per100 = seed.per100.getValue(nutrientId),
                                servingGrams = seed.typicalServingGrams,
                                target = target
                            )
                        )
                }
            if (passing < 5) shortfalls += "$nutrientId=$passing"
        }
        assertTrue("Seed pool too thin: $shortfalls", shortfalls.isEmpty())
    }
}
