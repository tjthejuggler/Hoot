package com.example.hoot.domain.nutrition

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests (2026-09-29): Tail-synced ingredient rows often carry NO
 * amount/unit. The IntakeAggregator fallback must then estimate grams from the
 * food's typicalServingGrams (passed as BOTH the per-item hint and the portion
 * default) instead of dropping the whole ingredient — dropping it silently
 * zeroed every nutrient only that ingredient supplied (added sugars, iodine,
 * chloride… showed "no data" for week/month windows while the profiles had
 * values).
 */
class AggregatorFallbackTest {

    @Test
    fun `no amount and no unit falls back to one typical serving`() {
        // Exact call shape of IntakeAggregator.recomputeDays (perItem=portion=serving hint)
        val grams = IngredientParser.gramsEstimate(
            quantity = null, unit = null, perItemGrams = 65.0, portionDefaultGrams = 65.0
        )
        assertEquals(65.0, grams)
    }

    @Test
    fun `count unit with hint still multiplies`() {
        val grams = IngredientParser.gramsEstimate(
            quantity = 2.0, unit = "slice", perItemGrams = 28.0, portionDefaultGrams = 28.0
        )
        assertEquals(56.0, grams)
    }

    @Test
    fun `mass unit still converts exactly`() {
        val grams = IngredientParser.gramsEstimate(
            quantity = 130.0, unit = "g", perItemGrams = 185.0, portionDefaultGrams = 185.0
        )
        assertEquals(130.0, grams)
    }
}
