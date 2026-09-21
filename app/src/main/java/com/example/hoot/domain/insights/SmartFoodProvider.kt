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

        /** Max picks shown in the Home strip. */
        const val MAX_PICKS = 6
    }

    /** Outcome of one refresh — surfaced for logging/tests. */
    data class SmartPicksResult(
        val picks: List<SmartFoodPick>,
        val gapCount: Int,
        val cacheCandidates: Int,
        val llmUsed: Boolean
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
            return SmartPicksResult(emptyList(), 0, 0, llmUsed = false)
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

        val candidates = cacheCandidates()
        var picks = SmartFoodMatcher.match(smartGaps, excesses, candidates, dietFilter, MAX_PICKS)

        // ---- (d) Seed top-up (thin cache, zero LLM) --------------------------
        if (candidates.size < MIN_CANDIDATES) {
            val seedTopUp = seedTopUp(candidates)
            if (seedTopUp.isNotEmpty()) {
                // Seed foods enter the SAME scoring pipeline (deterministic,
                // diet-filtered by the matcher like every other candidate).
                val merged = candidates + seedTopUp
                picks = SmartFoodMatcher.match(smartGaps, excesses, merged, dietFilter, MAX_PICKS)
            }
        }

        Log.i(
            TAG,
            "smartPicks($day): gaps=${smartGaps.size} excess=${excesses.size} " +
                "cache=${candidates.size} seedTopUp=${candidates.size < MIN_CANDIDATES} picks=${picks.size}"
        )
        return SmartPicksResult(picks, smartGaps.size, candidates.size, llmUsed = false)
            .also { memo(sig, it) }
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

