package com.example.hoot.domain.insights

import android.util.Log
import com.example.hoot.data.local.AppSettings
import com.example.hoot.data.local.SettingsRepository
import com.example.hoot.data.local.entity.FoodNutrientProfileEntity
import com.example.hoot.data.remote.LlmClient
import com.example.hoot.data.remote.LlmConfig
import com.example.hoot.data.repository.NutrientRepository
import com.example.hoot.data.repository.TailConfigRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridge between the repository layer and the pure [SmartFoodMatcher]
 * (feature C). Assembles gaps/excesses from today's totals + trailing-7d
 * averages, pulls candidate foods with resolved per-100 g profiles from the
 * local cache, and returns matcher output.
 *
 * Cache cold-start (< [MIN_CANDIDATES] resolved foods): returns an empty
 * list — the Home section shows the "builds up as Hoot learns your foods"
 * empty state. The optional LLM enhancement is ONE batched call, only when
 * configured AND the cache is thin; failures are silent (cache-only result).
 */
class SmartFoodProvider(
    private val nutrients: NutrientRepository,
    private val tailConfig: TailConfigRepository,
    private val settings: SettingsRepository,
    private val llm: LlmClient
) {

    companion object {
        private const val TAG = "HootSmartPicks"

        /** Below this many usable cached profiles the section stays empty. */
        const val MIN_CANDIDATES = 10

        /** Max picks shown in the Home strip. */
        const val MAX_PICKS = 6

        /** LLM asks for this many foods when enhancing a thin cache. */
        private const val LLM_ASK_COUNT = 6
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
        var llmUsed = false

        // ---- (d) Optional LLM enhancement (thin cache only) ------------------
        if (candidates.size < MIN_CANDIDATES) {
            val s = settings.current()
            val cfg = LlmConfig(s.baseUrl, s.apiKey, s.model)
            if (cfg.configured) {
                val generated = runCatching {
                    generateViaLlm(cfg, s, smartGaps, excesses, dietFilter)
                }.onFailure {
                    Log.w(TAG, "smart-picks LLM batch failed (silent): ${it.message}")
                }.getOrDefault(emptyList())
                if (generated.isNotEmpty()) {
                    llmUsed = true
                    // Generated foods enter the SAME scoring pipeline (per-100
                    // panels are approximate — marked source=llm for the badge).
                    val merged = candidates + generated
                    picks = SmartFoodMatcher.match(smartGaps, excesses, merged, dietFilter, MAX_PICKS)
                }
            }
        }

        Log.i(
            TAG,
            "smartPicks($day): gaps=${smartGaps.size} excess=${excesses.size} " +
                "cache=${candidates.size} llm=$llmUsed picks=${picks.size}"
        )
        return SmartPicksResult(picks, smartGaps.size, candidates.size, llmUsed)
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

    // ---- Optional LLM batch -----------------------------------------------------

    /**
     * ONE batched call: foods rich in the gaps, low in the excesses, diet
     * respected. Parsed into [SmartCandidateFood]s with approximate per-100
     * panels so they run through the same deterministic scorer.
     */
    private suspend fun generateViaLlm(
        cfg: LlmConfig,
        s: AppSettings,
        gaps: List<SmartGap>,
        excesses: List<SmartExcess>,
        diet: SmartDietFilter
    ): List<SmartCandidateFood> {
        val ask = JSONObject()
            .put(
                "gaps",
                JSONArray(gaps.map {
                    JSONObject().put("nutrient_id", it.nutrientId)
                        .put("name", it.name)
                        .put("unit", it.unit)
                })
            )
            .put(
                "avoid_excess",
                JSONArray(excesses.map {
                    JSONObject().put("nutrient_id", it.nutrientId).put("name", it.name)
                })
            )
            .put("diet_style", diet.dietStyle)
            .put(
                "avoid",
                JSONArray().apply {
                    diet.allergies.forEach { put(it) }
                    diet.dislikes.forEach { put(it) }
                }
            )
            .put("foods_needed", LLM_ASK_COUNT)
        val raw = llm.completeJson(
            cfg = cfg,
            system = SYSTEM_PROMPT,
            user = ask.toString(),
            temperature = s.temperature * 0.5f,
            maxTokens = 1_200,
            disableThinking = s.disableThinking
        )
        return parseLlmFoods(raw, gaps.associate { it.nutrientId to it.unit })
    }

    private fun parseLlmFoods(
        json: String,
        gapUnits: Map<String, String>
    ): List<SmartCandidateFood> {
        val root = JSONObject(LlmClient.extractJson(json))
        val arr = root.optJSONArray("foods") ?: return emptyList()
        val out = ArrayList<SmartCandidateFood>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("food").takeIf { it.isNotBlank() } ?: continue
            val values = JSONObject()
            val v = o.optJSONObject("per_100g") ?: continue
            val keys = v.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val amount = v.optDouble(k, Double.NaN)
                if (!amount.isNaN() && amount > 0) values.put(k, amount)
            }
            if (values.length() == 0) continue
            out += SmartCandidateFood(
                foodId = "llm:${name.lowercase()}",
                displayName = name,
                category = o.optString("category").takeIf { it.isNotBlank() },
                servingGrams = o.optDouble("serving_grams", Double.NaN)
                    .takeIf { !it.isNaN() && it > 0 },
                per100 = parseRawPer100(values),
                emojiHint = null
            )
        }
        return out
    }

    /** LLM values may be in mg/mcg per 100 g already — canonicalized upstream convention: values are used as-is (canonical units). */
    private fun parseRawPer100(values: JSONObject): Map<String, Double> {
        val out = HashMap<String, Double>()
        val keys = values.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = values.optDouble(k, Double.NaN)
            if (!v.isNaN() && v > 0) out[k] = v
        }
        return out
    }

    private fun parseStringList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
        }.getOrDefault(emptyList())
    }
}

/**
 * Strict-JSON prompt for the smart-picks enhancement (mirror of
 * [com.example.hoot.domain.insights.RecommendationEngine]'s approach).
 * Diet restrictions are stated as HARD constraints (diet-fix 2026-09);
 * the deterministic [DietRules] filter still gates LLM output defensively.
 */
private const val SYSTEM_PROMPT: String =
    "You are Hoot's smart food matcher. Given nutrient gaps and excess warnings, reply with ONLY " +
        "a JSON object {\"foods\":[{\"food\":\"...\",\"category\":\"vegetable\",\"serving_grams\":100," +
        "\"per_100g\":{\"magnesium\":79,\"vitamin_b6\":0.3}}]}. Pick common whole foods that are HIGH in " +
        "several gap nutrients AT ONCE and LOW in every avoid_excess nutrient. per_100g keys must be the " +
        "given nutrient ids in canonical units (g for macronutrients, mg, mcg). " +
        "diet_style and the avoid list are STRICT HARD CONSTRAINTS: STRICTLY EXCLUDE every food " +
        "that violates them (e.g. for vegan: ALL meat, fish, seafood, eggs, dairy, honey — use " +
        "legumes, tofu, tempeh, fortified plant foods instead). A food that violates the diet is " +
        "useless — never output one."
