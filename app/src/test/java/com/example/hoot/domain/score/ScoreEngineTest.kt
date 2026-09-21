package com.example.hoot.domain.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [ScoreEngine] — completeness, deficiency penalties,
 * UL handling, adherence and the well-rounded bonus (ARCHITECTURE.md §6).
 */
class ScoreEngineTest {

    private fun nutrient(
        id: String,
        tier: Int = 1,
        intake: Double,
        target: Double = 100.0,
        ul: Double? = null,
        limit: Boolean = false,
        streak: Int = 0
    ) = NutrientScoreInput(
        nutrientId = id, name = id, tier = tier, unit = "g",
        intake = intake, target = target, ul = ul,
        isLimitTracker = limit, lowStreakDays = streak
    )

    // ---- Completeness -----------------------------------------------------

    @Test
    fun `perfect intake on all tiers scores 100`() {
        val inputs = listOf(
            nutrient("protein", tier = 1, intake = 100.0),
            nutrient("vitamin_c", tier = 2, intake = 100.0),
            nutrient("vitamin_e", tier = 3, intake = 100.0)
        )
        val r = ScoreEngine.score(inputs, AdherenceSummary())
        assertEquals(100.0, r.completeness, 0.001)
        assertEquals(100.0, r.score, 0.001)
        assertEquals(0.0, r.deficiencyPenalty, 0.001)
    }

    @Test
    fun `tier weights dominate completeness - missing tier1 hurts more than tier3`() {
        val tier1Missing = listOf(
            nutrient("protein", tier = 1, intake = 0.0),
            nutrient("b6", tier = 1, intake = 100.0),
            nutrient("b12", tier = 1, intake = 100.0),
            nutrient("vita", tier = 2, intake = 100.0),
            nutrient("vitc", tier = 2, intake = 100.0),
            nutrient("vite", tier = 3, intake = 100.0)
        )
        val tier3Missing = listOf(
            nutrient("protein", tier = 1, intake = 100.0),
            nutrient("b6", tier = 1, intake = 100.0),
            nutrient("b12", tier = 1, intake = 100.0),
            nutrient("vita", tier = 2, intake = 100.0),
            nutrient("vitc", tier = 2, intake = 100.0),
            nutrient("vite", tier = 3, intake = 0.0)
        )
        val r1 = ScoreEngine.score(tier1Missing, AdherenceSummary())
        val r3 = ScoreEngine.score(tier3Missing, AdherenceSummary())
        assertTrue(
            "missing Tier-1 (${r1.completeness}) should score lower than missing Tier-3 (${r3.completeness})",
            r1.completeness < r3.completeness
        )
    }

    @Test
    fun `coverage caps at 100 percent - over-eating protein does not exceed target`() {
        val r = ScoreEngine.score(
            listOf(nutrient("protein", intake = 500.0)),
            AdherenceSummary()
        )
        assertEquals(1.0, r.components[0].coverage, 0.001)
        assertEquals(100.0, r.completeness, 0.001)
    }

    // ---- Deficiency penalty ------------------------------------------------

    @Test
    fun `tier1 shortfall below half target triggers penalty`() {
        val inputs = listOf(
            nutrient("iron", tier = 1, intake = 25.0)  // 25 % of target
        )
        val r = ScoreEngine.score(inputs, AdherenceSummary())
        assertTrue(
            "penalty should be > 0, was ${r.deficiencyPenalty}",
            r.deficiencyPenalty > 0.0
        )
        assertTrue("score below 100, was ${r.score}", r.score < 100.0)
    }

    @Test
    fun `tier1 at 60 percent does not trigger deficiency penalty`() {
        val r = ScoreEngine.score(
            listOf(nutrient("iron", tier = 1, intake = 60.0)),
            AdherenceSummary()
        )
        assertEquals(0.0, r.deficiencyPenalty, 0.001)
    }

    @Test
    fun `tier3 shortfall does not trigger deficiency penalty`() {
        val r = ScoreEngine.score(
            listOf(nutrient("vitamin_e", tier = 3, intake = 10.0)),
            AdherenceSummary()
        )
        assertEquals(0.0, r.deficiencyPenalty, 0.001)
    }

