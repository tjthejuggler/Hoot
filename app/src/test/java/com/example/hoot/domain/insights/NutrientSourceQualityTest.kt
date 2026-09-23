package com.example.hoot.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shared food-suggestion quality engine (feedback 2026-09-23):
 * vague category names are rejected, one-serving density floor, per-serving
 * (not per-100g) ranking, and reason-template coverage parsing.
 */
class NutrientSourceQualityTest {

    // ---- Name precision ----------------------------------------------------

    @Test
    fun `vague category names are rejected`() {
        assertFalse(NutrientSourceQuality.isAcceptableSourceName("Herbs and seasonings"))
        assertFalse(NutrientSourceQuality.isAcceptableSourceName("Herbs & Seasonings"))
        assertFalse(NutrientSourceQuality.isAcceptableSourceName("Spices"))
        assertFalse(NutrientSourceQuality.isAcceptableSourceName("Vegetables"))
        assertFalse(NutrientSourceQuality.isAcceptableSourceName("Mixed"))
        assertFalse(NutrientSourceQuality.isAcceptableSourceName("Seeds"))
    }

    @Test
    fun `concrete foods pass the name gate`() {
        assertTrue(NutrientSourceQuality.isAcceptableSourceName("Chia seeds"))
        assertTrue(NutrientSourceQuality.isAcceptableSourceName("Greek Yogurt"))
        assertTrue(NutrientSourceQuality.isAcceptableSourceName("Sardines"))
        assertTrue(NutrientSourceQuality.isAcceptableSourceName("Kale"))
    }

    @Test
    fun `junk rows fail via the shared plausibility gate`() {
        assertFalse(NutrientSourceQuality.isAcceptableSourceName("A Mixed Meal Of Rice"))
        assertFalse(NutrientSourceQuality.isAcceptableSourceName("Dark Beverage"))
    }

    // ---- Per-serving coverage ----------------------------------------------

    @Test
    fun `serving coverage scales per-100g to the serving`() {
        // 1000 mg/100g target 1000 mg, 30 g serving → 30%.
        assertEquals(0.30, NutrientSourceQuality.servingCoverage(1000.0, 30.0, 1000.0), 1e-9)
        // Unknown serving falls back to 100 g (honest, not inflated).
        assertEquals(1.0, NutrientSourceQuality.servingCoverage(1000.0, null, 1000.0), 1e-9)
        // Zero target / zero value → 0, never a divide-by-zero.
        assertEquals(0.0, NutrientSourceQuality.servingCoverage(100.0, 30.0, 0.0), 1e-9)
        assertEquals(0.0, NutrientSourceQuality.servingCoverage(0.0, 30.0, 1000.0), 1e-9)
    }

    @Test
    fun `density floor rejects weak sources`() {
        // 17% daily value in one serving → passes (chia-seed case).
        assertTrue(NutrientSourceQuality.meetsDensityFloor(0.17))
        // 1% daily value → rejected (herbs-and-seasonings case).
        assertFalse(NutrientSourceQuality.meetsDensityFloor(0.01))
        // Exactly at the 10% floor → passes.
        assertTrue(NutrientSourceQuality.meetsDensityFloor(NutrientSourceQuality.MIN_SERVING_COVERAGE))
    }

    @Test
    fun `per-serving ranking fixes the herbs-vs-chia inversion`() {
        // Per 100 g: herbs 400 mg > chia 630 mg? No — the inversion case is
        // herbs 630 mg/100 g eaten at 2 g vs chia 350 mg/100 g eaten at 30 g.
        val herbsCoverage = NutrientSourceQuality.servingCoverage(630.0, 2.0, 1000.0)
        val chiaCoverage = NutrientSourceQuality.servingCoverage(350.0, 30.0, 1000.0)
        assertTrue("chia serving should beat herbs serving", chiaCoverage > herbsCoverage)
        assertTrue(herbsCoverage < NutrientSourceQuality.MIN_SERVING_COVERAGE)
    }

    // ---- Reason parsing ------------------------------------------------------

    @Test
    fun `parses coverage from the standard reason template`() {
        assertEquals(
            17.0,
            NutrientSourceQuality.parseCoveragePct(
                "Chia seeds covers about 17% of your Calcium target per portion (you're at 22% today)."
            ) ?: -1.0,
            1e-9
        )
    }

    @Test
    fun `non-template reasons parse to null`() {
        assertNull(NutrientSourceQuality.parseCoveragePct("Rich in calcium."))
        assertNull(NutrientSourceQuality.parseCoveragePct(""))
    }
}
