package com.example.hoot.domain.insights

import com.example.hoot.data.local.entity.NutrientGoalEntity
import com.example.hoot.data.local.entity.ScoreSnapshotEntity
import kotlin.math.abs

/**
 * Rule-based insight generation over a time window (phase 4, ARCHITECTURE.md
 * §6–7): persistent Tier-1/2 deficiencies, over-limit nutrients, improving/
 * declining score trends vs the previous window, streaks and "what to eat"
 * gaps. Pure JVM — the input is a plain [WindowData] snapshot, so the whole
 * analysis is unit-testable without Android or a database.
 */

enum class InsightKind {
    DEFICIENCY,      // persistent shortfall on a tracked nutrient
    EXCESS,          // over cap / UL
    TREND_UP,        // score improving vs previous window
    TREND_DOWN,      // score declining vs previous window
    STREAK,          // consecutive good/bad-score days
    GAP,             // "what to eat" — specific foods for a gap
    COACH_NOTE       // LLM (or template fallback) summary
}

enum class InsightSeverity { INFO, WARNING, CRITICAL }

/** One generated insight card. */
data class Insight(
    val kind: InsightKind,
    val severity: InsightSeverity,
    val title: String,
    val message: String,
    val nutrientId: String? = null
)

/** Inputs for [InsightsEngine.analyze] — everything pre-fetched by the caller. */
data class WindowData(
    val from: String,                        // "yyyy-MM-dd"
    val to: String,
    /** (nutrientId, day) → intake total for the window (days with no data absent). */
    val intakeByDay: Map<Pair<String, String>, Double>,
    val definitions: Map<String, NutrientInsightDef>,
    val goals: Map<String, NutrientGoalEntity> = emptyMap(),
    val scoreSnapshots: List<ScoreSnapshotEntity> = emptyList(),
    /** Score snapshots of the equally-sized preceding window (may be empty). */
    val previousScoreSnapshots: List<ScoreSnapshotEntity> = emptyList(),
    /**
     * User dietary restrictions from Settings (null = omnivore/no restrictions).
     * Food mentions in generated messages are filtered against it.
     */
    val dietFilter: DietTextFilter? = null
)

/**
 * Diet-restriction carrier shared by the Insights "Good sources" /
 * "Foods high in X" cards, Home's "What stands out" cards and the Today
 * dashboard. ALL matching logic (exclusion keyword sets, word-boundary
 * matching, plant-phrase overrides, allergy synonyms) lives in
 * [DietRules]; curated per-nutrient alternatives for diet-emptied seed
 * lines live in [DietAwareSources]. Pure JVM — unit-testable.
 */
data class DietTextFilter(
    val dietStyle: String = "omnivore",
    val allergies: List<String> = emptyList(),
    val dislikes: List<String> = emptyList()
) {
    /** True when a food mention survives the user's restrictions. */
    fun allows(food: String): Boolean =
        DietRules.allowsFood(food, dietStyle, allergies, dislikes)

    /** [DietProfile] view for the [DietAwareSources] sanitizers. */
    fun toProfile(): DietProfile = DietProfile(dietStyle, allergies, dislikes)

    /**
     * Filters a comma/and-separated food list, keeping the sentence readable.
     * When the diet removes EVERY source, curated diet-appropriate
     * alternatives for [nutrientId] are substituted (or a fallback phrase).
     */
    fun filterList(sources: String, nutrientId: String? = null): String =
        DietAwareSources.filterLine(sources, DietProfile(dietStyle, allergies, dislikes), nutrientId)

    companion object {
        /**
         * Safety-biased merge of the two persisted diet sources (diet-fix
         * hardening, 2026-09): Hoot stores the profile in DataStore (what the
         * Settings UI edits/shows) AND mirrors it into the Room
         * `dietary_profile` row. A failed/missing mirror previously made the
         * insights engines silently filter as OMNIVORE while the user saw
         * "vegan" in Settings — the exact "still-leaking" bug. Merge rule:
         *  - style: the RESTRICTED one wins when the sources disagree (a
         *    vegan never sees omnivore text); unrestricted DataStore beats
         *    unrestricted Room trivially.
         *  - allergies/dislikes: UNION of both stores (over-filtering a
         *    removed chip shows alternatives — safe; under-filtering leaks).
         */
        fun merged(
            datastoreStyle: String?,
            datastoreAllergies: Collection<String>,
            datastoreDislikes: Collection<String>,
            roomStyle: String?,
            roomAllergies: Collection<String>,
            roomDislikes: Collection<String>
        ): DietTextFilter {
            fun restricted(style: String?): Boolean {
                val s = style?.trim()?.lowercase().orEmpty()
                return s.isNotEmpty() && s != "omnivore" && s != "none"
            }
            // diet-leak self-healing (2026-09, on-device verified root cause):
            // older builds persisted "Vegan"/"Vegetarian"/"Pescatarian" as
            // ALLERGY chips while diet_style stayed "omnivore" (the Save
            // button passed ""). No exclusion keyword matches those words, so
            // every filter silently ran as OMNIVORE. Promote such a chip to
            // the effective style whenever neither store has a restricted
            // style — this heals already-corrupted persisted state with no
            // user action, in addition to the fixed Save path.
            val allergyChipStyle = (datastoreAllergies + roomAllergies)
                .map { it.trim().lowercase() }
                .firstOrNull { it in setOf("vegan", "vegetarian", "pescatarian") }
            val style = when {
                restricted(datastoreStyle) -> datastoreStyle!!.trim()
                restricted(roomStyle) -> roomStyle!!.trim()
                allergyChipStyle != null -> allergyChipStyle
                else -> (datastoreStyle ?: roomStyle ?: "omnivore").trim().ifBlank { "omnivore" }
            }
            val allergies = (datastoreAllergies + roomAllergies)
                .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
            val dislikes = (datastoreDislikes + roomDislikes)
                .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
            return DietTextFilter(style, allergies, dislikes)
        }
    }
}

