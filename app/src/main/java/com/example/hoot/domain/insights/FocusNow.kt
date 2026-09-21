package com.example.hoot.domain.insights

/**
 * "Focus now" selection (UI/UX overhaul feedback #4): nutrients that were
 * lacking in the RECENT PAST (trailing [recentDays]-day average below target)
 * AND are still lacking SO FAR TODAY — the actionable "what should I eat
 * today" answer.
 *
 * Pure JVM + unit-testable: the caller pre-aggregates the raw numbers, this
 * engine only decides which nutrients qualify and in which order. Excess
 * (limit-tracker) nutrients are deliberately excluded — you never "eat more"
 * of a cap.
 */

/** Per-nutrient inputs the selection needs (all pre-aggregated by the caller). */
data class FocusNowInput(
    val nutrientId: String,
    val name: String,
    val tier: Int,                        // 1 critical / 2 important / 3 nice-to-have
    val unit: String,
    /** Target-trackers only: RDA/AI/custom goal. Limit-trackers are never selected. */
    val target: Double,
    /** Trailing [FocusNowEngine.recentDays]-day average intake (already incl. today is fine). */
    val recentAvgIntake: Double,
    /** Intake so far today (0 when nothing logged yet). */
    val todayIntake: Double
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
    val todayCoverage: Double
)

object FocusNowEngine {

    /** Recent-past window length (inclusive days) for the average. */
    const val RECENT_DAYS = 7

    /** A nutrient counts as "lacking" below this share of its target. */
    const val LACKING_RATIO = 0.8

    /**
     * Selects and ranks the focus-now nutrients:
     *  - target-trackers only (limit-trackers are caps, not gaps),
     *  - recent 7-day average below [LACKING_RATIO]× target,
     *  - still below [LACKING_RATIO]× target so far today,
     *  - tier ascending (critical first), then lowest recent coverage.
     */
    fun select(
        inputs: List<FocusNowInput>,
        recentDays: Int = RECENT_DAYS,
        max: Int = 6
    ): List<FocusNowItem> =
        inputs.asSequence()
            .filter { it.target > 0 }
            .filter { it.recentAvgIntake / it.target < LACKING_RATIO }
            .filter { it.todayIntake / it.target < LACKING_RATIO }
            .map {
                FocusNowItem(
                    nutrientId = it.nutrientId,
                    name = it.name,
                    tier = it.tier,
                    unit = it.unit,
                    target = it.target,
                    recentAvgIntake = it.recentAvgIntake,
                    todayIntake = it.todayIntake,
                    recentCoverage = it.recentAvgIntake / it.target,
                    todayCoverage = it.todayIntake / it.target
                )
            }
            .sortedWith(compareBy({ it.tier }, { it.recentCoverage }))
            .take(max)
            .toList()

    /**
     * Plain-language remaining amount, e.g. "420 mg left today".
     * Caller formats the number; this only picks the phrase.
     */
    fun remaining(todayIntake: Double, target: Double): Double =
        (target - todayIntake).coerceAtLeast(0.0)
}
