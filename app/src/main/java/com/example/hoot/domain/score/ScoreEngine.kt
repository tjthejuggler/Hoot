package com.example.hoot.domain.score

import com.example.hoot.data.local.entity.ScoreSnapshotEntity
import kotlin.math.max
import kotlin.math.min

/**
 * Daily 0–100 score (docs/ARCHITECTURE.md §6) — pure JVM, unit-testable:
 *
 *   score = W_COMPLETENESS·completeness + W_DEFICIENCY·(1 − penalty)
 *         + W_ADHERENCE·adherence          (all terms 0..1, ×100)
 *
 *  (a) completeness — tier-weighted mean of per-nutrient coverage
 *      (Tier 1 = ×3, Tier 2 = ×2, Tier 3 = ×1), intake/target capped at 1;
 *      limit-trackers (sodium, added sugar, sat-/trans-fat) are scored
 *      against their cap instead: coverage = 1 under the cap, decaying
 *      linearly to 0 at [LIMIT_DECAY_FACTOR]× cap (docs/NUTRIENTS.md §7.7).
 *  (b) deficiencyPenalty — extra deductions for Tier-1 nutrients below 50 %
 *      of target, amplified by consecutive low-day streaks, plus tier-scaled
 *      penalties for UL exceedances.
 *  (c) adherence — share of previously issued recommendations actually eaten
 *      (trailing 7 days); dismissed count mildly against, none issued = 1.
 *  (d) well-rounded bonus — every scored Tier-1 ≥ 90 % and no UL
 *      exceedances keeps the score at or above [WELL_ROUNDED_FLOOR].
 */

/** UI-facing status of one nutrient's daily coverage. */
enum class ScoreStatus { MET, CLOSE, LOW, EXCESS }

/** One scored nutrient for a single day. */
data class NutrientScoreInput(
    val nutrientId: String,
    val name: String,
    val tier: Int,                       // 1 critical / 2 important / 3 nice-to-have
    val unit: String,                    // canonical unit
    val intake: Double,                  // canonical unit, summed for the day
    /** Target-trackers: RDA/AI/custom goal. Limit-trackers: the practical cap. 0 = not scored. */
    val target: Double,
    /** Tolerable upper limit (null = none / not applicable / supplemental-only). */
    val ul: Double? = null,
    val isLimitTracker: Boolean = false,
    /** Consecutive prior days below 50 % of target — drives the Tier-1 streak penalty. */
    val lowStreakDays: Int = 0
)

/** Recommendation-following stats over the trailing window (ARCHITECTURE.md §6.3). */
data class AdherenceSummary(
    val issued: Int = 0,
    val accepted: Int = 0,
    val dismissed: Int = 0
) {
    /**
     * Share of recommendations actually eaten. Dismissed count mildly against
     * (−0.25 each); nothing issued → vacuously 1.0 (no penalty).
     */
    val ratio: Double
        get() {
            if (issued <= 0) return 1.0
            return ((accepted - 0.25 * dismissed) / issued).coerceIn(0.0, 1.0)
        }
}

/** Per-nutrient breakdown row for UI display. */
data class ScoreComponent(
    val nutrientId: String,
    val name: String,
    val tier: Int,
    val unit: String,
    val intake: Double,
    val target: Double,
    val coverage: Double,                // 0..1 (limit-trackers: 1 under cap → 0 at 1.5× cap)
    val isLimitTracker: Boolean,
    val ulExceeded: Boolean,
    val status: ScoreStatus
)

/** Full result of [ScoreEngine.score]. */
data class ScoreBreakdown(
    val score: Double,                   // 0..100
    val completeness: Double,            // 0..100
    val deficiencyPenalty: Double,       // 0..1 (deficits + UL exceedances)
    val adherence: Double,               // 0..1
    val wellRounded: Boolean,
    val ulExceedances: Int,
    val nutrientsMet: Int,
    val nutrientsTracked: Int,
    val components: List<ScoreComponent>
)

object ScoreEngine {

    // Component weights (ARCHITECTURE.md §6).
    const val W_COMPLETENESS = 0.45
    const val W_DEFICIENCY = 0.35
    const val W_ADHERENCE = 0.20

    const val WELL_ROUNDED_FLOOR = 85.0
    const val WELL_ROUNDED_MIN_COVERAGE = 0.90

    /** Limit-tracker coverage reaches 0 at this multiple of the cap. */
    const val LIMIT_DECAY_FACTOR = 1.5

    /** Deficiency penalty kicks in below this coverage (Tier-1 only). */
    const val DEFICIT_THRESHOLD = 0.5

    /** Streak amplification caps out after this many consecutive low days. */
    const val MAX_STREAK_LOOKBACK = 6

    /** Tier → scoring weight (docs/NUTRIENTS.md §5). */
    fun tierWeight(tier: Int): Double = when (tier) {
        1 -> 3.0
        2 -> 2.0
        else -> 1.0
    }

