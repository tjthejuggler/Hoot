package com.example.hoot.domain.insights

import android.util.Log
import com.example.hoot.data.local.SettingsRepository
import com.example.hoot.data.local.entity.FoodNutrientProfileEntity
import com.example.hoot.data.repository.NutrientRepository
import com.example.hoot.data.repository.TailConfigRepository
import com.example.hoot.domain.nutrition.SeedFoodLibrary
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridge between the repository layer and the pure [SmartFoodMatcher]
 * (feature C). Assembles gaps/excesses from today's totals + trailing-7d
 * averages, pulls candidate foods with resolved per-100 g profiles from the
 * local cache, and returns matcher output.
 *
 * Cache cold-start (< [MIN_CANDIDATES] resolved foods): the bundled
 * [SeedFoodLibrary] LUT tops the candidate pool up deterministically — ZERO
 * LLM calls (audit 2026-09, docs/LLM_AUDIT.md §6.3: the old thin-cache LLM
 * enhancement batch is gone; the seed keeps the section useful from the
 * first launch and never violates the diet filter, which
 * [SmartFoodMatcher.match] applies to every candidate).
 */
class SmartFoodProvider(
    private val nutrients: NutrientRepository,
    private val tailConfig: TailConfigRepository,
    private val settings: SettingsRepository
) {

    companion object {
        private const val TAG = "HootSmartPicks"

        /** Below this many usable cached profiles the section stays empty. */
        const val MIN_CANDIDATES = 10

        /** Max picks shown in the Home section (2026-09: widened from 6 per user request). */
        const val MAX_PICKS = 12

        /**
         * "Show more" flow (feedback 2026-09-21): the default ranking targets
         * this many picks; [EXTENDED_MIN_SCORE] relaxes the quality floor
         * when the default ranking comes up short. Zero LLM — depth comes
         * from scoring the same pool more permissively.
         */
        const val EXTENDED_PICKS_TARGET = 24
        const val EXTENDED_MIN_SCORE = 0.05
    }

    /** Outcome of one refresh — surfaced for logging/tests. */
    data class SmartPicksResult(
        /** Curated top slice for the Home section (diversity-selected). */
        val picks: List<SmartFoodPick>,
        /**
         * Full ranking (feedback 2026-09 "see all"): same scoring WITHOUT the
         * Home cap or diversity de-dup — every food covering ≥1 current gap,
         * best score first. Powers [com.example.hoot.ui.home.AllSmartPicksSheet].
         */
        val allPicks: List<SmartFoodPick> = emptyList(),
        val gapCount: Int,
        val cacheCandidates: Int,
        val llmUsed: Boolean,
        /**
         * Foods actually offered to the matcher: DB-cache candidates PLUS the
         * seed top-up when the cache is thin. The UI cold-state must key off
         * THIS pool, not [cacheCandidates] — the bundled seed guarantees a
         * warm pool from the first launch, so the raw cache count kept the
         * "Building your smart picks" placeholder up while picks scored fine
         * (bug 2026-09-21).
         */
        val poolSize: Int = 0,
        /**
         * Deepest ranking (feedback 2026-09-21 "show more"): quality floor
         * fully relaxed (minScore 0 — any diet-clean food covering ≥1 gap).
         * Strict superset of [allPicks]; the sheet's "show even more" step
         * hands this out. Always computed — scoring the ~hundreds-food pool
         * is microseconds, and the memoized result keeps it free thereafter.
         */
        val deepPicks: List<SmartFoodPick> = emptyList()
    )

    // ---- Idempotency guard ------------------------------------------------------
    // HomeViewModel recomputes smart picks on EVERY distinct ledger emission
    // (observeDailyTotals.distinctUntilChanged) — during multi-pass meal
    // resolution that is several recomputes per minute, each previously re-running
    // the cache scan and (thin cache) the LLM batch. The result is deterministic
    // for a given (day, gap set), so the last one is memoized: identical inputs
    // return instantly and the LLM fires at most once per distinct gap signature.
    private var lastSignature: String? = null
    private var lastResult: SmartPicksResult? = null

    private fun signature(day: String, gaps: List<FocusNowItem>): String = day +
        "|" + gaps.sortedBy { it.nutrientId }.joinToString(",") {
            it.nutrientId + ":" + "%.3f".format(it.todayIntake)
        }

    /**
     * @param day today's day key ("yyyy-MM-dd")
     * @param gaps focus-now gaps (recent 7d AND today lacking)
     * @param windowAvg per-nutrient trailing-7d average (for excess detection)
     * @param effectiveLimit limit for every limit-tracker (goal > UL > RDA-cap)
     */
    suspend fun refresh(
        day: String,
        gaps: List<FocusNowItem>,
        windowAvg: Map<String, Double>,
        effectiveLimit: Map<String, Double>
    ): SmartPicksResult {
        val sig = signature(day, gaps)
        lastResult?.takeIf { sig == lastSignature }?.let {
            Log.d(TAG, "smartPicks($day): memo hit, skipping recompute")
            return it
        }

        val defs = nutrients.definitionsAll().associateBy { it.id }
        val totals = nutrients.dailyTotals(day).associate { it.nutrientId to it.total }

        // ---- (a) Lacking nutrients with deficits -----------------------------
        val smartGaps = gaps.mapNotNull { f ->
            val def = defs[f.nutrientId] ?: return@mapNotNull null
            SmartGap(
                nutrientId = f.nutrientId, name = f.name, tier = f.tier,
                unit = f.unit, target = f.target, todayIntake = f.todayIntake
            )
        }
        if (smartGaps.isEmpty()) {
            return SmartPicksResult(emptyList(), emptyList(), 0, 0, llmUsed = false)
                .also { memo(sig, it) }
        }

        // ---- (b) Excess nutrients -------------------------------------------
        // Red-line limit-trackers over cap recently (window avg or today),
        // plus ANY nutrient consistently > ~120% of target.
        val excesses = ArrayList<SmartExcess>()
        val seen = HashSet<String>()
        for (def in defs.values) {
            val limit = effectiveLimit[def.id] ?: continue
            if (limit <= 0) continue
            val isLimitTracker = com.example.hoot.ui.common.LIMIT_TRACKER_IDS.contains(def.id)
            val today = totals[def.id] ?: 0.0
            val avg = windowAvg[def.id] ?: 0.0
            val recentOver = avg > limit * 1.0
            val todayOver = today > limit
            if (isLimitTracker && (recentOver || todayOver)) {
                excesses += SmartExcess(
                    nutrientId = def.id, name = def.name, limit = limit,
                    intake = maxOf(today, avg), redLine = true
                )
                seen += def.id
            } else if (!isLimitTracker && avg > limit * 1.2) {
                excesses += SmartExcess(
                    nutrientId = def.id, name = def.name, limit = limit,
                    intake = avg, redLine = false
                )
                seen += def.id
            }
        }

        // ---- (c) Candidates from the local cache ------------------------------
        // diet-leak self-healing (2026-09): route through the merged
        // DataStore+Room filter (safety-biased promotion, see
        // DietTextFilter.merged) instead of the raw Room row — the raw row
        // kept diet_style=omnivore with "Vegan" only as an allergy chip.
        val diet = tailConfig.dietaryProfile()
        val s = runCatching { settings.current() }.getOrNull()
        val merged = com.example.hoot.domain.insights.DietTextFilter.merged(
            datastoreStyle = s?.dietStyle,
            datastoreAllergies = s?.dietAllergies ?: emptySet(),
            datastoreDislikes = s?.dietDislikes ?: emptySet(),
            roomStyle = diet?.dietStyle,
            roomAllergies = parseStringList(diet?.allergiesJson),
            roomDislikes = parseStringList(diet?.dislikesJson)
        )
        val dietFilter = SmartDietFilter(
            dietStyle = merged.dietStyle,
            allergies = merged.allergies,
            dislikes = merged.dislikes
        )

        // ---- (d) Pool assembly (quality-first rework 2026-09-22) -------------
        // The bundled [SeedFoodLibrary] is ALWAYS in the pool — it is the
        // curated, database-grade backbone of every recommendation list. DB
        // rows join it only when they pass the plausibility gate, so a large
        // but junky cache can no longer crowd the suggestions with garbage
        // ("Dark Beverage") — the old thin-cache-only seed top-up meant a
        // 465-row cache scored meal-title fragments instead of real foods.
        val candidates = cacheCandidates().filter {
            SmartFoodMatcher.isPlausibleFoodName(SmartFoodMatcher.cleanFoodName(it.displayName))
        }
        val seed = seedTopUp(candidates)
        val pool = candidates + seed

        // Cleaned display names (feedback 2026-09-21): Tail/LLM segmentation
        // artifacts ("Plus Seaweed Sheets.", "… (700 Kcal)") must not leak
        // into suggestion cards. Cleaning is display-only — ids/keys keep the
        // raw values so cache resolution stays stable.
        val cleanedPool = pool.map { it.copy(displayName = SmartFoodMatcher.cleanFoodName(it.displayName)) }
            // Plausibility gate (quality rework 2026-09-22): DB rows whose
            // names are not concrete single foods ("Dark Beverage", compound
            // meal titles) never become recommendations.
            .filter { SmartFoodMatcher.isPlausibleFoodName(it.displayName) }

        // Home section: capped + diversity-de-duped.
        val picks = SmartFoodMatcher.match(smartGaps, excesses, cleanedPool, dietFilter, MAX_PICKS)
        // Sheet ranking (one continuous list, quality rework 2026-09-22): the
        // FULL ranked set with the quality floor relaxed to [EXTENDED_MIN_SCORE]
        // — the UI pages through it and, at the end, offers the deep pass
        // (floor 0) for "keep going" growth. No more disjointed three-tier
        // lists; the seed-anchored pool keeps every tier real foods.
        val allPicks = SmartFoodMatcher.match(
            smartGaps, excesses, cleanedPool, dietFilter,
            Int.MAX_VALUE, minScore = EXTENDED_MIN_SCORE
        )
        val deepPicks = SmartFoodMatcher.match(
            smartGaps, excesses, cleanedPool, dietFilter,
            Int.MAX_VALUE, minScore = 0.0
        )

        Log.i(
            TAG,
            "smartPicks($day): gaps=${smartGaps.size} excess=${excesses.size} " +
                "cacheOk=${candidates.size} seedTop=${seed.size} " +
                "pool=${pool.size} picks=${picks.size} allPicks=${allPicks.size} " +
                "deep=${deepPicks.size}"
        )
        return SmartPicksResult(
            picks, allPicks, smartGaps.size, candidates.size,
            llmUsed = false, poolSize = pool.size, deepPicks = deepPicks
        ).also { memo(sig, it) }
    }

    private fun memo(sig: String, result: SmartPicksResult) {
        lastSignature = sig
        lastResult = result
    }

    // ---- Cache scan -----------------------------------------------------------

    /** All diet-agnostic resolved foods with usable per-100 g panels. */
    private suspend fun cacheCandidates(): List<SmartCandidateFood> {
        val out = ArrayList<SmartCandidateFood>()
        for (food in nutrients.foodsAll()) {
            if (food.isSupplement) continue
            val profile = nutrients.profileForFood(food.id) ?: continue
            if (profile.confidence < 0.5) continue
            val values = parseProfileValues(profile) ?: continue
            if (values.isEmpty()) continue
            out += SmartCandidateFood(
                foodId = food.id,
                displayName = food.displayName,
                category = food.category,
                servingGrams = food.typicalServingGrams,
                per100 = values
            )
        }
        return out
    }

    private fun parseProfileValues(profile: FoodNutrientProfileEntity): Map<String, Double>? {
        val raw = runCatching { JSONObject(profile.valuesJson) }.getOrNull() ?: return null
        val per100 = if (profile.perAmount > 0) 100.0 / profile.perAmount else return null
        val out = HashMap<String, Double>(raw.length())
        val keys = raw.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = raw.optDouble(k, Double.NaN)
            if (!v.isNaN() && v > 0) out[k] = v * per100
        }
        return out
    }

    // ---- Seed top-up (thin cache) ------------------------------------------------

    /**
     * Bundled LUT foods the cache doesn't already cover, as scoring
     * candidates. Deterministic; the matcher's diet filter applies to them
     * like to every other candidate.
     */
    private fun seedTopUp(existing: List<SmartCandidateFood>): List<SmartCandidateFood> {
        val seenNames = existing.mapTo(HashSet()) { it.displayName.lowercase() }
        return SeedFoodLibrary.foods.values
            .filter { it.displayName.lowercase() !in seenNames }
            .map { food ->
                SmartCandidateFood(
                    foodId = "seed:${food.key}",
                    displayName = food.displayName,
                    category = food.category,
                    servingGrams = food.typicalServingGrams,
                    per100 = food.per100
                )
            }
    }

    private fun parseStringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
        }.getOrDefault(emptyList())
    }
}

