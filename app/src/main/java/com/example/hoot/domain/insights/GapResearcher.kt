package com.example.hoot.domain.insights

import android.util.Log
import com.example.hoot.data.local.SettingsRepository
import com.example.hoot.data.remote.LlmClient
import com.example.hoot.data.remote.LlmConfig
import com.example.hoot.data.repository.FoodKnowledgeRepository
import com.example.hoot.domain.nutrition.FoodNormalizer
import com.example.hoot.domain.nutrition.LlmRateGuard
import com.example.hoot.domain.nutrition.NutritionResolver
import com.example.hoot.domain.nutrition.SeedFoodLibrary
import org.json.JSONArray

/**
 * Gap-targeted food research (feedback 2026-09-27): when the current
 * recommendations don't satisfy the user ("find more foods online"), ONE
 * LLM discovery call asks for foods SPECIFICALLY rich in the user's current
 * gap nutrients, then every NEW food is resolved through the standard
 * pipeline (batch panels) and lands in the permanent food-knowledge base
 * tagged with the gaps it was researched for. Over time this amasses a
 * private database keyed to the user's actual deficiencies.
 *
 * Zero-LLM-safe: without a configured LLM the call is a fast no-op.
 */
class GapResearcher(
    private val resolver: NutritionResolver,
    private val knowledge: FoodKnowledgeRepository,
    private val settings: SettingsRepository,
    private val llm: LlmClient
) {
    /** Result of one research pass — surfaced to the UI/toast. */
    data class ResearchOutcome(
        val discovered: Int,      // foods the LLM proposed
        val added: Int,           // foods newly persisted into the knowledge base
        val skippedKnown: Int     // proposals the KB/seed/cache already covered
    )

    /**
     * Asks the LLM for up to [maxFoods] foods rich in [gaps]' nutrients,
     * resolves the new ones and records them in the knowledge base. Never
     * throws; failures collapse to an outcome with added=0.
     *
     * @param alreadyKnown foods to skip (normalizedName keys), typically the
     *   keys already visible in the recommendation sheet
     */
    suspend fun researchForGaps(
        gaps: List<FocusNowItem>,
        alreadyKnown: Set<String> = emptySet(),
        maxFoods: Int = MAX_FOODS_PER_PASS
    ): ResearchOutcome {
        if (gaps.isEmpty()) return ResearchOutcome(0, 0, 0)
        val s = runCatching { settings.current() }.getOrNull()
            ?: return ResearchOutcome(0, 0, 0)
        val cfg = LlmConfig(s.baseUrl, s.apiKey, s.model)
        if (!cfg.configured) return ResearchOutcome(0, 0, 0)

        val gapList = gaps.take(MAX_GAPS).joinToString("\n") { g ->
            "- ${g.name} (daily target ${"%.0f".format(g.target)} ${g.unit})"
        }
        val prompt = discoveryPrompt(gapList, maxFoods)

        val names = runCatching {
            val json = llm.chat(
                cfg, DISCOVERY_SYSTEM_PROMPT, prompt,
                temperature = 0.4f,
                maxTokens = 600,
                disableThinking = true
            )
            parseFoodNames(LlmClient.extractJson(json), maxFoods)
        }.onFailure {
            Log.w(TAG, "gap-food discovery failed: ${it.message}")
        }.getOrDefault(emptyList())
        if (names.isEmpty()) return ResearchOutcome(0, 0, 0)

        // Dedupe + skip everything Hoot already knows (knowledge base, seed
        // LUT, keys already on the sheet). Only genuinely NEW foods cost
        // resolution budget.
        val gapIds = gaps.map { it.nutrientId }
        var skipped = 0
        val newItems = ArrayList<Pair<String, String>>()
        val seen = HashSet<String>()
        for (name in names) {
            val key = FoodNormalizer.normalize(name)
            if (key.isBlank() || !seen.add(key)) continue
            val known = key in alreadyKnown ||
                knowledge.byName(key) != null ||
                SeedFoodLibrary.lookup(key) != null
            if (known) {
                // Already known — still tag the gap link (cheap, pure).
                runCatching { knowledge.tagResearchedFor(key, gapIds) }
                skipped++
            } else {
                newItems += key to FoodNormalizer.displayName(name)
            }
        }

        var added = 0
        if (newItems.isNotEmpty()) {
            val rateGuard = LlmRateGuard(s.llmCallsPerMinute) { delayMs ->
                kotlinx.coroutines.delay(delayMs)
            }
            val panels = resolver.resolveFoodsBatch(newItems, rateGuard)
            for ((key, panel) in panels) {
                val ok = runCatching {
                    resolver.persistFoodPanel(key, newItems.firstOrNull { it.first == key }?.second ?: key, panel)
                }.getOrDefault(false)
                if (ok) {
                    added++
                    runCatching { knowledge.tagResearchedFor(key, gapIds) }
                }
            }
        }
        Log.i(TAG, "gapResearch: discovered=${names.size} added=$added skippedKnown=$skipped")
        return ResearchOutcome(names.size, added, skipped)
    }

    private fun discoveryPrompt(gapList: String, maxFoods: Int): String = """
For each nutrient gap below, list whole single foods (ingredients a shopper
can buy) that are EXCEPTIONALLY rich sources — the kind dietitians recommend.

Nutrient gaps:
$gapList

Rules:
- Only SINGLE foods ("lentils", "sardines", "pumpkin seeds") — no dishes,
  meals, branded products, supplements or multivitamins.
- No repeats; vary across food groups (vegetables, legumes, fish, meat,
  nuts, fruits, grains).
- Prefer foods NOT already common on an average plate — the goal is variety.

Reply with ONLY compact JSON:
{"foods":["food 1","food 2", ...]}
At most $maxFoods foods total.
"""

    /** Tolerant parser for the discovery reply. */
    private fun parseFoodNames(json: String, max: Int): List<String> = runCatching {
        val arr = when {
            json.trimStart().startsWith("[") -> JSONArray(json)
            else -> org.json.JSONObject(json).optJSONArray("foods") ?: return emptyList()
        }
        (0 until arr.length()).mapNotNull { i ->
            arr.optString(i).trim().takeIf { it.isNotBlank() }
        }.distinct().take(max)
    }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "HootGapResearch"

        /** One research pass proposes at most this many foods. */
        const val MAX_FOODS_PER_PASS = 12

        /** Cap on gap nutrients named in one discovery call. */
        const val MAX_GAPS = 6

        private const val DISCOVERY_SYSTEM_PROMPT = """
You are a dietitian's food-finder API. Reply with ONLY one valid JSON object —
no prose, no markdown fences.
"""
    }
}
