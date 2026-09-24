package com.example.hoot.domain.nutrition

import android.util.Log
import com.example.hoot.data.local.AppSettings
import com.example.hoot.data.local.SettingsRepository
import com.example.hoot.data.local.entity.FoodEntity
import com.example.hoot.data.local.entity.FoodNutrientProfileEntity
import com.example.hoot.data.local.entity.IngredientEntity
import com.example.hoot.data.remote.LlmClient
import com.example.hoot.data.remote.LlmConfig
import com.example.hoot.data.repository.MealRepository
import com.example.hoot.data.repository.NutrientRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Nutrition resolution engine (docs/ARCHITECTURE.md §5, seed tier added
 * 2026-09 per docs/LLM_AUDIT.md):
 *
 *   ingredient/supplement text
 *     → (a) LookupCache (TTL) → expand cached profile to quantity
 *     → (a2) Bundled seed LUT ([SeedFoodLibrary], ~65 common whole foods,
 *         USDA-derived) — zero network, deterministic, persists like any
 *         other resolution so later runs are plain cache hits
 *     → (b) LLM full per-100 g panel for ALL tracked nutrients
 *     → (c) low confidence / failure → MCP web-search + web-reader → LLM extract
 *     → (d) persist Food + Profile + LookupCache + SourceRecords (always)
 *     → (e) total failure → ingredient stays unresolved (visible, retryable)
 *
 * Public API surface of the original engine; the tiers now live in
 * [SupplementResolver] (supplement paths), [WebResolution] (web fallback)
 * and [ResolutionStore] (persistence) — refactor 2026-09-22 P4.
 */