/** Minimal definition shape the analysis needs (decoupled from Room). */
data class NutrientInsightDef(
    val id: String,
    val name: String,
    val unit: String,
    val tier: Int,
    val rda: Double?,
    val ul: Double?,
    val foodSources: String?,
    /** Practical cap present in the seed (limit-trackers score against it). */
    val isLimitTracker: Boolean = false
)

object InsightsEngine {

    /** A nutrient is "persistently deficient" when below this share of target on ≥ [PERSISTENT_DAYS] days. */
    const val DEFICIENT_DAY_RATIO = 0.8
    const val PERSISTENT_DAYS = 3
    const val CRITICAL_DEFICIENCY_PCT = 0.5

    fun analyze(window: WindowData): List<Insight> {
        val insights = ArrayList<Insight>()
        val days = window.intakeByDay.values.size.let { _ ->
            window.intakeByDay.keys.map { it.second }.distinct().sorted()
        }
        if (days.isEmpty() && window.scoreSnapshots.isEmpty()) return insights

        // ---- Per-nutrient aggregates ------------------------------------
        data class Agg(
            val id: String, val name: String, val unit: String, val tier: Int,
            val target: Double, val isLimit: Boolean,
            val avgPct: Double, val lowDays: Int, val daysWithData: Int,
            val overCapDays: Int, val maxIntake: Double
        )

        val aggs = ArrayList<Agg>()
        for ((id, def) in window.definitions) {
            val goal = window.goals[id]
            val isLimit = def.isLimitTracker
            val target = when {
                isLimit -> goal?.targetValue ?: def.ul ?: def.rda ?: 0.0
                else -> goal?.targetValue ?: def.rda ?: 0.0
            }
            if (target <= 0) continue
            val series = days.map { d -> window.intakeByDay[id to d] }
            val withData = series.filterNotNull()
            if (withData.isEmpty()) continue
            val pcts = withData.map { it / target }
            val lowDays = pcts.count {
                if (isLimit) it > 1.0 else it < DEFICIENT_DAY_RATIO
            }
            val overCapDays = if (isLimit) pcts.count { it > 1.0 }
            else def.ul?.takeIf { it > 0 }?.let { ul ->
                withData.count { v -> v > ul }
            } ?: 0
            aggs += Agg(
                id = id, name = def.name, unit = def.unit, tier = def.tier,
                target = target, isLimit = isLimit,
                avgPct = pcts.average(), lowDays = lowDays, daysWithData = withData.size,
                overCapDays = overCapDays, maxIntake = withData.max()
            )
        }

        // ---- Persistent deficiencies (Tier 1/2 first) --------------------
        aggs.filter { !it.isLimit && it.lowDays >= PERSISTENT_DAYS }
            .sortedWith(compareBy({ it.tier }, { it.avgPct }))
            .forEach { a ->
                val severity = when {
                    a.tier == 1 && a.avgPct < CRITICAL_DEFICIENCY_PCT -> InsightSeverity.CRITICAL
                    a.tier == 1 -> InsightSeverity.WARNING
                    a.tier == 2 && a.avgPct < CRITICAL_DEFICIENCY_PCT -> InsightSeverity.WARNING
                    else -> InsightSeverity.INFO
                }
                insights += Insight(
                    kind = InsightKind.DEFICIENCY,
                    severity = severity,
                    nutrientId = a.id,
                    title = "Low ${a.name}",
                    message = ("Averaging %d%% of your %s target across %d days (%d low days)." +
                        " Good sources: %s").format(
                            (a.avgPct * 100).toInt(), a.name, a.daysWithData, a.lowDays,
                            sourcesFor(window, a.id)
                        )
                )
            }

        // ---- Over-limit / UL ---------------------------------------------
        aggs.filter { it.overCapDays > 0 }
            .sortedWith(compareByDescending<Agg> { it.tier }.thenByDescending { it.overCapDays })
            .forEach { a ->
                insights += Insight(
                    kind = InsightKind.EXCESS,
                    severity = if (a.tier == 1) InsightSeverity.CRITICAL else InsightSeverity.WARNING,
                    nutrientId = a.id,
                    title = "${a.name} high",
                    message = "Intake exceeded the %s on %d of %d days (peak %.4g %s).".format(
                        if (a.isLimit) "cap" else "UL",
                        a.overCapDays, a.daysWithData, a.maxIntake, a.unit
                    )
                )
            }

        // ---- Score trend vs previous window -------------------------------
        val scores = window.scoreSnapshots.map { it.score }
        val prevScores = window.previousScoreSnapshots.map { it.score }
        if (scores.isNotEmpty() && prevScores.isNotEmpty()) {
            val delta = scores.average() - prevScores.average()
            if (abs(delta) >= 3.0) {
                val up = delta > 0
                insights += Insight(
                    kind = if (up) InsightKind.TREND_UP else InsightKind.TREND_DOWN,
                    severity = if (up) InsightSeverity.INFO else InsightSeverity.WARNING,
                    title = if (up) "Score trending up" else "Score trending down",
                    message = "Daily score averaged %.0f this window vs %.0f before (%+.0f).".format(
                        scores.average(), prevScores.average(), delta
                    )
                )
            }
        }

        // ---- Streaks -------------------------------------------------------
        val byDay = window.scoreSnapshots.sortedBy { it.day }
        if (byDay.isNotEmpty()) {
            var best = 0
            var currentKind: Boolean? = null
            var current = 0
            for (s in byDay) {
                val good = s.score >= 70.0
                if (currentKind == good) current++ else { currentKind = good; current = 1 }
                if (current > best) best = current
            }
            if (best >= 3 && currentKind == true) {
                insights += Insight(
                    kind = InsightKind.STREAK, severity = InsightSeverity.INFO,
                    title = "$best-day strong streak",
                    message = "You scored 70+ for $best consecutive days. Keep it up."
                )
            } else if (best >= 3 && currentKind == false && byDay.last().score < 70.0) {
                insights += Insight(
                    kind = InsightKind.STREAK, severity = InsightSeverity.WARNING,
                    title = "$best-day weak streak",
                    message = "Scores have been below 70 for $best days — check your Tier-1 nutrients."
                )
            }
        }

        // ---- "What to eat" gaps -------------------------------------------
        aggs.filter { !it.isLimit && it.tier <= 2 && it.avgPct < DEFICIENT_DAY_RATIO }
            .take(3)
            .forEach { a ->
                insights += Insight(
                    kind = InsightKind.GAP,
                    severity = InsightSeverity.INFO,
                    nutrientId = a.id,
                    title = "Foods high in ${a.name}",
                    message = sourcesFor(window, a.id)
                )
            }

        return insights
    }

