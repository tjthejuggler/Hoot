package com.example.hoot.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the "Focus now" selection engine (UI overhaul feedback #4):
 * recent-past AND today gaps, tier-ranked, limit-trackers excluded.
 */
class FocusNowEngineTest {

    private fun input(
        id: String,
        tier: Int = 1,
        target: Double = 100.0,
        recentAvg: Double = 40.0,
        today: Double = 10.0,
        unit: String = "mg"
    ) = FocusNowInput(
        nutrientId = id, name = id, tier = tier, unit = unit,
        target = target, recentAvgIntake = recentAvg, todayIntake = today
    )

    @Test
    fun `selects nutrients lacking recently and today`() {
        val out = FocusNowEngine.select(listOf(input("magnesium", recentAvg = 40.0, today = 20.0)))
        assertEquals(listOf("magnesium"), out.map { it.nutrientId })
        assertEquals(0.4, out.first().recentCoverage, 1e-9)
        assertEquals(0.2, out.first().todayCoverage, 1e-9)
    }

    @Test
    fun `excludes nutrients already on track today`() {
        val out = FocusNowEngine.select(
            listOf(
                input("ok_recent", recentAvg = 50.0, today = 95.0),
                input("gap", recentAvg = 50.0, today = 30.0)
            )
        )
        assertEquals(listOf("gap"), out.map { it.nutrientId })
    }

    @Test
    fun `excludes nutrients fine in recent past even if today is empty`() {
        // Fresh day: today = 0, but 7-day average was above 80% → not a real gap.
        val out = FocusNowEngine.select(listOf(input("vitamin_c", recentAvg = 90.0, today = 0.0)))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `excludes limit-trackers via zero target convention`() {
        // Callers pass target = 0 for limit-trackers (sodium, added sugar…).
        val out = FocusNowEngine.select(listOf(input("sodium", target = 0.0)))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `excludes nutrients with no target at all`() {
        val out = FocusNowEngine.select(listOf(input("unknown", target = 0.0)))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `ranks tier ascending then by lowest recent coverage`() {
        val out = FocusNowEngine.select(
            listOf(
                input("t2_low", tier = 2, recentAvg = 20.0),
                input("t1_high", tier = 1, recentAvg = 70.0),
                input("t1_low", tier = 1, recentAvg = 10.0),
                input("t3_low", tier = 3, recentAvg = 5.0)
            )
        )
        assertEquals(
            listOf("t1_low", "t1_high", "t2_low", "t3_low"),
            out.map { it.nutrientId }
        )
    }

    @Test
    fun `caps results at max`() {
        val many = (1..10).map { input("n$it", tier = 1, recentAvg = 10.0) }
        assertEquals(FocusNowEngine.RECENT_DAYS /* sanity const */, 7)
        assertEquals(6, FocusNowEngine.select(many).size)
        assertEquals(3, FocusNowEngine.select(many, max = 3).size)
    }

    @Test
    fun `boundary - exactly 80 percent is not lacking`() {
        val out = FocusNowEngine.select(listOf(input("edge", recentAvg = 80.0, today = 80.0)))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `remaining never negative`() {
        assertEquals(0.0, FocusNowEngine.remaining(120.0, 100.0), 1e-9)
        assertEquals(40.0, FocusNowEngine.remaining(60.0, 100.0), 1e-9)
    }
}
