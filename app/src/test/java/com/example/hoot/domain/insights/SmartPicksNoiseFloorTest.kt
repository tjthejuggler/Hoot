package com.example.hoot.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Systemic "0%"-card regression tests (feedback 2026-09-23): trace nutrients
 * must not qualify a food as a source for a gap. Nori's potassium (21 mg per
 * 2 g sheet vs a ~2600 mg deficit = 0.8%) and coconut oil's choline
 * (0.3 mg/100 g) previously became hit rows that the UI rendered as
 * "0 g · 0%" while still citing the nutrient as the reason.
 *
 * Root fix: [SmartFoodMatcher.MIN_HIT_COVERAGE] — a serving must cover ≥3%
 * of the remaining daily deficit for the nutrient to count as a hit.
 */
class SmartPicksNoiseFloorTest {

    private fun gap(
        id: String,
        tier: Int = 1,
        target: Double,
        today: Double = 0.0
    ) = SmartGap(nutrientId = id, name = id, tier = tier, unit = "mg", target = target, todayIntake = today)

    private fun food(
        id: String,
        name: String = id,
        serving: Double? = null,
        per100: Map<String, Double>
    ) = SmartCandidateFood(foodId = id, displayName = name, category = null, servingGrams = serving, per100 = per100)

    @Test
    fun `trace amount below the noise floor is not a hit`() {
        // Nori replica: 1070 mg potassium/100 g on a 2 g serving = 21.4 mg
        // vs a 2600 mg deficit → 0.8% < MIN_HIT_COVERAGE → no hit.
        val picks = SmartFoodMatcher.match(
            gaps = listOf(gap("potassium", target = 2600.0)),
            excesses = emptyList(),
            foods = listOf(food("nori", name = "Nori", serving = 2.0, per100 = mapOf("potassium" to 1070.0)))
        )
        assertTrue("trace potassium must not qualify nori", picks.isEmpty())
    }

    @Test
    fun `meaningful amount above the floor is still a hit`() {
        // Same nori but a realistic 30 g serving: 321 mg vs 2600 mg = 12.3% ≥
        // floor. minScore is lowered here so THIS test isolates the noise
        // floor — the default MIN_SCORE bar is tested elsewhere.
        val picks = SmartFoodMatcher.match(
            gaps = listOf(gap("potassium", target = 2600.0)),
            excesses = emptyList(),
            foods = listOf(food("nori", name = "Nori", serving = 30.0, per100 = mapOf("potassium" to 1070.0))),
            minScore = 0.0
        )
        assertEquals(1, picks.size)
        assertEquals(1, picks[0].hits.size)
        assertTrue(picks[0].hits.first().deficitCovered >= SmartFoodMatcher.MIN_HIT_COVERAGE)
    }

    @Test
    fun `coconut-oil choline trace does not make it a choline source`() {
        // 0.3 mg choline/100 g (per-100 display serving) vs 425 mg deficit
        // = 0.07% — far below the floor.
        val picks = SmartFoodMatcher.match(
            gaps = listOf(gap("choline", target = 425.0)),
            excesses = emptyList(),
            foods = listOf(food("coconut oil", name = "Coconut Oil", serving = 14.0, per100 = mapOf("choline" to 0.3)))
        )
        assertTrue("0.07% coverage must never cite choline", picks.isEmpty())
    }

    @Test
    fun `mixed food keeps only meaningful hits`() {
        // Food rich in magnesium but trace in potassium: the potassium hit
        // must drop, the magnesium hit must survive.
        val picks = SmartFoodMatcher.match(
            gaps = listOf(
                gap("magnesium", target = 400.0),
                gap("potassium", target = 2600.0)
            ),
            excesses = emptyList(),
            foods = listOf(
                food("kale", name = "Kale", serving = 100.0, per100 = mapOf("magnesium" to 150.0, "potassium" to 5.0))
            )
        )
        assertEquals(1, picks.size)
        assertEquals(listOf("magnesium"), picks[0].hits.map { it.nutrientId })
    }

    @Test
    fun `food whose every hit is trace produces no pick`() {
        val picks = SmartFoodMatcher.match(
            gaps = listOf(gap("fiber", target = 30.0)),
            excesses = emptyList(),
            foods = listOf(food("rice cake", name = "Rice Cake", serving = 9.0, per100 = mapOf("fiber" to 2.0)))
        )
        // 0.18 g fiber on a 9 g cake = 0.6% of a 30 g deficit → below floor.
        assertTrue(picks.isEmpty())
    }
}
