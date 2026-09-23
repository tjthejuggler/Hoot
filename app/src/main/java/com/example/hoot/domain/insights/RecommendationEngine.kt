package com.example.hoot.domain.insights

import android.util.Log
import com.example.hoot.data.local.entity.FoodEntity
import com.example.hoot.data.local.entity.FoodNutrientProfileEntity
import com.example.hoot.data.local.entity.RecommendationEntity
import com.example.hoot.data.remote.LlmClient
import com.example.hoot.data.remote.LlmConfig
import com.example.hoot.data.repository.MealRepository
import com.example.hoot.data.repository.NutrientRepository
import com.example.hoot.data.repository.TailConfigRepository
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.min

/**
 * Deficiency-driven food recommendations (ARCHITECTURE.md §5–6): for the top
 * Tier-1/2 gaps, find foods rich in the lacking nutrient — local resolved
 * profiles first ([FoodNutrientProfileEntity] values already in canonical
 * units), otherwise ONE batched LLM call. Every issued row lands in
 * `recommendation_log` with `accepted = null` (open) so ScoreEngine can
 * measure adherence over the trailing window.
 *
 * Diet filtering (vegan/vegetarian/pescatarian/keto + allergies + dislikes)
 * applies to cached candidates by keyword heuristics and is injected into the
 * LLM prompt for generated ones.
 */