    /** Coverage for one input (0..1). */
    fun coverage(input: NutrientScoreInput): Double = when {
        input.isLimitTracker && input.target > 0 -> {
            val over = input.intake - input.target
            if (over <= 0) 1.0
            else (1.0 - over / (input.target * (LIMIT_DECAY_FACTOR - 1.0))).coerceIn(0.0, 1.0)
        }
        input.target > 0 -> min(1.0, input.intake / input.target)
        else -> 0.0
    }

    /** Computes the full daily breakdown from per-nutrient inputs + adherence. */
    fun score(inputs: List<NutrientScoreInput>, adherence: AdherenceSummary): ScoreBreakdown {
        val scored = inputs.filter { it.target > 0 }
        if (scored.isEmpty()) {
            return ScoreBreakdown(0.0, 0.0, 0.0, adherence.ratio, false, 0, 0, 0, emptyList())
        }

        var weightSum = 0.0
        var weighted = 0.0
        var tier1Scored = 0
        var deficits = 0.0
        var excess = 0.0
        var exceedances = 0
        var met = 0
        val components = ArrayList<ScoreComponent>(scored.size)

        for (input in scored) {
            val cov = coverage(input)
            val w = tierWeight(input.tier)
            weightSum += w
            weighted += w * cov

            val exceeded = input.ul != null && input.intake > input.ul
            if (exceeded) exceedances++
            if (!input.isLimitTracker && cov >= 1.0) met++

            // (b) Tier-1 shortfall below 50 % of target, amplified by streaks.
            if (!input.isLimitTracker && input.tier == 1 && cov < DEFICIT_THRESHOLD) {
                val base = (DEFICIT_THRESHOLD - cov) / DEFICIT_THRESHOLD        // 0..1
                val streak = min(input.lowStreakDays, MAX_STREAK_LOOKBACK)
                val factor = 1.0 + 0.25 * max(0, streak - 1)                    // ≤ 2×
                deficits += base * factor
            }
            if (input.tier == 1) tier1Scored++

            // UL exceedance — tier-scaled portion of the penalty.
            val ul = input.ul
            if (ul != null && ul > 0 && input.intake > ul) {
                val ratio = min(1.0, (input.intake - ul) / ul)
                val tierMul = when (input.tier) { 1 -> 1.0; 2 -> 0.6; else -> 0.3 }
                excess += ratio * 0.5 * tierMul
            }

            components += ScoreComponent(
                nutrientId = input.nutrientId,
                name = input.name,
                tier = input.tier,
                unit = input.unit,
                intake = input.intake,
                target = input.target,
                coverage = cov,
                isLimitTracker = input.isLimitTracker,
                ulExceeded = exceeded,
                status = statusOf(input, cov)
            )
        }

        val completeness = if (weightSum > 0) 100.0 * weighted / weightSum else 0.0
        val deficitScore = min(1.0, deficits / max(1, tier1Scored))
        val excessScore = min(1.0, excess)
        val penalty = min(1.0, deficitScore + excessScore)
        val adh = adherence.ratio

        var score = 100.0 * (
            W_COMPLETENESS * completeness / 100.0 +
                W_DEFICIENCY * (1.0 - penalty) +
                W_ADHERENCE * adh
            )

        // (d) well-rounded: every scored Tier-1 ≥ 90 % and zero UL exceedances.
        val tier1 = scored.filter { !it.isLimitTracker && it.tier == 1 }
        val wellRounded = tier1.isNotEmpty() &&
            tier1.all { coverage(it) >= WELL_ROUNDED_MIN_COVERAGE } &&
            exceedances == 0
        if (wellRounded) score = max(score, WELL_ROUNDED_FLOOR)

        return ScoreBreakdown(
            score = score.coerceIn(0.0, 100.0),
            completeness = completeness.coerceIn(0.0, 100.0),
            deficiencyPenalty = penalty,
            adherence = adh,
            wellRounded = wellRounded,
            ulExceedances = exceedances,
            nutrientsMet = met,
            nutrientsTracked = scored.size,
            components = components
        )
    }

    private fun statusOf(input: NutrientScoreInput, cov: Double): ScoreStatus = when {
        input.ul != null && input.intake > input.ul -> ScoreStatus.EXCESS
        input.isLimitTracker -> if (cov >= 1.0) ScoreStatus.MET else ScoreStatus.EXCESS
        cov >= 1.0 -> ScoreStatus.MET
        cov >= 0.75 -> ScoreStatus.CLOSE
        else -> ScoreStatus.LOW
    }
}

/** Maps a breakdown onto the persisted daily row (PK = day → idempotent upsert). */
fun ScoreBreakdown.toSnapshot(day: String): ScoreSnapshotEntity = ScoreSnapshotEntity(
    day = day,
    score = score,
    completeness = completeness,
    deficiencyPenalty = deficiencyPenalty,
    adherence = adherence,
    nutrientsMet = nutrientsMet,
    nutrientsTracked = nutrientsTracked
)