    @Test
    fun `streak amplifies tier1 penalty`() {
        val fresh = ScoreEngine.score(
            listOf(nutrient("iron", tier = 1, intake = 20.0, streak = 0)),
            AdherenceSummary()
        )
        val sustained = ScoreEngine.score(
            listOf(nutrient("iron", tier = 1, intake = 20.0, streak = 5)),
            AdherenceSummary()
        )
        assertTrue(
            "sustained deficit penalty (${sustained.deficiencyPenalty}) > fresh (${fresh.deficiencyPenalty})",
            sustained.deficiencyPenalty > fresh.deficiencyPenalty
        )
        assertTrue(sustained.score < fresh.score)
    }

    // ---- UL / limit-trackers ----------------------------------------------

    @Test
    fun `ul exceedance counts and penalizes`() {
        val r = ScoreEngine.score(
            listOf(nutrient("vitamin_c", tier = 2, intake = 2500.0, ul = 2000.0)),
            AdherenceSummary()
        )
        assertEquals(1, r.ulExceedances)
        assertTrue(r.components[0].ulExceeded)
        assertEquals(ScoreStatus.EXCESS, r.components[0].status)
        assertTrue("penalty > 0, was ${r.deficiencyPenalty}", r.deficiencyPenalty > 0.0)
        assertTrue(r.score < 100.0)
    }

    @Test
    fun `supplemental-only UL absence is handled by caller - null UL never penalizes`() {
        val r = ScoreEngine.score(
            listOf(nutrient("magnesium", tier = 1, intake = 1000.0, ul = null)),
            AdherenceSummary()
        )
        assertEquals(0, r.ulExceedances)
        assertEquals(100.0, r.completeness, 0.001)
    }

    @Test
    fun `limit tracker full coverage under cap`() {
        val r = ScoreEngine.score(
            listOf(nutrient("sodium", intake = 1000.0, target = 2300.0, limit = true)),
            AdherenceSummary()
        )
        assertEquals(1.0, r.components[0].coverage, 0.001)
        assertEquals(ScoreStatus.MET, r.components[0].status)
        assertEquals(100.0, r.completeness, 0.001)
    }

    @Test
    fun `limit tracker decays to zero at 1_5x cap`() {
        val at1x = ScoreEngine.score(
            listOf(nutrient("sugar", intake = 2500.0, target = 2300.0, limit = true)),
            AdherenceSummary()
        )
        val at15x = ScoreEngine.score(
            listOf(nutrient("sugar", intake = 2300.0 * 1.5, target = 2300.0, limit = true)),
            AdherenceSummary()
        )
        assertTrue(at1x.components[0].coverage in 0.0..1.0 && at1x.components[0].coverage > 0.0)
        assertEquals(0.0, at15x.components[0].coverage, 0.001)
        assertTrue(at1x.completeness > at15x.completeness)
    }

    @Test
    fun `tier1 limit-tracker excess also penalizes`() {
        val r = ScoreEngine.score(
            listOf(nutrient("sugar_t1", tier = 1, intake = 5000.0, target = 25.0, limit = true)),
            AdherenceSummary()
        )
        assertTrue(r.components[0].coverage <= 0.01)
        assertTrue(r.score < 100.0)
    }

    // ---- Adherence ----------------------------------------------------------

    @Test
    fun `no recommendations means neutral adherence`() {
        val r = ScoreEngine.score(
            listOf(nutrient("protein", intake = 100.0)),
            AdherenceSummary(issued = 0)
        )
        assertEquals(1.0, r.adherence, 0.001)
        assertEquals(100.0, r.score, 0.001)
    }

    @Test
    fun `all accepted recommendations give full adherence`() {
        val r = ScoreEngine.score(
            listOf(nutrient("protein", intake = 100.0)),
            AdherenceSummary(issued = 4, accepted = 4)
        )
        assertEquals(1.0, r.adherence, 0.001)
    }

