package com.example.hoot.domain.score

import android.util.Log
import com.example.hoot.data.local.entity.ScoreSnapshotEntity
import com.example.hoot.data.repository.MealRepository
import com.example.hoot.data.repository.NutrientRepository
import com.example.hoot.data.repository.TailConfigRepository
import java.time.LocalDate

/**
 * Orchestrates the daily score pipeline (phase 4): repository reads →
 * [ScoreEngine] → idempotent [ScoreSnapshotEntity] upsert (PK = day, exactly
 * like IntakeAggregator's per-day ledger recompute). Runs after the intake
 * ledger changes — see AppGraph's observer on NutritionProcessor.
 */
class ScoreSnapshotter(
    private val nutrients: NutrientRepository,
    private val meals: MealRepository,
    private val tailConfig: TailConfigRepository
) {

    /**
     * Recomputes (and persists) the score for [day]. Idempotent: safe to run
     * repeatedly for the same day; the row is replaced wholesale.
     */
    suspend fun recomputeDay(day: String): ScoreSnapshotEntity? {
        val diet = tailConfig.dietaryProfile()
        if (diet?.excludeFromScoring == true) {
            Log.d(TAG, "scoring excluded by dietary profile — leaving $day untouched")
            return nutrients.snapshot(day)
        }

        val definitions = nutrients.definitionsAll()
        val goals = nutrients.goalsAll().associateBy { it.nutrientId }
        val totals = nutrients.dailyTotals(day).associate { it.nutrientId to it.total }

        val inputs = ArrayList<NutrientScoreInput>(definitions.size)
        for (def in definitions) {
            val goal = goals[def.id]
            val tier = goal?.priority ?: def.tier
            val isLimit = def.id in LIMIT_TRACKERS

            // Limit-trackers score against the cap (goal > UL > practical cap);
            // target-trackers score against goal > RDA.
            val cap = goal?.targetValue ?: def.ulValue ?: def.rdaValue ?: 0.0
            val target = if (isLimit) cap else (goal?.targetValue ?: def.rdaValue ?: 0.0)
            val intake = totals[def.id] ?: 0.0

            // Supplemental-only ULs (magnesium) must not penalize food intake.
            val ul = if (isLimit || def.id in SUPPLEMENTAL_ONLY_UL) null else def.ulValue

            var streak = 0
            if (!isLimit && target > 0 && tier == 1 &&
                intake < target * ScoreEngine.DEFICIT_THRESHOLD
            ) {
                streak = lowStreakBefore(day, def.id, target)
            }

            inputs += NutrientScoreInput(
                nutrientId = def.id,
                name = def.name,
                tier = tier,
                unit = def.unit,
                intake = intake,
                target = if (target > 0) target else 0.0,
                ul = ul?.takeIf { it > 0 },
                isLimitTracker = isLimit,
                lowStreakDays = streak
            )
        }

        // (c) adherence — recommendations issued in the trailing 7 days.
        val from = runCatching { LocalDate.parse(day).minusDays(6).toString() }.getOrElse { day }
        val recs = nutrients.recommendationsBetween(from, day)
        val adherence = AdherenceSummary(
            issued = recs.size,
            accepted = recs.count { it.accepted == true },
            dismissed = recs.count { it.accepted == false }
        )

        val breakdown = ScoreEngine.score(inputs, adherence)
        val snapshot = breakdown.toSnapshot(day)
        nutrients.upsertSnapshot(snapshot)
        Log.d(
            TAG,
            "snapshot($day): score=%.1f comp=%.1f pen=%.2f adh=%.2f met=%d/%d".format(
                breakdown.score, breakdown.completeness, breakdown.deficiencyPenalty,
                breakdown.adherence, breakdown.nutrientsMet, breakdown.nutrientsTracked
            )
        )
        return snapshot
    }

    /** Backfills every known day (meal days ∪ supplement days); idempotent. */
    suspend fun recomputeAll(): Int {
        val days = (meals.distinctMealDays() + meals.distinctSupplementDays())
            .distinct()
            .sorted()
        for (day in days) {
            runCatching { recomputeDay(day) }
                .onFailure { Log.e(TAG, "snapshot($day) failed", it) }
        }
        Log.i(TAG, "recomputed %d score snapshots".format(days.size))
        return days.size
    }

    /** Consecutive days before [day] where the nutrient stayed below 50 % of target. */
    private suspend fun lowStreakBefore(day: String, nutrientId: String, target: Double): Int {
        var cursor = runCatching { LocalDate.parse(day) }.getOrElse { return 0 }
        var streak = 0
        repeat(ScoreEngine.MAX_STREAK_LOOKBACK) {
            cursor = cursor.minusDays(1)
            val total = nutrients.dailyTotals(cursor.toString())
                .firstOrNull { it.nutrientId == nutrientId }?.total ?: 0.0
            if (total < target * ScoreEngine.DEFICIT_THRESHOLD) streak++ else return streak
        }
        return streak
    }

    companion object {
        private const val TAG = "HootScore"

        /** NUTRIENTS.md §7.7 — aggregated but scored against a cap, not a target. */
        val LIMIT_TRACKERS = setOf("added_sugar", "saturated_fat", "trans_fat", "sodium")

        /** ULs that apply to supplemental form only (NUTRIENTS.md §3). */
        private val SUPPLEMENTAL_ONLY_UL = setOf("magnesium")
    }
}
