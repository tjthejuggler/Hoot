package com.example.hoot.domain.nutrition

import android.util.Log
import com.example.hoot.data.local.AppSettings
import com.example.hoot.data.local.entity.FoodEntity
import com.example.hoot.data.local.entity.FoodNutrientProfileEntity
import com.example.hoot.data.local.entity.LookupCacheEntity
import com.example.hoot.data.local.entity.SourceEntity
import com.example.hoot.data.repository.NutrientRepository
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Shared system prompt for every resolution call (facade + tiers).
 */
internal const val RESOLUTION_SYSTEM_PROMPT = """
You are Hoot's nutrition database API. Values must be realistic USDA-grade.
Reply with ONLY one valid JSON object — no prose, no markdown fences.
"""

/** Response budget scaling with batch size (see facade KDoc). */
internal fun batchMaxTokens(n: Int): Int = (600 * n + 400).coerceIn(4_000, 8_000)

/**
 * Persistence tier of the resolution pipeline (extracted from
 * [NutritionResolver], refactor 2026-09-22 P4): cache reads honoring TTL,
 * canonicalization + Food/Profile/LookupCache/Source upserts, and the
 * tracked-nutrient refs every prompt tier needs.
 */
internal class ResolutionStore(
    private val nutrients: NutrientRepository
) {
    /** Tracked-nutrient refs for prompts, straight from the seeded table. */
    suspend fun nutrientRefs(): List<NutritionPrompts.NutrientRef> =
        nutrients.definitionsAll().map { NutritionPrompts.NutrientRef(it.id, it.name, it.unit) }

    suspend fun unitMap(): Map<String, String> =
        nutrients.definitionsAll().associate { it.id to it.unit }

    /** Cache read honoring TTL; bumps hitCount and returns (food, profile). */
    suspend fun cacheHit(
        key: String,
        ttlDays: Int
    ): Pair<FoodEntity, FoodNutrientProfileEntity>? {
        val entry = nutrients.cacheLookup(key) ?: return null
        val fresh = System.currentTimeMillis() - entry.fetchedAt <= ttlDays * 86_400_000L
        val food = entry.resolvedFoodId?.let { nutrients.food(it) } ?: return null
        val profile = entry.profileId?.let { nutrients.profileById(it) } ?: return null
        nutrients.cacheHit(key)
        return if (fresh) food to profile else food to profile // stale still usable; refreshed on next write
    }

    /** Upserts Food + Profile + LookupCache + Source rows (always record sources). */
    suspend fun persistResolution(
        key: String,
        displayName: String,
        panel: NutritionPrompts.FoodPanel,
        method: String,
        sourceUrls: List<String>,
        llmModel: String,
        s: AppSettings
    ) {
        // (source-record shaping stays origin-aware below — see `sources` list)
        val food = ensureFood(key, panel.displayName.ifBlank { displayName }, imageTerm = panel.imageSearchTerm)
        val canonicalUnits = unitMap()
        // Canonicalize LLM-reported amounts to canonical nutrient units. Ids
        // are already alias-folded by [NutritionPrompts.parsePanel]; fold
        // again defensively (web-merge path) and SKIP ids without a seeded
        // definition — a fabricated key must not land in valuesJson where it
        // would silently never aggregate (the fiber/iodine failure mode).
        val canonicalValues = JSONObject()
        for ((rawKey, amount) in panel.values) {
            val nutrientId = NutrientKeys.canonicalId(rawKey) ?: rawKey
            val unit = canonicalUnits[nutrientId] ?: continue
            val canon = Units.canonicalNutrientAmount(nutrientId, amount, null, unit) ?: continue
            canonicalValues.put(nutrientId, canon)
        }
        val profile = FoodNutrientProfileEntity(
            id = UUID.randomUUID().toString(),
            foodId = food.id,
            perAmount = 100.0,
            perUnit = "g",
            valuesJson = canonicalValues.toString(),
            confidence = panel.confidence,
            resolutionMethod = method
        )
        nutrients.upsertProfile(profile)
        // Refresh food serving hint when the panel provided one.
        if (panel.typicalServingGrams != null && food.typicalServingGrams == null) {
            nutrients.upsertFood(food.copy(typicalServingGrams = panel.typicalServingGrams))
        }
        val existing = nutrients.cacheLookup(key)
        nutrients.cachePut(
            LookupCacheEntity(
                normalizedKey = key,
                resolvedFoodId = food.id,
                profileId = profile.id,
                sourceUrlsJson = JSONArray(sourceUrls).toString(),
                fetchedAt = System.currentTimeMillis(),
                hitCount = existing?.hitCount ?: 0
            )
        )
        // SourceRecords: provenance + fetched URLs (always). Bundled seed rows
        // cite the USDA-derived LUT instead of an llm:// pseudo-URL.
        val isSeed = panel.origin == "seed"
        val primarySource = if (isSeed) {
            SourceEntity(
                id = UUID.randomUUID().toString(), lookupKey = key,
                url = "seed://usda-sr-legacy",
                title = "Bundled USDA SR Legacy seed (Hoot LUT)", publisher = "USDA",
                fetchedAt = System.currentTimeMillis(), toolName = null
            )
        } else {
            SourceEntity(
                id = UUID.randomUUID().toString(), lookupKey = key,
                url = "llm://chat-completions/$llmModel",
                title = "LLM panel ($llmModel)", publisher = llmModel,
                fetchedAt = System.currentTimeMillis(), toolName = null
            )
        }
        val sources = buildList {
            add(primarySource)
            for (url in sourceUrls) add(
                SourceEntity(
                    id = UUID.randomUUID().toString(), lookupKey = key,
                    url = url, title = null,
                    publisher = NutritionResolver.AUTHORITATIVE.firstOrNull { url.contains(it, true) },
                    fetchedAt = System.currentTimeMillis(), toolName = "web-search-prime"
                )
            )
        }
        nutrients.recordSources(sources)
    }

    suspend fun ensureFood(
        key: String,
        displayName: String,
        category: String? = null,
        isSupplement: Boolean = false,
        imageTerm: String? = null
    ): FoodEntity {
        nutrients.foodByName(key)?.let { existing ->
            return if (imageTerm != null && existing.imageSearchTerm == null)
                existing.copy(imageSearchTerm = imageTerm).also { nutrients.upsertFood(it) }
            else existing
        }
        val food = FoodEntity(
            id = UUID.randomUUID().toString(),
            normalizedName = key,
            displayName = displayName.ifBlank { FoodNormalizer.displayName(key) },
            category = category,
            isSupplement = isSupplement,
            createdAt = System.currentTimeMillis(),
            imageSearchTerm = imageTerm
        )
        nutrients.upsertFood(food)
        return food
    }

    companion object {
        private const val TAG = "HootResolver"
    }
}
