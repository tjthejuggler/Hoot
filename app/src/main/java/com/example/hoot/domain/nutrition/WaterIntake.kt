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
 * path (ml) for local rows.
 *
 * Bare-number interpretation (feedback 2026-09): Tail logs raw ml values
 * ("2500" meaning 2.5 L), which the old code counted as 250 ml glasses — a
 * 10× undercount. [WaterUnitMode] lets the user declare what a bare number
 * means (chosen at Tail-setup when mapping the water habit); "auto" keeps a
 * heuristic: values ≥ 100 are ml, smaller values are glasses. Explicit units
 * in the text ("500 ml", "1.5 l", "16 oz") always win over the mode.
 *
 * Rows without any number ("drank water") contribute nothing rather than an
 * invented volume. Pure JVM — unit-tested in `WaterIntakeTest.kt`.
 */
object WaterIntake {

    /** [TailEntryEntity.kind] value for water rows (Tail habit + local quick-add). */
    const val KIND_WATER = "water"

    /** Milliliters per unitless water count under the legacy glass assumption. */
    const val ML_PER_GLASS = 250.0

    /** Milliliters per US fluid ounce (parse normalizes oz here). */
    const val ML_PER_OZ = 29.5735

    /** Valid values of [com.example.hoot.data.local.AppSettings.waterUnitMode]. */
    val UNIT_MODES = listOf("auto", "ml", "l", "oz", "glass")

    /** Total water carried by [entries], in liters (the canonical "water" unit). */
    fun liters(
        entries: List<TailEntryEntity>,
        unitMode: String = "auto"
    ): Double = entries
        .filter { it.kind == KIND_WATER }
        .sumOf { entry ->
            val amount = entry.amount ?: return@sumOf 0.0
            when (entry.unit?.lowercase()) {
                "l" -> amount * 1000.0
                "ml" -> amount
                "oz" -> amount * ML_PER_OZ
                null -> bareNumberMl(amount, unitMode)   // no unit → mode decides
                else -> amount                           // "ml" variants pass through
            }
        } / 1000.0

    /**
     * A parseable number the text carried WITHOUT a unit ("2500", "2 glasses").
     * [unitMode] decides the meaning; "auto" is the setup-free heuristic.
     */
    private fun bareNumberMl(amount: Double, unitMode: String): Double = when (unitMode) {
        "ml" -> amount
        "l" -> amount * 1000.0
        "oz" -> amount * ML_PER_OZ
        "glass" -> amount * ML_PER_GLASS
        else -> if (amount >= 100) amount else amount * ML_PER_GLASS   // auto
    }
}

/**
 * Water-habit text → (amount, normalized unit) when the text contains a
 * parseable number: "500 ml" → (500, "ml"), "1.5 l" → (1500, "ml"),
 * "2.5" → (2.5, null), "16 oz" → (16, "oz"). Returns null for no-number
 * texts ("drank water").
 *
 * Amount is returned in the TEXT's own unit (not ml-normalized) — the unit
 * string travels with it so [WaterIntake.liters] can convert. (Bug fix
 * 2026-09: "16 oz" was previously labeled "ml" and summed as 16 ml.)
 * Numbers ending in a bare "." ("2500.") normalize to unitless.
 */
internal fun parseWaterAmount(text: String): Pair<Double, String?>? {
    val trimmed = text.trim()
    val m = Regex("""(\d+(?:[.,]\d+)?)\s*(ml|milliliters?|l|liters?|oz|fl\s*oz)?\.?""", RegexOption.IGNORE_CASE)
        .find(trimmed) ?: return null
    val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
    return when (m.groupValues[2].lowercase()
        .replace("milliliters", "ml").replace("milliliter", "ml")
        .replace("liters", "l").replace("liter", "l")
        .replace(" ", "")) {
        "l" -> value to "l"
        "" -> value to null
        "oz", "floz" -> value to "oz"
        else -> value to "ml"   // "ml" pass-through
    }
}