    /**
     * First sentence of the seed's food list, with diet-clashing foods
     * removed. When the diet empties the seeded list, curated
     * diet-appropriate alternatives for the nutrient are substituted.
     */
    private fun sourcesFor(window: WindowData, nutrientId: String): String {
        val def = window.definitions[nutrientId]
        val raw = def?.foodSources?.takeIf { it.isNotBlank() } ?: "varied whole foods"
        val first = raw.substringBefore('.').trim()
        return window.dietFilter?.filterList(first, nutrientId) ?: first
    }

    /** Top-3 insight summary for compact cards (Today dashboard). */
    fun topSummary(insights: List<Insight>, max: Int = 3): List<Insight> =
        insights.sortedWith(
            compareBy(
                { severityRank(it.severity) },
                { kindRank(it.kind) }
            )
        ).take(max)

    private fun severityRank(s: InsightSeverity): Int = when (s) {
        InsightSeverity.CRITICAL -> 0
        InsightSeverity.WARNING -> 1
        InsightSeverity.INFO -> 2
    }

    private fun kindRank(k: InsightKind): Int = when (k) {
        InsightKind.DEFICIENCY -> 0
        InsightKind.EXCESS -> 1
        InsightKind.TREND_DOWN -> 2
        InsightKind.STREAK -> 3
        InsightKind.GAP -> 4
        InsightKind.TREND_UP -> 5
        InsightKind.COACH_NOTE -> 6
    }
}
