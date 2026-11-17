package com.example.hoot.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.example.hoot.data.local.entity.NutrientDefinitionEntity
import com.example.hoot.domain.nutrition.DayKeys
import com.example.hoot.domain.score.ScoreStatus
import java.time.LocalDate
import java.time.ZoneId

/** Shared UI-level helpers for the phase-4 screens. */

/**
 * Fling containment for fixed-height scrollable sections nested inside the
 * main screen scroll (feedback 2026-11-17): when a fling inside the section
 * reaches its top/bottom edge, the REMAINING velocity is consumed here
 * instead of chaining to the parent list — a hard fling stops dead at the
 * section boundary rather than yanking the whole dashboard.
 *
 * Deliberate SLOW drags still chain (standard nested-scroll behaviour), so
 * the user can keep pulling the main screen past a section edge; only the
 * leftover FLING momentum is killed.
 *
 * Attach BEFORE the scroll modifier:
 * `Modifier.killParentFling().verticalScroll(state)`.
 *
 * Protocol: onPostFling runs AFTER the inner scroller finished flinging —
 * `available` is exactly the velocity it could not use (it hit an edge).
 * Returning it reports it consumed, so the parent list never flings with it.
 */
fun Modifier.killParentFling(): Modifier = nestedScroll(object : NestedScrollConnection {
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        available // swallow the leftover — do not pass it to the parent list
})

/**
 * Uniform height of every scrollable Home subsection (feedback 2026-11-17):
 * the symptoms list, Focus-now and Today's-nutrients all cap/hold the SAME
 * fixed inner-scroll height so the dashboard sections line up visually.
 * Attach as `.height(SUBSECTION_SCROLL_HEIGHT)` on the inner scrollable.
 */
val SUBSECTION_SCROLL_HEIGHT = 300.dp

/**
 * Today's local day key ("yyyy-MM-dd", Tail convention). Delegates to the
 * single [DayKeys] helper — the same key source used by sync ingest, the
 * intake ledger and score snapshots (day-key mismatch bug fix).
 */
fun todayKey(zone: ZoneId = ZoneId.systemDefault()): String =
    DayKeys.todayKey(zone)

fun epochToDayKey(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DayKeys.fromEpoch(ms, zone)

fun dayKeyMinusDays(day: String, days: Long): String =
    DayKeys.minusDays(day, days)

fun dayKeyPlusDays(day: String, days: Long): String =
    DayKeys.plusDays(day, days)

/** "2026-09-19" → "Fri, Sep 19" (locale-stable fallback on parse failure). */
fun prettyDay(day: String): String = runCatching {
    LocalDate.parse(day).let {
        it.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase).take(3) + ", " +
            it.month.name.lowercase().replaceFirstChar(Char::uppercase).take(3) + " " + it.dayOfMonth
    }
}.getOrElse { day }

/** Window length in days (inclusive). */
fun windowDays(from: String, to: String): Long =
    runCatching { LocalDate.parse(from).datesUntil(LocalDate.parse(to)).count() + 1 }.getOrElse { 1L }

/** Canonical nutrient display: whole numbers for g/kcal, 1 decimal otherwise.
 *  Water (L) keeps 1 decimal — truncating 2.1 L to "2 L" hid the actual total. */
fun formatNutrient(amount: Double, unit: String): String = when (unit) {
    "L" -> "%.1f L".format(amount)
    "g", "kcal" -> "%s %s".format(amount.toInt(), unit)
    else -> "%.1f %s".format(amount, unit)
}

/** %RDA display helper. */
fun percentOf(target: Double, intake: Double): Int =
    if (target <= 0) 0 else (intake / target * 100.0).toInt()

/** Deterministic tasteful food emoji fallback (used when no image URL exists). */
fun foodEmoji(name: String): String {
    val n = name.lowercase()
    val table: List<Pair<List<String>, String>> = listOf(
        listOf("salmon", "tuna", "sardine", "mackerel", "fish") to "🐟",
        listOf("egg") to "🥚",
        listOf("spinach", "kale", "lettuce", "greens", "salad") to "🥬",
        listOf("carrot") to "🥕",
        listOf("avocado") to "🥑",
        listOf("banana") to "🍌",
        listOf("apple") to "🍎",
        listOf("orange", "citrus", "lemon", "lime") to "🍊",
        listOf("berry", "strawberry", "blueberry", "raspberry") to "🫐",
        listOf("broccoli") to "🥦",
        listOf("potato", "sweet potato") to "🥔",
        listOf("bread", "toast", "oat", "wheat", "cereal") to "🍞",
        listOf("rice", "pasta", "noodle") to "🍚",
        listOf("bean", "lentil", "chickpea", "pea") to "🫘",
        listOf("milk", "yogurt", "kefir") to "🥛",
        listOf("cheese") to "🧀",
        listOf("nut", "almond", "walnut", "cashew", "seed") to "🥜",
        listOf("beef", "steak", "lamb", "pork", "meat") to "🥩",
        listOf("chicken", "turkey", "poultry") to "🍗",
        listOf("mushroom") to "🍄",
        listOf("pepper") to "🫑",
        listOf("chocolate", "cocoa") to "🍫",
        listOf("water") to "💧",
        listOf("oil", "olive") to "🫒",
        listOf("corn") to "🌽",
        listOf("tomato") to "🍅",
        listOf("tofu", "soy") to "🍲"
    )
    table.forEach { (keys, emoji) -> if (keys.any { n.contains(it) }) return emoji }
    return "🍽️"
}

/** Status → color role for coverage bars/chips (maps to theme accents). */
sealed class CoverageTone {
    data object Good : CoverageTone()
    data object Close : CoverageTone()
    data object Low : CoverageTone()
    data object Excess : CoverageTone()
}

fun toneFor(status: ScoreStatus): CoverageTone = when (status) {
    ScoreStatus.MET -> CoverageTone.Good
    ScoreStatus.CLOSE -> CoverageTone.Close
    ScoreStatus.LOW -> CoverageTone.Low
    ScoreStatus.EXCESS -> CoverageTone.Excess
}

/** Definition + effective target convenience (goal > RDA; cap for limit-trackers). */
data class NutrientRowModel(
    val definition: NutrientDefinitionEntity,
    val effectiveTarget: Double,
    val isLimitTracker: Boolean
)

val LIMIT_TRACKER_IDS = setOf("added_sugar", "saturated_fat", "trans_fat", "sodium")

fun effectiveTarget(def: NutrientDefinitionEntity, goalTarget: Double?): Double {
    if (def.id in LIMIT_TRACKER_IDS) return goalTarget ?: def.ulValue ?: def.rdaValue ?: 0.0
    return goalTarget ?: def.rdaValue ?: 0.0
}

/** "2026-09-15" → "9/15" — compact chart axis tick. */
fun shortDayLabel(day: String): String = runCatching {
    LocalDate.parse(day).let { "${it.monthValue}/${it.dayOfMonth}" }
}.getOrElse { day.takeLast(5) }

/** Compose [androidx.compose.material3.DatePicker] millis (UTC midnight) → day key. */
fun datePickerMillisToDayKey(utcMillis: Long): String =
    DayKeys.fromEpoch(utcMillis, ZoneId.of("UTC"))

/** Day key → [androidx.compose.material3.DatePicker] millis (UTC midnight of that date). */
fun dayKeyToDatePickerMillis(day: String): Long = runCatching {
    LocalDate.parse(day).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
}.getOrDefault(0L)
