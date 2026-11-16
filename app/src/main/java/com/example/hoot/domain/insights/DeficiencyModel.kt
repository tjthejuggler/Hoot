package com.example.hoot.domain.insights

/**
 * Deficiency-likelihood model (deficiency system, 2026-11-16). Pure JVM.
 *
 * For each nutrient the caller supplies the per-window coverage facts:
 *   - trackedDays: days in the window with ANY intake data
 *   - lowDays: tracked days below the "good day" line (target × ratio)
 *   - avgCoverage: mean coverage over tracked days (cap 1.0 per day)
 *
 * Likelihood 0..1 blends:
 *   severity  — how far below target the average sits
 *   frequency — share of tracked days that were low
 *   chronicity— low frequency across LONGER windows amplifies: a nutrient
 *               low in the week AND the month AND the year is far more
 *               likely deficient than one low this week only (body stores
 *               take months to deplete). Windows compound multiplicatively.
 *   dataConfidence — shrinks toward 0 when little data exists (2 tracked
 *               days say less than 60).
 */
object DeficiencyModel {

    /** Windows fed by the caller, longest last. */
    data class WindowStat(
        val days: Int,               // window length (7 / 30 / 90 / 365)
        val trackedDays: Int,
        val lowDays: Int,
        val avgCoverage: Double      // 0..1 mean of daily coverage
    )

    data class Input(
        val nutrientId: String,
        val name: String,
        val tier: Int,
        val unit: String,
        val target: Double,
        val windows: List<WindowStat>,
        /** Today-so-far coverage (0..1) — recency nudge only. */
        val todayCoverage: Double = 0.0
    )

    data class Assessment(
        val nutrientId: String,
        val name: String,
        val tier: Int,
        val unit: String,
        val target: Double,
        /** 0..1 probability the user is actually deficient. */
        val likelihood: Double,
        /** Worst (lowest) window coverage, for the "low for N days" line. */
        val worstWindowDays: Int,
        val worstWindowAvgCoverage: Double
    )

    /** Days below target×ratio count as "low". */
    const val LOW_RATIO = 0.75

    /** Weight of each window in the severity average (longer = heavier). */
    private val WINDOW_WEIGHTS = mapOf(7 to 1.0, 30 to 1.5, 90 to 2.0, 365 to 2.5)

    fun assess(input: Input): Assessment {
        val scored = input.windows
            .filter { it.trackedDays > 0 && it.days > 0 }
        var likelihood = 0.0
        var worstDays = 0
        var worstAvg = 1.0
        if (scored.isNotEmpty()) {
            // Severity: coverage deficit (1 - avg), weighted by window length.
            var wSum = 0.0
            var sev = 0.0
            var chronic = 1.0
            for (w in scored) {
                val weight = WINDOW_WEIGHTS[w.days] ?: 1.0
                wSum += weight
                sev += (1.0 - w.avgCoverage.coerceIn(0.0, 1.0)) * weight
                // Chronicity: each window with ≥50% low days compounds ×0.85.
                val lowFrac = w.lowDays.toDouble() / w.trackedDays
                if (lowFrac >= 0.5) chronic *= (1.0 - 0.15 * (lowFrac - 0.5) * 2.0).coerceIn(0.7, 1.0)
                if (w.avgCoverage < worstAvg) {
                    worstAvg = w.avgCoverage
                    worstDays = w.days
                }
            }
            val severity = (sev / wSum).coerceIn(0.0, 1.0)

            // Frequency: low-day share in the most recent meaningful window
            // (the 7-day if tracked, else the shortest tracked).
            val freqWindow = scored.minByOrNull { it.days } ?: scored.first()
            val frequency = (freqWindow.lowDays.toDouble() / freqWindow.trackedDays).coerceIn(0.0, 1.0)

            // Data confidence: saturating curve — 3 tracked days ≈ 0.4,
            // 14 days ≈ 0.8, 30+ ≈ ~0.95.
            val totalTracked = scored.sumOf { it.trackedDays }.coerceAtMost(90)
            val dataConf = 1.0 - Math.exp(-totalTracked / 12.0)

            // Blend + chronicity amplifier (up to ×1.3 when ALL windows low).
            val chronicAmp = 1.0 + (1.0 - chronic) * 0.3
            likelihood = (0.45 * severity + 0.35 * frequency + 0.10 * (1.0 - input.todayCoverage.coerceIn(0.0, 1.0))) *
                dataConf * chronicAmp
        }
        // Tier nudge: critical nutrients surface slightly earlier.
        val tierNudge = when (input.tier) { 1 -> 1.1; 2 -> 1.0; else -> 0.9 }
        return Assessment(
            nutrientId = input.nutrientId,
            name = input.name,
            tier = input.tier,
            unit = input.unit,
            target = input.target,
            likelihood = (likelihood * tierNudge).coerceIn(0.0, 0.99),
            worstWindowDays = worstDays,
            worstWindowAvgCoverage = worstAvg
        )
    }

    /** Convenience: threshold used by UI to include a nutrient in lists. */
    const val REPORT_THRESHOLD = 0.25
}
