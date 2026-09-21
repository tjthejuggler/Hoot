package com.example.hoot.domain.nutrition

import com.example.hoot.data.local.entity.TailEntryEntity

/**
 * Water aggregation over `tail_entries` rows (kind = "water") — the THIRD
 * ledger source beside meals and supplements.
 *
 * Bug this fixes: Home showed "0 L" and a 0 % water 7-day average although the
 * user logs water daily, because [IntakeAggregator] only walked meals +
 * supplements; both Tail-synced water-habit entries and in-app quick-adds
 * lived solely in `tail_entries` and never reached `nutrient_intake_log`.
 *
 * Amounts are stored at ingest time already ml-normalized: `parseWaterAmount`
 * ("500 ml" → 500 "ml", "1.5 l" → 1500 "ml") for Tail rows and the quick-add
 * path (ml) for local rows. Unitless rows ("2", "3 glasses") carry no unit and
 * are counted as standard 250 ml glasses. Rows without any number ("drank
 * water") contribute nothing rather than an invented volume.
 *
 * Pure JVM — unit-tested in `WaterIntakeTest.kt`.
 */
object WaterIntake {

    /** [TailEntryEntity.kind] value for water rows (Tail habit + local quick-add). */
    const val KIND_WATER = "water"

    /** Milliliters per unitless water count (a standard glass). */
    const val ML_PER_GLASS = 250.0

    /** Total water carried by [entries], in liters (the canonical "water" unit). */
    fun liters(entries: List<TailEntryEntity>): Double = entries
        .filter { it.kind == KIND_WATER }
        .sumOf { entry ->
            val amount = entry.amount ?: return@sumOf 0.0
            when (entry.unit?.lowercase()) {
                "l" -> amount * 1000.0
                "ml" -> amount
                else -> amount * ML_PER_GLASS   // null/unknown unit → glass counter
            }
        } / 1000.0
}