class NutritionResolver(
    private val nutrients: NutrientRepository,
    private val meals: MealRepository,
    private val settings: SettingsRepository,
    private val llm: LlmClient
) {
    private val store = ResolutionStore(nutrients)
    private val supplementTier = SupplementResolver(meals, settings, llm, store)
    private val webTier = WebResolution(llm)

    // ── Ingredient resolution ────────────────────────────────────────────

    /**
     * Resolves one [IngredientEntity] (raw text + amount/unit). Returns the
     * outcome; the ingredient row is updated in place (foodId/gramsEstimate).
     */
    suspend fun resolveIngredient(ingredientId: String): ResolveOutcome = withContext(Dispatchers.IO) {
        val ingredient = meals.ingredient(ingredientId)
            ?: return@withContext ResolveOutcome.Missing("ingredient $ingredientId vanished")
        val s = settings.current()
        val defs = store.nutrientRefs()
        if (defs.isEmpty()) return@withContext ResolveOutcome.Failed("nutrient definitions not seeded yet")

        val parsed = IngredientParser.parseSegment(ingredient.rawText)
            ?: IngredientParser.ParsedIngredient(
                rawText = ingredient.rawText,
                foodKey = FoodNormalizer.normalize(ingredient.rawText),
                displayName = FoodNormalizer.displayName(ingredient.rawText),
                quantity = ingredient.amount,
                unit = ingredient.unit
            )
        val key = parsed.foodKey
        if (key.isBlank()) return@withContext ResolveOutcome.Failed("cannot normalize '${ingredient.rawText}'")

        // Refresh parser-extracted quantity/unit onto the row when missing.
        if (ingredient.amount == null && parsed.quantity != null) {
            meals.updateIngredient(ingredient.copy(amount = parsed.quantity, unit = parsed.unit))
        }

        // (a) LookupCache
        val cached = store.cacheHit(key, s.cacheTtlDays)
        if (cached != null) {
            applyProfile(ingredient, cached.second, cached.first, parsed)
            return@withContext ResolveOutcome.Resolved(method = "cache", foodKey = key)
        }

        // (a2) Bundled seed LUT — a common whole food needs no model call.
        val seedPanel = seedPanel(key)
        if (seedPanel != null) {
            store.persistResolution(key, parsed.displayName, seedPanel, "seed", emptyList(), "usda-seed", s)
            finishIngredient(ingredient, key, parsed)
            return@withContext ResolveOutcome.Resolved(method = "seed", foodKey = key)
        }

        // (b) LLM direct panel
        val cfg = LlmConfig(s.baseUrl, s.apiKey, s.model)
        if (!cfg.configured) {
            Log.w(TAG, "LLM not configured — ingredient '$key' stays unresolved")
            return@withContext ResolveOutcome.NotConfigured
        }
        val llmPanel = runCatching {
            val json = llm.completeJson(
                cfg, RESOLUTION_SYSTEM_PROMPT.trimIndent(),
                NutritionPrompts.foodPanelUserPrompt(parsed.displayName, defs),
                temperature = 0.2f,
                disableThinking = s.disableThinking
            )
            NutritionPrompts.parsePanel(json, defs.associate { it.id to it.unit }.keys)
        }.onFailure { Log.w(TAG, "LLM panel failed for '$key': ${it.message}") }.getOrNull()

        if (llmPanel != null && llmPanel.confidence >= CONFIDENCE_THRESHOLD) {
            store.persistResolution(key, parsed.displayName, llmPanel, "llm", emptyList(), cfg.model, s)
            finishIngredient(ingredient, key, parsed)
            return@withContext ResolveOutcome.Resolved(method = "llm", foodKey = key)
        }

        // (a2 note): bundled seed LUT hit above short-circuits before (b).

        // (c) Web fallback (budgeted)
        if (!s.webSearchFallback) {
            Log.w(TAG, "web fallback disabled — '$key' unresolved (llmConf=${llmPanel?.confidence})")
            return@withContext ResolveOutcome.Failed("low confidence ${llmPanel?.confidence} and web fallback off")
        }
        val webOutcome = webTier.resolveViaWeb(key, parsed.displayName, defs, cfg, s)
        if (webOutcome != null) {
            store.persistResolution(key, parsed.displayName, webOutcome.panel, "web_usda", webOutcome.sources, cfg.model, s)
            finishIngredient(ingredient, key, parsed)
            return@withContext ResolveOutcome.Resolved(method = "web_usda", foodKey = key)
        }

        // (e) total failure — leave unresolved (visible state; retry re-queues).
        Log.w(TAG, "resolution failed for '$key'")
        ResolveOutcome.Failed("no confident panel (LLM + web) for '$key'")
    }

    // ── Batch (per-DISTINCT-FOOD) resolution ─────────────────────────────

    /**
     * Cache-first sweep over distinct food keys. Returns the TTL-fresh
     * (food, profile) pairs for every key the LookupCache can already serve —
     * these need ZERO LLM calls; the caller applies each profile to ALL
     * ingredient rows of the key in one bulk update.
     */
    suspend fun cachedProfiles(
        keys: Collection<String>
    ): Map<String, Pair<FoodEntity, FoodNutrientProfileEntity>> = withContext(Dispatchers.IO) {
        val s = settings.current()
        val out = LinkedHashMap<String, Pair<FoodEntity, FoodNutrientProfileEntity>>()
        for (key in keys) {
            val entry = nutrients.cacheLookup(key) ?: continue
            val food = entry.resolvedFoodId?.let { nutrients.food(it) } ?: continue
            val profile = entry.profileId?.let { nutrients.profileById(it) } ?: continue
            nutrients.cacheHit(key)
            val fresh = System.currentTimeMillis() - entry.fetchedAt <= s.cacheTtlDays * 86_400_000L
            // Stale entries are still usable (refreshed on next write) — same
            // policy as the single-row [ResolutionStore.cacheHit].
            if (food.id.isNotBlank() && profile.valuesJson.isNotBlank()) {
                out[key] = food to profile
                if (!fresh) Log.d(TAG, "stale cache used for '$key' (still applied)")
            }
        }
        out
    }

    /**
     * BULK profile application: links EVERY still-unresolved ingredient row
     * whose raw text normalizes to [food.normalizedName] to the cached food +
     * writes per-row gram estimates in one pass. Never touches rows that are
     * already resolved (foodId != null) — re-running stays idempotent.
     *
     * @return number of rows updated.
     */
    suspend fun applyProfileToGroup(
        food: FoodEntity,
        profile: FoodNutrientProfileEntity,
        ingredientIds: Collection<String>
    ): Int = withContext(Dispatchers.IO) {
        var updated = 0
        for (id in ingredientIds) {
            val ingredient = meals.ingredient(id) ?: continue
            // Idempotency guard: don't clobber resolution state of resolved rows.
            if (ingredient.foodId != null) continue
            // Safety: the row must still normalize to this group's key.
            val parsed = IngredientParser.parseSegment(ingredient.rawText)
                ?: IngredientParser.ParsedIngredient(
                    rawText = ingredient.rawText,
                    foodKey = FoodNormalizer.normalize(ingredient.rawText),
                    displayName = FoodNormalizer.displayName(ingredient.rawText),
                    quantity = ingredient.amount,
                    unit = ingredient.unit
                )
            if (parsed.foodKey != food.normalizedName) continue
            // Refresh parser-extracted quantity/unit onto the row when missing.
            val quantity = ingredient.amount ?: parsed.quantity
            val unit = ingredient.unit ?: parsed.unit
            val grams = IngredientParser.gramsEstimate(
                quantity = quantity, unit = unit,
                perItemGrams = food.typicalServingGrams,
                portionDefaultGrams = PORTION_DEFAULTS[food.normalizedName]
            ) ?: PORTION_DEFAULTS[food.normalizedName]
            meals.updateIngredient(
                ingredient.copy(foodId = food.id, amount = quantity, unit = unit, gramsEstimate = grams)
            )
            updated++
        }
        updated
    }

    /**
     * ONE batched LLM call for up to N distinct foods (full per-100 g schema
     * per food, keyed by foodKey). Returns ONLY the panels that parsed; the
     * caller falls back to [resolveSingleFood] for the missing keys.
     * Each batched call acquires one [LlmRateGuard] slot.
     */
    suspend fun resolveFoodsBatch(
        items: List<Pair<String, String>>,   // foodKey → display name
        rateGuard: LlmRateGuard?
    ): Map<String, NutritionPrompts.FoodPanel> = withContext(Dispatchers.IO) {
        // Seed-tier pre-filter: bundled foods never enter the LLM batch (the
        // processor persists them via [seedPanelsFor]; here they are dropped
        // so a stale caller cannot burn call budget on LUT-covered keys).
        val llmItems = items.filter { (key, _) -> seedPanel(key) == null }
        if (llmItems.isEmpty()) return@withContext emptyMap()
        val s = settings.current()
        val cfg = LlmConfig(s.baseUrl, s.apiKey, s.model)
        if (!cfg.configured) return@withContext emptyMap()
        val defs = store.nutrientRefs()
        if (defs.isEmpty()) return@withContext emptyMap()
        rateGuard?.acquire()
        runCatching {
            // Tolerant ONE-shot batch (LLM_AUDIT §6.1): completeJson's strict
            // re-ask threw away a truncated reply wholesale; chat + tolerant
            // parse salvages every key that parsed, and the caller's
            // single-food fallback re-asks ONLY the missing keys.
            val json = llm.chat(
                cfg, RESOLUTION_SYSTEM_PROMPT.trimIndent(),
                NutritionPrompts.foodPanelBatchUserPrompt(llmItems, defs),
                temperature = 0.2f,
                maxTokens = batchMaxTokens(llmItems.size),
                // Reasoning tokens share the completion budget — a batch of
                // panels must not compete with a chain-of-thought (finish=length).
                disableThinking = true
            )
            NutritionPrompts.parsePanelBatch(
                json, llmItems.map { it.first }.toSet(), defs.associate { it.id to it.unit }.keys
            )
        }.onFailure { Log.w(TAG, "batch LLM panel failed (${llmItems.size} foods): ${it.message}") }
            .getOrDefault(emptyMap())
    }

    /**
     * Seed-tier bulk lookup: the [SeedFoodLibrary] panels for every requested
     * key the LUT covers. The processor persists these through
     * [persistFoodPanel] exactly like LLM batch replies — same Food + Profile
     * + LookupCache + Sources path, `resolutionMethod = "seed"` — so seed
     * foods become ordinary cache hits with zero LLM spend.
     */
    fun seedPanelsFor(
        items: List<Pair<String, String>>
    ): Map<String, NutritionPrompts.FoodPanel> = buildMap {
        for ((key, displayName) in items) {
            seedPanel(key)?.let { put(key, it.copy(displayName = displayName)) }
        }
    }

    /** Bundled panel for one normalized key; null when the LUT doesn't cover it. */
    private fun seedPanel(key: String): NutritionPrompts.FoodPanel? {
        val seed = SeedFoodLibrary.lookup(key) ?: return null
        return NutritionPrompts.FoodPanel(
            displayName = seed.displayName,
            values = seed.per100,
            confidence = 0.95,
            typicalServingGrams = seed.typicalServingGrams,
            imageSearchTerm = seed.displayName.lowercase(),
            origin = "seed"
        )
    }

    /**
     * Persists an already-parsed panel (from a batch reply) for one food key:
     * canonicalize → Food + Profile + LookupCache + Sources. Returns false
     * when nothing was persisted (caller keeps the rows unresolved/retryable).
     */
    suspend fun persistFoodPanel(
        key: String,
        displayName: String,
        panel: NutritionPrompts.FoodPanel
    ): Boolean = withContext(Dispatchers.IO) {
        val s = settings.current()
        if (store.nutrientRefs().isEmpty()) return@withContext false
        val cfg = LlmConfig(s.baseUrl, s.apiKey, s.model)
        // Origin-aware provenance: bundled seed rows are USDA data, not model
        // output — record them as "seed" with a usda-seed pseudo-source.
        val method = if (panel.origin == "seed") "seed" else "llm"
        val model = if (panel.origin == "seed") "usda-seed" else cfg.model.ifBlank { "batch" }
        runCatching {
            store.persistResolution(key, displayName, panel, method, emptyList(), model, s)
            nutrients.cacheLookup(key) != null
        }.getOrDefault(false)
    }

    /**
     * Single-food fallback + web-escalation path, keyed (not row-bound):
     * LLM panel → confidence gate → budgeted MCP web fallback → persist.
     * Resolution state lands in Food/Profile/LookupCache/Source; the CALLER
     * bulk-applies the resulting profile to the group's ingredient rows via
     * [applyProfileToGroup].
     */
    suspend fun resolveSingleFood(
        key: String,
        displayName: String,
        rateGuard: LlmRateGuard?
    ): ResolveOutcome = withContext(Dispatchers.IO) {
        val s = settings.current()
        val defs = store.nutrientRefs()
        if (defs.isEmpty()) return@withContext ResolveOutcome.Failed("nutrient definitions not seeded yet")

        // (a2) Seed LUT before the single-food LLM call — batch replies can
        // drop keys; a covered key still resolves without any model call.
        val seedPanel = seedPanel(key)
        if (seedPanel != null) {
            store.persistResolution(key, displayName, seedPanel, "seed", emptyList(), "usda-seed", s)
            return@withContext ResolveOutcome.Resolved(method = "seed", foodKey = key)
        }

        val cfg = LlmConfig(s.baseUrl, s.apiKey, s.model)
        if (!cfg.configured) return@withContext ResolveOutcome.NotConfigured

        // (b) single-food LLM panel (second and final LLM try for this key).
        rateGuard?.acquire()
        val llmPanel = runCatching {
            val json = llm.completeJson(
                cfg, RESOLUTION_SYSTEM_PROMPT.trimIndent(),
                NutritionPrompts.foodPanelUserPrompt(displayName, defs),
                temperature = 0.2f,
                disableThinking = s.disableThinking
            )
            NutritionPrompts.parsePanel(json, defs.associate { it.id to it.unit }.keys)
        }.onFailure { Log.w(TAG, "single LLM panel failed for '$key': ${it.message}") }.getOrNull()

        if (llmPanel != null && llmPanel.confidence >= CONFIDENCE_THRESHOLD) {
            store.persistResolution(key, displayName, llmPanel, "llm", emptyList(), cfg.model, s)
            return@withContext ResolveOutcome.Resolved(method = "llm", foodKey = key)
        }

        // (c) Web fallback — only for genuinely uncertain foods, budgeted.
        if (!s.webSearchFallback) {
            return@withContext ResolveOutcome.Failed("low confidence ${llmPanel?.confidence} and web fallback off")
        }
        val webOutcome = webTier.resolveViaWeb(key, displayName, defs, cfg, s)
        if (webOutcome != null) {
            store.persistResolution(key, displayName, webOutcome.panel, "web_usda", webOutcome.sources, cfg.model, s)
            return@withContext ResolveOutcome.Resolved(method = "web_usda", foodKey = key)
        }
        ResolveOutcome.Failed("no confident panel (LLM + web) for '$key'")
    }

    /** ONE batched LLM call for supplement name-groups (delegates). */
    suspend fun resolveSupplementGroupsBatch(
        items: List<Pair<String, String>>,
        rateGuard: LlmRateGuard?
    ): Map<String, NutritionPrompts.FoodPanel> =
        supplementTier.resolveSupplementGroupsBatch(items, rateGuard)

    /** Bulk supplement panel application (delegates). */
    suspend fun applySupplementPanel(
        supplementIds: Collection<String>,
        panel: NutritionPrompts.FoodPanel
    ): Int = supplementTier.applySupplementPanel(supplementIds, panel)

    /** Single-supplement group fallback (delegates). */
    suspend fun resolveSupplementGroupSingle(
        supplementIds: Collection<String>,
        rateGuard: LlmRateGuard?,
        allowLlm: Boolean = true
    ): ResolveOutcome = supplementTier.resolveSupplementGroupSingle(supplementIds, rateGuard, allowLlm)

    // Attempt-cap bookkeeping moved to the processor's drain-start CLAIM
    // (kill-safe, 2026-09-23): rows are bumped when queued for a drain, not
    // when their batch finishes — a killed process no longer loses the count.

    // ── Supplement resolution (delegates) ────────────────────────────────

    /** Single supplement resolution — label parse then LLM (delegates). */
    suspend fun resolveSupplement(supplementId: String): ResolveOutcome =
        supplementTier.resolveSupplement(supplementId)

    /** Cache-hit path: links the ingredient to the cached food + gram estimate. */
    private suspend fun applyProfile(
        ingredient: IngredientEntity,
        profile: FoodNutrientProfileEntity,
        food: FoodEntity,
        parsed: IngredientParser.ParsedIngredient
    ) {
        val quantity = ingredient.amount ?: parsed.quantity
        val unit = ingredient.unit ?: parsed.unit
        val grams = IngredientParser.gramsEstimate(
            quantity = quantity, unit = unit,
            perItemGrams = food.typicalServingGrams,
            portionDefaultGrams = PORTION_DEFAULTS[food.normalizedName]
        ) ?: PORTION_DEFAULTS[food.normalizedName]
        meals.updateIngredient(
            ingredient.copy(
                foodId = food.id,
                amount = quantity,
                unit = unit,
                gramsEstimate = grams
            )
        )
    }

    /** Links the ingredient to its food + writes the gram estimate. */
    private suspend fun finishIngredient(
        ingredient: IngredientEntity,
        key: String,
        parsed: IngredientParser.ParsedIngredient
    ) {
        val food = nutrients.foodByName(key) ?: return
        val quantity = ingredient.amount ?: parsed.quantity
        val unit = ingredient.unit ?: parsed.unit
        val grams = IngredientParser.gramsEstimate(
            quantity = quantity, unit = unit,
            perItemGrams = food.typicalServingGrams,
            portionDefaultGrams = PORTION_DEFAULTS[key]
        ) ?: PORTION_DEFAULTS[key] // count-unit without hint → portion default
        meals.updateIngredient(
            ingredient.copy(
                foodId = food.id,
                amount = quantity,
                unit = unit,
                gramsEstimate = grams
            )
        )
    }

    sealed interface ResolveOutcome {
        data class Resolved(val method: String, val foodKey: String) : ResolveOutcome
        data class Failed(val reason: String) : ResolveOutcome
        data class Missing(val reason: String) : ResolveOutcome
        data object NotConfigured : ResolveOutcome
    }

    companion object {
        private const val TAG = "HootResolver"

        /** Below this the pipeline escalates to the web tier (ARCHITECTURE.md §5). */
        const val CONFIDENCE_THRESHOLD = 0.75

        /** Authoritative hosts preferred in web ranking + publisher tagging. */
        val AUTHORITATIVE = listOf(
            "fdc.nal.usda.gov", "usda.gov", "ods.od.nih.gov", "nih.gov",
            "pubmed", "nutritiondata", "fine dining lovers" // last: harmless fuzzy tail
        )

        /** Gram defaults for unspecified quantities (docs/NUTRIENTS.md §7 rule 1). */
        val PORTION_DEFAULTS: Map<String, Double> = mapOf(
            "spinach" to 80.0, "apple" to 180.0, "banana" to 118.0, "egg" to 50.0,
            "rice" to 160.0, "bread" to 30.0, "slice" to 30.0, "chicken" to 150.0,
            "salmon" to 150.0, "yogurt" to 170.0, "milk" to 240.0, "potato" to 173.0,
            "tomato" to 123.0, "avocado" to 150.0, "oat" to 40.0, "lentil" to 150.0,
            "bean" to 130.0, "chickpea" to 130.0, "pasta" to 140.0, "salad" to 100.0
        )

        /** Unambiguous label → nutrient map for the no-LLM fast path. */
        val KNOWN_SUPPLEMENT_NUTRIENTS: Map<String, String> = mapOf(
            "magnesium" to "magnesium", "zinc" to "zinc", "iron" to "iron",
            "calcium" to "calcium", "potassium" to "potassium", "iodine" to "iodine",
            "selenium" to "selenium", "vitamin d" to "vitamin_d",
            "vitamin d3" to "vitamin_d", "vitamin b12" to "vitamin_b12",
            "vitamin c" to "vitamin_c", "folate" to "vitamin_b9",
            "folic acid" to "vitamin_b9", "vitamin b6" to "vitamin_b6",
            "omega-3" to "omega3_epa_dha", "fish oil" to "omega3_epa_dha",
            "creatine" to "protein", "whey" to "protein", "protein" to "protein"
        )
    }
}
