package com.example.hoot.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the "Focus now" selection engine (reworked 2026-11-17):
 * every nutrient whose multi-window (7/30/90/365-day) chronic deficiency
 * likelihood clears [DeficiencyModel.REPORT_THRESHOLD] is listed — NO count
 * cap — ranked most-chronically-deficient first, least-eaten-today next.
 */
class FocusNowEngineTest {

    private fun input(
        id: String,
        tier: Int = 1,
        target: Double = 100.0,
        recentAvg: Double = 40.0,
        today: Double = 10.0,
        unit: String = "mg",
        windows: List<DeficiencyModel.WindowStat> = defaultWindows()
    ) = FocusNowInput(
        nutrientId = id, name = id, tier = tier, unit = unit,
        target = target, recentAvgIntake = recentAvg, todayIntake = today,
        windows = windows
    )

    /** Chronically low in every window: 25 tracked days, all low, ~30% avg. */
    private fun defaultWindows(tracked: Int = 25) = listOf(
        DeficiencyModel.WindowStat(7, tracked, tracked, 0.3),
        DeficiencyModel.WindowStat(30, tracked, tracked, 0.3),
        DeficiencyModel.WindowStat(90, tracked, tracked, 0.3),
        DeficiencyModel.WindowStat(365, tracked, tracked, 0.3)
    )

    /** Healthy windows: nothing low, full coverage everywhere. */
    private fun healthyWindows(tracked: Int = 25) = listOf(
        DeficiencyModel.WindowStat(7, tracked, 0, 1.0),
        DeficiencyModel.WindowStat(30, tracked, 0, 1.0),
        DeficiencyModel.WindowStat(90, tracked, 0, 1.0),
        DeficiencyModel.WindowStat(365, tracked, 0, 1.0)
    )

    @Test
    fun `selects nutrients chronically lacking`() {
        val out = FocusNowEngine.select(listOf(input("magnesium")))
        assertEquals(listOf("magnesium"), out.map { it.nutrientId })
        assertTrue(out.first().likelihood >= DeficiencyModel.REPORT_THRESHOLD)
    }

    @Test
    fun `excludes nutrients healthy across windows even with empty today`() {
        // Fresh day: today = 0, but all windows fully covered → not a real gap.
        val out = FocusNowEngine.select(
            listOf(input("vitamin_c", recentAvg = 90.0, today = 0.0, windows = healthyWindows()))
        )
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
    fun `excludes inputs without window evidence`() {
        // Legacy-style input (no windows): no chronic evidence → no claim.
        val out = FocusNowEngine.select(
            listOf(input("legacy", windows = emptyList()))
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `no cap - all qualifying nutrients are listed`() {
        val many = (1..12).map { input("n$it", tier = 1) }
        val out = FocusNowEngine.select(many)
        assertEquals(12, out.size)
    }

    @Test
    fun `chronic deficiency ranks above single bad week`() {
        // Both tier 1. chronicBad is low in ALL windows; blipWeek only in
        // the 7-day one (long windows healthy).
        val chronicBad = input(
            "chronic_bad",
            windows = listOf(
                DeficiencyModel.WindowStat(7, 25, 25, 0.3),
                DeficiencyModel.WindowStat(30, 25, 25, 0.3),
                DeficiencyModel.WindowStat(90, 25, 25, 0.3),
                DeficiencyModel.WindowStat(365, 25, 25, 0.3)
            )
        )
        val blipWeek = input(
            "blip_week",
            windows = listOf(
                DeficiencyModel.WindowStat(7, 25, 25, 0.3),
                DeficiencyModel.WindowStat(30, 25, 2, 0.95),
                DeficiencyModel.WindowStat(90, 25, 2, 0.95),
                DeficiencyModel.WindowStat(365, 25, 2, 0.95)
            )
        )
        val out = FocusNowEngine.select(listOf(blipWeek, chronicBad))
        assertEquals(listOf("chronic_bad", "blip_week"), out.map { it.nutrientId })
    }

    @Test
    fun `less eaten today ranks higher on equal likelihood`() {
        // Identical windows + tier → the nutrient with less consumed so far
        // today comes first (user request 2026-11-17).
        val out = FocusNowEngine.select(
            listOf(
                input("eaten_some", today = 60.0),
                input("eaten_nothing", today = 0.0)
            )
        )
        assertEquals(listOf("eaten_nothing", "eaten_some"), out.map { it.nutrientId })
    }

    @Test
    fun `worst window facts carried through`() {
        val out = FocusNowEngine.select(
            listOf(
                input(
                    "iron",
                    windows = listOf(
                        DeficiencyModel.WindowStat(7, 25, 0, 1.0),
                        DeficiencyModel.WindowStat(30, 25, 10, 0.4)
                    )
                )
            )
        )
        assertEquals(30, out.first().worstWindowDays)
        assertEquals(0.4, out.first().worstWindowAvgCoverage, 0.01)
    }

    @Test
    fun `sparse data alone does not qualify`() {
        // One window, 2 tracked days of poor coverage: data confidence
        // (1-e^(-2/12) ≈ 0.15) shrinks likelihood below the reporting
        // threshold → no false alarm from a two-day blip.
        val out = FocusNowEngine.select(
            listOf(
                input(
                    "sparse",
                    windows = listOf(DeficiencyModel.WindowStat(7, 2, 2, 0.2))
                )
            )
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `remaining never negative`() {
        assertEquals(0.0, FocusNowEngine.remaining(120.0, 100.0), 1e-9)
        assertEquals(40.0, FocusNowEngine.remaining(60.0, 100.0), 1e-9)
    }
}