    @Test
    fun `dismissed recommendations count mildly against`() {
        // Same accepted count, but dismissed recommendations drag adherence down.
        val clean = ScoreEngine.score(
            listOf(nutrient("vitamin_c", tier = 2, intake = 100.0)),
            AdherenceSummary(issued = 4, accepted = 2, dismissed = 0)
        )
        val dismissed = ScoreEngine.score(
            listOf(nutrient("vitamin_c", tier = 2, intake = 100.0)),
            AdherenceSummary(issued = 4, accepted = 2, dismissed = 2)
        )
        assertEquals(0.5, clean.adherence, 0.001)          // 2/4
        assertEquals(0.375, dismissed.adherence, 0.001)    // (2 − 0.25·2)/4
        assertTrue(dismissed.score < clean.score)
    }

    @Test
    fun `adherence shifts score at perfect nutrition`() {
        // Tier-2 solo: no well-rounded floor interference.
        val good = ScoreEngine.score(
            listOf(nutrient("vitamin_c", tier = 2, intake = 100.0)),
            AdherenceSummary(issued = 2, accepted = 2)
        )
        val bad = ScoreEngine.score(
            listOf(nutrient("vitamin_c", tier = 2, intake = 100.0)),
            AdherenceSummary(issued = 2, accepted = 0)
        )
        assertEquals(100.0, good.score, 0.001)
        assertEquals(
            100.0 * (ScoreEngine.W_COMPLETENESS + ScoreEngine.W_DEFICIENCY),
            bad.score,
            0.001
        )
    }

    // ---- Well-rounded bonus --------------------------------------------------

    @Test
    fun `well-rounded bonus floors the score when all tier1 met and no UL exceeded`() {
        // Bad adherence would otherwise drag the score below the floor.
        val inputs = listOf(
            nutrient("protein", tier = 1, intake = 95.0),
            nutrient("iron", tier = 1, intake = 100.0),
            nutrient("vitamin_d", tier = 1, intake = 90.0),
            nutrient("vitamin_e", tier = 3, intake = 10.0)
        )
        val r = ScoreEngine.score(inputs, AdherenceSummary(issued = 5, accepted = 0))
        assertTrue(r.wellRounded)
        assertEquals(ScoreEngine.WELL_ROUNDED_FLOOR, r.score, 0.001)
    }

    @Test
    fun `well-rounded is false when a tier1 nutrient is below 90 percent`() {
        val r = ScoreEngine.score(
            listOf(
                nutrient("protein", tier = 1, intake = 95.0),
                nutrient("iron", tier = 1, intake = 50.0)
            ),
            AdherenceSummary(issued = 5, accepted = 0)
        )
        assertFalse(r.wellRounded)
    }

    @Test
    fun `well-rounded is false when any UL is exceeded`() {
        val r = ScoreEngine.score(
            listOf(
                nutrient("protein", tier = 1, intake = 100.0),
                nutrient("iron", tier = 1, intake = 100.0, ul = 45.0)
            ),
            AdherenceSummary(issued = 5, accepted = 0)
        )
        assertFalse(r.wellRounded)
    }

    // ---- Edge cases ----------------------------------------------------------

    @Test
    fun `empty inputs produce a zero score without crashing`() {
        val r = ScoreEngine.score(emptyList(), AdherenceSummary())
        assertEquals(0.0, r.score, 0.001)
        assertEquals(0, r.nutrientsTracked)
    }

    @Test
    fun `zero-target nutrients are excluded from scoring`() {
        val r = ScoreEngine.score(
            listOf(
                nutrient("protein", intake = 100.0),
                nutrient("calories", intake = 0.0, target = 0.0)
            ),
            AdherenceSummary()
        )
        assertEquals(1, r.nutrientsTracked)
        assertEquals(100.0, r.score, 0.001)
    }

    @Test
    fun `score is always clamped to 0-100`() {
        val worst = ScoreEngine.score(
            listOf(nutrient("iron", tier = 1, intake = 0.0, streak = 99)),
            AdherenceSummary(issued = 10, dismissed = 10)
        )
        assertTrue(worst.score in 0.0..100.0)
    }
}
