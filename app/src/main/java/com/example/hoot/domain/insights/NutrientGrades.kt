package com.example.hoot.domain.insights

/**
 * School-style letter grading for EVERY nutrient over a time window
 * (feedback 2026-09-23: "unclear what high/watch/info colors mean"):
 *
 *   A — consistently at/inside the target (the proud list, hidden behind
 *       "show more")
 *   B — close, minor drift
 *   C — noticeably off, worth attention
 *   D — persistent meaningful shortfall / regular exceedance
 *   F — severe or untracked: far off target, or NO data logged at all
 *       (an unknown status must never masquerade as an A)
 *
 * Direction-agnostic BY DESIGN: the grade encodes how GOOD the situation is,
 * whether the problem is "too low" (RDA shortfall) or "too high" (cap/UL
 * exceedance). Pure JVM + unit-testable — the caller pre-aggregates the
 * per-day coverage ratios from the intake ledger, exactly like
 * [InsightsEngine.analyze].
 */

/** Letter grades, worst-first ordering used throughout the UI. */
enum class Grade { F, D, C, B, A }

/** One nutrient's graded report row for the Insights report card. */
data class NutrientGradeRow(
    val nutrientId: String,
    val name: String,
    val tier: Int,
    val unit: String,
    val grade: Grade,
    /** True when the problem is "too much" (limit-tracker / over UL). */
    val isExcess: Boolean,
    /** Average coverage vs target across days WITH data, 0..∞ (limit-trackers: 1 under cap → 0). */
    val avgCoverage: Double,
    /** Days in the window that had any logged intake for this nutrient. */
    val daysWithData: Int,
    /** Total days in the window — daysWithData < windowDays ⇒ untracked days. */
    val windowDays: Int,
    /** Days below 80% of target (target-trackers) or under-cap coverage (limit-trackers). */
    val badDays: Int,
    /** True when the nutrient has a scoreable target (target/cap > 0). */
    val isScoreable: Boolean
) {
    /** Days in the window with no ledger entry — the "unknown" fraction. */
    val untrackedDays: Int get() = windowDays - daysWithData
}

object NutrientGrades {

    /** Day-level bad-day threshold (share of target) — mirrors FocusNowEngine.LACKING_RATIO. */
    const val BAD_DAY_RATIO = 0.8

    /** Per-day penalty for a target-tracker: 0 at the 80% line, 1 at zero intake. */
    private fun dayPenalty(coverage: Double, isExcess: Boolean): Double = when {
        isExcess -> (1.0 - coverage).coerceIn(0.0, 1.0)   // coverage 1 = under cap
        else -> ((BAD_DAY_RATIO - coverage) / BAD_DAY_RATIO).coerceIn(0.0, 1.0)
    }

    /**
     * Grades one nutrient from its per-day coverage series.
     *
     * @param coverages per-day coverage (intake/target; limit-trackers: decayed
     *   cap coverage 0..1 as in ScoreEngine/Home), only for days WITH data.
     * @param windowDays total days in the window (inclusive) — days absent
     *   from [coverages] count as UNTRACKED, which pulls the grade DOWN (an
     *   untracked nutrient cannot earn an A; fully untracked = F).
     */
    fun grade(
        nutrientId: String,
        name: String,
        tier: Int,
        unit: String,
        isExcess: Boolean,
        coverages: List<Double>,
        windowDays: Int,
        isScoreable: Boolean
    ): NutrientGradeRow {
        val daysWithData = coverages.size
        val badDays = coverages.count { it < BAD_DAY_RATIO }
        val avg = if (daysWithData > 0) coverages.average() else 0.0
        val g = when {
            !isScoreable -> Grade.C                        // display-only rows: neutral "–" in UI
            daysWithData == 0 -> Grade.F                   // never logged = unknown = F
            // Severe persistent state: EVERY tracked day bad AND far off target.
            daysWithData > 0 && badDays == daysWithData && avg <= 0.3 -> Grade.F
            // Persistent meaningful state: every tracked day bad, moderately off.
            daysWithData > 0 && badDays == daysWithData && avg <= 0.6 -> Grade.D
            else -> {
                val untracked = (windowDays - daysWithData).coerceAtLeast(0)
                val untrackedFrac =
                    if (windowDays > 0) (untracked.toDouble() / windowDays).coerceIn(0.0, 1.0)
                    else 0.0
                val daySeverity = coverages.map { dayPenalty(it, isExcess) }.average()
                val depth = (1.0 - avg).coerceIn(0.0, 1.0)
                // 55% day-to-day shortfall, 20% depth of the average, 25% data gaps.
                val severity = (0.55 * daySeverity + 0.20 * depth + 0.25 * untrackedFrac)
                    .coerceIn(0.0, 1.0)
                when {
                    severity <= 0.05 -> Grade.A
                    severity <= 0.30 -> Grade.B
                    severity <= 0.55 -> Grade.C
                    severity <= 0.85 -> Grade.D
                    else -> Grade.F
                }
            }
        }
        return NutrientGradeRow(
            nutrientId = nutrientId, name = name, tier = tier, unit = unit,
            grade = g, isExcess = isExcess, avgCoverage = avg,
            daysWithData = daysWithData, windowDays = windowDays,
            badDays = badDays, isScoreable = isScoreable
        )
    }

    /** Worst-first, then tier ascending, then name — the report card order. */
    val ROW_ORDER: Comparator<NutrientGradeRow> = compareBy(
        { it.grade.ordinal }, { it.tier }, { it.name }
    )
}