class RecommendationEngine(
    private val nutrients: NutrientRepository,
    private val meals: MealRepository,
    private val tailConfig: TailConfigRepository,
    private val llm: LlmClient,
    private val settings: com.example.hoot.data.local.SettingsRepository
) {

    /** Max distinct nutrients targeted per run (top deficiencies first). */
    var maxNutrients: Int = 3

    /** Max foods per nutrient. */
    var foodsPerNutrient: Int = 2

    /** One run's outcome — surfaced for logs/tests. */
    data class RunStats(
        val nutrientsTargeted: Int,
        val cachedFoods: Int,
        val llmFoods: Int,
        val issued: Int,
        val skippedAlreadyRecommended: Int,
        val llmUsed: Boolean
    )

    /**
     * Analyzes [day]'s intake vs goals and issues recommendations for the
     * biggest gaps. Idempotent-ish: nutrients already targeted today are
     * skipped, so repeated kicks don't pile up duplicates.
     */
    suspend fun generateForDay(day: String): RunStats {
        val filter = mergedDietFilter()
        val dietStyle = filter.dietStyle
        val allergies = filter.allergies
        val dislikes = filter.dislikes

        val definitions = nutrients.definitionsAll().associateBy { it.id }
        val goals = nutrients.goalsAll().associateBy { it.nutrientId }
        val totals = nutrients.dailyTotals(day).associate { it.nutrientId to it.total }
        val alreadyToday = nutrients.recommendedNutrientIdsForDay(day).toSet()

        // Rank deficient nutrients: tier asc (critical first), coverage asc.
        val gaps = definitions.values.mapNotNull { def ->
            val isLimit = def.id in ScoreEngineLimitTrackers.SET
            val target = if (isLimit) return@mapNotNull null    // never "recommend more" of a cap
            else goals[def.id]?.targetValue ?: def.rdaValue ?: return@mapNotNull null
            val intake = totals[def.id] ?: 0.0
            val coverage = intake / target
            // Only actual gaps (below ~80 %) make the cut.
            if (coverage >= 0.8) return@mapNotNull null
            Gap(
                id = def.id, name = def.name,
                tier = goals[def.id]?.priority ?: def.tier,
                target = target, intake = intake, coverage = coverage
            )
        }.sortedWith(compareBy({ it.tier }, { it.coverage }))
            .filter { it.id !in alreadyToday }
            .take(maxNutrients)

        if (gaps.isEmpty()) {
            return RunStats(0, 0, 0, 0, 0, llmUsed = false)
        }

        var cachedFoods = 0
        var llmFoods = 0
        var issued = 0
        val needLlm = ArrayList<Pair<Gap, Int>>()   // gap → how many more foods needed

        // ---- Pass 1: local profiles rich in the lacking nutrient ------------
        // Quality engine (2026-09-23): findCachedSources name-gates, ranks by
        // PER-SERVING % of target (not per-100g), and enforces the density
        // floor — vague/weak rows ("herbs and seasonings" at 1%) never issue.
        for (gap in gaps) {
            val candidates = findCachedSources(gap.id, dietStyle, allergies, dislikes, gap.target)
            cachedFoods += candidates.size
            val take = min(candidates.size, foodsPerNutrient)
            for (cand in candidates.take(take)) {
                if (issue(gap, day, cand.food.displayName, reason(gap, cand.per100, cand.perAmount, cand.food), null)) {
                    issued++
                }
            }
            if (take < foodsPerNutrient) needLlm += gap to (foodsPerNutrient - take)
        }

        // ---- Pass 2: ONE batched LLM call for the remaining shortfalls ------
        var llmUsed = false
        if (needLlm.isNotEmpty()) {
            val s = settings.current()
            val cfg = LlmConfig(s.baseUrl, s.apiKey, s.model)
            if (cfg.configured) {
                val generated = runCatching { generateViaLlm(cfg, s, dietStyle, allergies, dislikes, needLlm) }
                    .onFailure { Log.w(TAG, "LLM recommendation batch failed: ${it.message}") }
                    .getOrDefault(emptyList())
                    // Defensive gates: LLMs occasionally ignore diet_style AND
                    // emit vague category names ("herbs and seasonings") —
                    // neither may reach the ledger (quality engine 2026-09-23).
                    .filter {
                        DietRules.allowsFood(it.foodName, dietStyle, allergies, dislikes) &&
                            NutrientSourceQuality.isAcceptableSourceName(it.foodName)
                    }
                llmUsed = generated.isNotEmpty()
                for (gen in generated) {
                    val gap = gaps.firstOrNull { it.id == gen.nutrientId } ?: continue
                    if (issue(gap, day, gen.foodName, gen.reason, gen.imageUrl)) llmFoods++
                }
            } else {
                Log.i(TAG, "LLM not configured — cache-only recommendations for $day")
            }
        }

        // "issued" counts every accepted-by-pipeline row (cache + LLM).
        issued = cachedFoods.coerceAtMost(foodsPerNutrient * gaps.size) + llmFoods
        Log.i(
            TAG,
            "recommend($day): nutrients=${gaps.size} cached=$cachedFoods llm=$llmFoods issued=$issued"
        )
        return RunStats(gaps.size, cachedFoods, llmFoods, issued, 0, llmUsed)
    }

    /**
     * On-demand generation for the nutrient detail sheet ("More suggestions",
     * UI overhaul feedback #3): cache-first, optional single LLM call scoped
     * to ONE nutrient, deduped against foods already suggested for that
     * nutrient on [day]. Every issued row is persisted (accepted = null) so
     * adherence + ScoreEngine pick it up.
     */
    suspend fun generateForNutrient(
        day: String,
        nutrientId: String,
        count: Int,
        allowLlm: Boolean
    ): RunStats {
        val def = nutrients.definition(nutrientId)
            ?: return RunStats(0, 0, 0, 0, 0, llmUsed = false)
        val goals = nutrients.goalsAll().associateBy { it.nutrientId }
        val target = goals[nutrientId]?.targetValue ?: def.rdaValue
            ?: return RunStats(0, 0, 0, 0, 0, llmUsed = false)
        val totals = nutrients.dailyTotals(day).associate { it.nutrientId to it.total }
        val intake = totals[nutrientId] ?: 0.0
        val gap = Gap(
            id = def.id, name = def.name,
            tier = goals[def.id]?.priority ?: def.tier,
            target = target, intake = intake, coverage = intake / target
        )
        val filter = mergedDietFilter()
        val dietStyle = filter.dietStyle
        val allergies = filter.allergies
        val dislikes = filter.dislikes
        val existing = nutrients.recommendationsBetween(day, day)
            .filter { it.nutrientId == nutrientId }
            .map { it.foodName.lowercase() }
            .toSet()

        val issuedNames = LinkedHashSet<String>()
        var cached = 0
        var llmFoods = 0

        // Pass 1 — local resolved profiles rich in the nutrient (quality
        // gates inside: precise names, per-serving ranking, density floor).
        val candidates = findCachedSources(nutrientId, dietStyle, allergies, dislikes, target)
        for (cand in candidates) {
            if (issuedNames.size >= count) break
            val key = cand.food.displayName.lowercase()
            if (key in existing || key in issuedNames) continue
            if (issue(gap, day, cand.food.displayName, reason(gap, cand.per100, cand.perAmount, cand.food), null)) {
                issuedNames += key
                cached++
            }
        }

        // Pass 2 — one LLM call for the remainder (when allowed + configured).
        var llmUsed = false
        val stillNeeded = count - issuedNames.size
        if (allowLlm && stillNeeded > 0) {
            val s = settings.current()
            val cfg = LlmConfig(s.baseUrl, s.apiKey, s.model)
            if (cfg.configured) {
                val generated = runCatching {
                    generateViaLlm(cfg, s, dietStyle, allergies, dislikes, listOf(gap to stillNeeded))
                }
                    .onFailure { Log.w(TAG, "LLM single-nutrient batch failed: ${it.message}") }
                    .getOrDefault(emptyList())
                    // Defensive gates: diet violations + vague names (same as
                    // the batch path, quality engine 2026-09-23).
                    .filter {
                        DietRules.allowsFood(it.foodName, dietStyle, allergies, dislikes) &&
                            NutrientSourceQuality.isAcceptableSourceName(it.foodName)
                    }
                llmUsed = generated.isNotEmpty()
                for (gen in generated) {
                    if (issuedNames.size >= count) break
                    val key = gen.foodName.lowercase()
                    if (key in existing || key in issuedNames) continue
                    if (issue(gap, day, gen.foodName, gen.reason, gen.imageUrl)) {
                        issuedNames += key
                        llmFoods++
                    }
                }
            } else {
                Log.i(TAG, "LLM not configured — cache-only more-suggestions for $nutrientId")
            }
        }
        return RunStats(
            if (issuedNames.isEmpty()) 0 else 1, cached, llmFoods,
            issuedNames.size, 0, llmUsed
        )
    }

    /** One nutrient gap to fill (target-trackers only). */
    private data class Gap(
        val id: String, val name: String, val tier: Int,
        val target: Double, val intake: Double, val coverage: Double
    )

    /** Adherence over a window (consumed by ScoreEngine + Insights header). */
    suspend fun adherence(from: String, to: String): Pair<Int, Int> {
        val recs = nutrients.recommendationsBetween(from, to)
        val answered = recs.count { it.accepted != null }
        val accepted = recs.count { it.accepted == true }
        return accepted to answered
    }

    // ---- Internals ----------------------------------------------------------

    private suspend fun issue(
        gap: Gap,
        day: String,
        foodName: String,
        reason: String,
        imageUrl: String?
    ): Boolean {
        val def = nutrients.definition(gap.id) ?: return false
        nutrients.upsertRecommendation(
            RecommendationEntity(
                id = UUID.randomUUID().toString(),
                day = day,
                nutrientId = gap.id,
                foodName = foodName,
                reasonText = reason,
                imageUrl = imageUrl?.takeIf { it.startsWith("http") },
                sourceIdsJson = "[]",
                accepted = null
            )
        )
        Log.d(TAG, "issued: ${gap.name} ← $foodName (${def.unit})")
        return true
    }

    private fun reason(gap: Gap, valuePerAmount: Double, perAmount: Double, food: FoodEntity): String {
        val valuePer100 = if (perAmount > 0) valuePerAmount * (100.0 / perAmount) else valuePerAmount
        val perPortion = food.typicalServingGrams?.let { g -> valuePer100 * (g / 100.0) } ?: valuePer100
        val pctOfTarget = if (gap.target > 0) (perPortion / gap.target * 100.0) else 0.0
        return "%s covers about %d%% of your %s target per portion (you're at %d%% today).".format(
            food.displayName,
            min(200, pctOfTarget.toInt().coerceAtLeast(1)),
            gap.name,
            (gap.coverage * 100).toInt()
        )
    }

    /** One quality-gated cached candidate: per-100 g value + serving grams. */
    data class CachedSource(
        val food: FoodEntity,
        /** Profile's native amount (grams) the value refers to. */
        val perAmount: Double,
        /** Nutrient value normalized to per-100 g. */
        val per100: Double,
        /** Coverage = share of the daily target in one typical serving. */
        val servingCoverage: Double
    )

    /**
     * Local profile search: foods whose resolved panel has a high value of
     * [nutrientId], ranked by how much of the daily [target] ONE TYPICAL
     * SERVING delivers (feedback 2026-09-23 — per-100 g ranking favored
     * papers like "herbs and seasonings" that nobody eats in 100 g lots).
     * Quality gates via [NutrientSourceQuality]:
     *  - precise-name check (rejects "Herbs and seasonings", junk LLM rows),
     *  - density floor (one serving must cover ≥ 10% of the target),
     *  - diet/allergy/dislike keyword filtering (unchanged).
     */
    private suspend fun findCachedSources(
        nutrientId: String,
        dietStyle: String,
        allergies: List<String>,
        dislikes: List<String>,
        target: Double
    ): List<CachedSource> {
        val out = ArrayList<CachedSource>()
        val foods = nutrients.foodsAll()
        for (food in foods) {
            if (food.isSupplement) continue
            if (!NutrientSourceQuality.isAcceptableSourceName(food.displayName)) continue
            if (!dietAllows(food.displayName, food.category, dietStyle, allergies, dislikes)) continue
            val profile = nutrients.profileForFood(food.id) ?: continue
            if (profile.confidence < 0.5) continue
            val values = runCatching { JSONObject(profile.valuesJson) }.getOrNull() ?: continue
            val value = values.optDouble(nutrientId, Double.NaN)
            if (value.isNaN() || value <= 0) continue
            // Normalize to per-100-unit, then to per-typical-serving coverage.
            val per100 = if (profile.perAmount > 0) value * (100.0 / profile.perAmount) else value
            val coverage = NutrientSourceQuality.servingCoverage(
                per100 = per100,
                servingGrams = food.typicalServingGrams,
                target = target
            )
            if (!NutrientSourceQuality.meetsDensityFloor(coverage)) continue
            out += CachedSource(food, profile.perAmount, per100, coverage)
        }
        // Rank by per-serving coverage of the daily target, best first.
        return out.sortedByDescending { it.servingCoverage }.take(12)
    }

    /**
     * Keyword-level diet compatibility (deterministic pre-LLM filter).
     * Delegates to the central [DietRules] sets so every surface shares the
     * same exclusion vocabularies, word-boundary matching and plant-phrase
     * overrides (diet-fix, 2026-09).
     */
    private fun dietAllows(
        displayName: String,
        category: String?,
        dietStyle: String,
        allergies: List<String>,
        dislikes: List<String>
    ): Boolean {
        if (!DietRules.allowsFood(displayName, dietStyle, allergies, dislikes)) return false
        val c = (category ?: "").lowercase()
        // Category strings ("dairy", "seafood"…) get the allergy + style
        // check too — a category alone can violate the diet.
        return c.isEmpty() || DietRules.allowsFood(c, dietStyle, allergies, emptyList())
    }

    /**
     * Safety-biased DataStore + Room merge (diet-fix hardening, 2026-09):
     * previously ONLY the Room `dietary_profile` row was consulted here — a
     * failed mirror write silently regenerated OMNIVORE suggestions while
     * the user saw "vegan" in Settings. See [DietTextFilter.merged].
     */
    private suspend fun mergedDietFilter(): DietTextFilter {
        val room = runCatching { tailConfig.dietaryProfile() }.getOrNull()
        val s = runCatching { settings.current() }.getOrNull()
        return DietTextFilter.merged(
            datastoreStyle = s?.dietStyle,
            datastoreAllergies = s?.dietAllergies ?: emptySet(),
            datastoreDislikes = s?.dietDislikes ?: emptySet(),
            roomStyle = room?.dietStyle,
            roomAllergies = parseStringList(room?.allergiesJson),
            roomDislikes = parseStringList(room?.dislikesJson)
        )
    }

    // ---- LLM batch generation ---------------------------------------------

    data class GeneratedFood(
        val nutrientId: String,
        val foodName: String,
        val reason: String,
        val imageUrl: String?
    )

    private suspend fun generateViaLlm(
        cfg: LlmConfig,
        s: com.example.hoot.data.local.AppSettings,
        dietStyle: String,
        allergies: List<String>,
        dislikes: List<String>,
        needs: List<Pair<Gap, Int>>
    ): List<GeneratedFood> {
        val asks = needs.map { (gap, count) ->
            JSONObject()
                .put("nutrient", gap.name)
                .put("nutrient_id", gap.id)
                .put("current_pct", (gap.coverage * 100).toInt())
                .put("foods_needed", count)
        }
        val user = JSONObject()
            .put("diet_style", dietStyle)
            .put("avoid", JSONArray().apply { allergies.forEach { put(it) }; dislikes.forEach { put(it) } })
            .put("requests", JSONArray(asks.map { it.toString() }))
        val raw = llm.completeJson(
            cfg = cfg,
            system = SYSTEM_PROMPT,
            user = user.toString(),
            temperature = (s.temperature * 0.5f),
            maxTokens = 1_200,
            disableThinking = s.disableThinking
        )
        return runCatching { parseGenerated(raw) }.getOrElse {
            Log.w(TAG, "unparseable LLM recommendation JSON: ${raw.take(160)}")
            emptyList()
        }
    }

    private fun parseGenerated(json: String): List<GeneratedFood> {
        val root = JSONObject(LlmClient.extractJson(json))
        val arr = root.optJSONArray("recommendations") ?: return emptyList()
        val out = ArrayList<GeneratedFood>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val nutrientId = o.optString("nutrient_id").takeIf { it.isNotBlank() } ?: continue
            val foodName = o.optString("food").takeIf { it.isNotBlank() } ?: continue
            out += GeneratedFood(
                nutrientId = nutrientId,
                foodName = foodName,
                reason = o.optString("why").ifBlank { "Rich in the nutrient you're missing." },
                imageUrl = o.optString("image_url").takeIf { it.startsWith("http") }
            )
        }
        return out
    }

    companion object {
        private const val TAG = "HootRecommend"

        /**
         * Diet restrictions are HARD constraints: violating suggestions are
         * useless output (strengthened wording, diet-fix 2026-09). The
         * deterministic [DietRules] keyword filter still gates LLM output
         * defensively in [generateForDay]/[generateForNutrient].
         */
        const val SYSTEM_PROMPT: String =
            "You are Hoot's nutrition recommender. Given gaps vs daily targets, reply with ONLY " +
                "a JSON object {\"recommendations\":[{\"nutrient_id\":\"...\",\"food\":\"...\",\"why\":\"...\"" +
                ",\"image_url\":\"...\"}]}. Pick common whole foods (or fortified foods for vegans where " +
                "noted), 1 short sentence per 'why' (<=160 chars). " +
                "diet_style and the avoid list are STRICT HARD CONSTRAINTS: " +
                "STRICTLY EXCLUDE every food that violates them (e.g. for vegan: ALL meat, fish, " +
                "seafood, eggs, dairy, honey; use legumes, tofu, tempeh, seitan, fortified plant " +
                "foods instead). A suggestion that violates the diet is useless — never output one. " +
                "image_url is optional: include it ONLY if you are certain of a stable direct image URL; " +
                "otherwise omit the field entirely."
    }
}

/** Mirrors [com.example.hoot.domain.score.ScoreSnapshotter] — cap-scored nutrients. */
private object ScoreEngineLimitTrackers {
    val SET = setOf("added_sugar", "saturated_fat", "trans_fat", "sodium")
}

private fun parseStringList(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
    }.getOrDefault(emptyList())
}
