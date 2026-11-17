package com.example.hoot.domain.insights

/**
 * "Focus now" selection (UI/UX overhaul feedback #4, reworked 2026-11-17):
 * nutrients the user is NOT GETTING ENOUGH OF IN GENERAL — judged from
 * multiple horizons at once (today, trailing 7 days, 30 days, 90 days and
 * a full year) via [DeficiencyModel].
 *
 * Selection has NO fixed count: every nutrient whose chronic-deficiency
 * likelihood clears [DeficiencyModel.REPORT_THRESHOLD] is included.
 * Ranking follows the same drivers the user asked for:
 *   - the more chronically deficient (low across week AND month AND year),
 *     the higher — [DeficiencyModel] compounds windows multiplicatively;
 *   - the less consumed so far today, the higher — likelihood carries a
 *     recency factor, plus an explicit today tiebreak.
 *
 * Pure JVM + unit-testable: the caller pre-aggregates per-window coverage
 * facts, this engine only decides who qualifies and in which order. Excess
 * (limit-tracker) nutrients are deliberately excluded — you never "eat
 * more" of a cap.
 */

/** Per-nutrient inputs the selection needs (all pre-aggregated by the caller). */
data class FocusNowInput(
    val nutrientId: String,
    val name: String,
    val tier: Int,                        // 1 critical / 2 important / 3 nice-to-have
    val unit: String,
    /** Target-trackers only: RDA/AI/custom goal. Limit-trackers are never selected. */
    val target: Double,
    /** Trailing [FocusNowEngine.RECENT_DAYS]-day average intake (display only). */
    val recentAvgIntake: Double,
    /** Intake so far today (0 when nothing logged yet). */
    val todayIntake: Double,
    /**
     * Coverage facts per window (7/30/90/365 days), longest last. Empty for
     * callers that only want the legacy today/7-day behaviour — such inputs
     * can no longer qualify (no chronic evidence).
     */
    val windows: List<DeficiencyModel.WindowStat> = emptyList()
)

/** One "focus now" row for the Home card. */
data class FocusNowItem(
    val nutrientId: String,
    val name: String,
    val tier: Int,
    val unit: String,
    val target: Double,
    val recentAvgIntake: Double,
    val todayIntake: Double,
    /** Recent-past coverage 0..1+ (uncapped, so severity can rank on it). */
    val recentCoverage: Double,
    /** Coverage so far today 0..1+ — drives the "progress so far today" bar. */
    val todayCoverage: Double,
    /** Chronic-deficiency likelihood 0..1 from [DeficiencyModel] — the rank key. */
    val likelihood: Double = 0.0,
    /** Length of the worst window (days) — "low for N days" hint. */
    val worstWindowDays: Int = 0,
    /** Average coverage in that worst window 0..1. */
    val worstWindowAvgCoverage: Double = 1.0
)

object FocusNowEngine {

    /** Recent-past window length (inclusive days) for the display average. */
    const val RECENT_DAYS = 7

    /**
     * Selects and ranks the focus-now nutrients. NO count cap (feedback
     * 2026-11-17): everyone whose [DeficiencyModel] likelihood clears the
     * reporting threshold is listed; the Home card scrolls internally.
     * Order: likelihood descending, then tier ascending, then least covered
     * today first, then id (deterministic).
     */
    fun select(inputs: List<FocusNowInput>): List<FocusNowItem> =
        inputs.asSequence()
            .filter { it.target > 0 }
            .map { input ->
                val assessment = DeficiencyModel.assess(
                    DeficiencyModel.Input(
                        nutrientId = input.nutrientId,
                        name = input.name,
                        tier = input.tier,
                        unit = input.unit,
                        target = input.target,
                        windows = input.windows,
                        todayCoverage = input.todayIntake / input.target
                    )
                )
                FocusNowItem(
                    nutrientId = input.nutrientId,
                    name = input.name,
                    tier = input.tier,
                    unit = input.unit,
                    target = input.target,
                    recentAvgIntake = input.recentAvgIntake,
                    todayIntake = input.todayIntake,
                    recentCoverage = input.recentAvgIntake / input.target,
                    todayCoverage = input.todayIntake / input.target,
                    likelihood = assessment.likelihood,
                    worstWindowDays = assessment.worstWindowDays,
                    worstWindowAvgCoverage = assessment.worstWindowAvgCoverage
                )
            }
            .filter { it.likelihood >= DeficiencyModel.REPORT_THRESHOLD }
            .sortedWith(
                compareByDescending<FocusNowItem> { it.likelihood }
                    .thenBy { it.tier }
                    .thenBy { it.todayCoverage }
                    .thenBy { it.nutrientId }
            )
            .toList()

    /**
     * Plain-language remaining amount, e.g. "420 mg left today".
     * Caller formats the number; this only picks the phrase.
     */
    fun remaining(todayIntake: Double, target: Double): Double =
        (target - todayIntake).coerceAtLeast(0.0)
}
