package com.example.hoot.domain.nutrition

import android.util.Log
import com.example.hoot.data.local.SettingsRepository
import com.example.hoot.data.local.entity.FoodEntity
import com.example.hoot.data.local.entity.FoodNutrientProfileEntity
import com.example.hoot.data.local.entity.IngredientEntity
import com.example.hoot.data.local.entity.LookupCacheEntity
import com.example.hoot.data.local.entity.SourceEntity
import com.example.hoot.data.local.entity.SupplementEntity
import com.example.hoot.data.remote.LlmClient
import com.example.hoot.data.remote.LlmConfig
import com.example.hoot.data.remote.McpWebTools
import com.example.hoot.data.repository.MealRepository
import com.example.hoot.data.repository.NutrientRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
 * Supplements resolve their own nutrient contributions per serving (label
 * parse first, LLM assist, cached through the same lookup pipeline).
 */
class NutritionResolver(
    private val nutrients: NutrientRepository,
    private val meals: MealRepository,
    private val settings: SettingsRepository,
    private val llm: LlmClient
) {

    /** Per-process MCP session reuse (reconnects only when settings change). */
    private var webTools: McpWebTools? = null
    private var webToolsKey: String? = null
    private val webToolsMutex = Any()

    // ── Ingredient resolution ────────────────────────────────────────────

    /**
     * Resolves one [IngredientEntity] (raw text + amount/unit). Returns the
     * outcome; the ingredient row is updated in place (foodId/gramsEstimate).
     */
    suspend fun resolveIngredient(ingredientId: String): ResolveOutcome = withContext(Dispatchers.IO) {
        val ingredient = meals.ingredient(ingredientId)
            ?: return@withContext ResolveOutcome.Missing("ingredient $ingredientId vanished")
        val s = settings.current()
        val defs = nutrientRefs()
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
        val cached = cacheHit(key, s.cacheTtlDays)
        if (cached != null) {
            applyProfile(ingredient, cached.second, cached.first, parsed)
            return@withContext ResolveOutcome.Resolved(method = "cache", foodKey = key)
        }

        // (a2) Bundled seed LUT — a common whole food needs no model call.
        val seedPanel = seedPanel(key)
        if (seedPanel != null) {
            persistResolution(key, parsed.displayName, seedPanel, "seed", emptyList(), "usda-seed", s)
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
                cfg, SYSTEM_PROMPT.trimIndent(),
                NutritionPrompts.foodPanelUserPrompt(parsed.displayName, defs),
                temperature = 0.2f,
                disableThinking = s.disableThinking
            )
            NutritionPrompts.parsePanel(json, defs.associate { it.id to it.unit }.keys)
        }.onFailure { Log.w(TAG, "LLM panel failed for '$key': ${it.message}") }.getOrNull()

        if (llmPanel != null && llmPanel.confidence >= CONFIDENCE_THRESHOLD) {
            persistResolution(key, parsed.displayName, llmPanel, "llm", emptyList(), cfg.model, s)
            finishIngredient(ingredient, key, parsed)
            return@withContext ResolveOutcome.Resolved(method = "llm", foodKey = key)
        }

        // (a2 note): bundled seed LUT hit above short-circuits before (b).

        // (c) Web fallback (budgeted)
        if (!s.webSearchFallback) {
            Log.w(TAG, "web fallback disabled — '$key' unresolved (llmConf=${llmPanel?.confidence})")
            return@withContext ResolveOutcome.Failed("low confidence ${llmPanel?.confidence} and web fallback off")
        }
        val webOutcome = resolveViaWeb(key, parsed.displayName, defs, cfg, s)
        if (webOutcome != null) {
            persistResolution(key, parsed.displayName, webOutcome.panel, "web_usda", webOutcome.sources, cfg.model, s)
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
            // policy as the single-row [cacheHit].
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
        val defs = nutrientRefs()
        if (defs.isEmpty()) return@withContext emptyMap()
        rateGuard?.acquire()
        runCatching {
            // Tolerant ONE-shot batch (LLM_AUDIT §6.1): completeJson's strict
            // re-ask threw away a truncated reply wholesale; chat + tolerant
            // parse salvages every key that parsed, and the caller's
            // single-food fallback re-asks ONLY the missing keys.
            val json = llm.chat(
                cfg, SYSTEM_PROMPT.trimIndent(),
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
        if (nutrientRefs().isEmpty()) return@withContext false
        val cfg = LlmConfig(s.baseUrl, s.apiKey, s.model)
        // Origin-aware provenance: bundled seed rows are USDA data, not model
        // output — record them as "seed" with a usda-seed pseudo-source.
        val method = if (panel.origin == "seed") "seed" else "llm"
        val model = if (panel.origin == "seed") "usda-seed" else cfg.model.ifBlank { "batch" }
        runCatching {
            persistResolution(key, displayName, panel, method, emptyList(), model, s)
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
        val defs = nutrientRefs()
        if (defs.isEmpty()) return@withContext ResolveOutcome.Failed("nutrient definitions not seeded yet")

        // (a2) Seed LUT before the single-food LLM call — batch replies can
        // drop keys; a covered key still resolves without any model call.
        val seedPanel = seedPanel(key)
        if (seedPanel != null) {
            persistResolution(key, displayName, seedPanel, "seed", emptyList(), "usda-seed", s)
            return@withContext ResolveOutcome.Resolved(method = "seed", foodKey = key)
        }

        val cfg = LlmConfig(s.baseUrl, s.apiKey, s.model)
        if (!cfg.configured) return@withContext ResolveOutcome.NotConfigured

        // (b) single-food LLM panel (second and final LLM try for this key).
        rateGuard?.acquire()
        val llmPanel = runCatching {
            val json = llm.completeJson(
                cfg, SYSTEM_PROMPT.trimIndent(),
                NutritionPrompts.foodPanelUserPrompt(displayName, defs),
                temperature = 0.2f,
                disableThinking = s.disableThinking
            )
            NutritionPrompts.parsePanel(json, defs.associate { it.id to it.unit }.keys)
        }.onFailure { Log.w(TAG, "single LLM panel failed for '$key': ${it.message}") }.getOrNull()

        if (llmPanel != null && llmPanel.confidence >= CONFIDENCE_THRESHOLD) {
            persistResolution(key, displayName, llmPanel, "llm", emptyList(), cfg.model, s)
            return@withContext ResolveOutcome.Resolved(method = "llm", foodKey = key)
        }

        // (c) Web fallback — only for genuinely uncertain foods, budgeted.
        if (!s.webSearchFallback) {
            return@withContext ResolveOutcome.Failed("low confidence ${llmPanel?.confidence} and web fallback off")
        }
        val webOutcome = resolveViaWeb(key, displayName, defs, cfg, s)
        if (webOutcome != null) {
            persistResolution(key, displayName, webOutcome.panel, "web_usda", webOutcome.sources, cfg.model, s)
            return@withContext ResolveOutcome.Resolved(method = "web_usda", foodKey = key)
        }
        ResolveOutcome.Failed("no confident panel (LLM + web) for '$key'")
    }

    /**
     * ONE batched LLM call for supplement name-groups (per-serving panels
     * keyed by group key). Returns only the panels that parsed.
     */
    suspend fun resolveSupplementGroupsBatch(
        items: List<Pair<String, String>>,   // groupKey → display label
        rateGuard: LlmRateGuard?
    ): Map<String, NutritionPrompts.FoodPanel> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext emptyMap()
        val s = settings.current()
        val cfg = LlmConfig(s.baseUrl, s.apiKey, s.model)
        if (!cfg.configured) return@withContext emptyMap()
        val defs = nutrientRefs()
        if (defs.isEmpty()) return@withContext emptyMap()
        rateGuard?.acquire()
        runCatching {
            // Tolerant ONE-shot batch — same rationale as resolveFoodsBatch.
            val json = llm.chat(
                cfg, SYSTEM_PROMPT.trimIndent(),
                NutritionPrompts.supplementBatchUserPrompt(items, defs),
                temperature = 0.2f,
                maxTokens = batchMaxTokens(items.size),
                disableThinking = true
            )
            NutritionPrompts.parsePanelBatch(
                json, items.map { it.first }.toSet(), defs.associate { it.id to it.unit }.keys
            )
        }.onFailure { Log.w(TAG, "batch supplement panel failed (${items.size} groups): ${it.message}") }
            .getOrDefault(emptyMap())
    }

    /**
     * Applies a parsed per-serving panel to EVERY still-unresolved supplement
     * row of one name-group (never touches rows that already carry
     * contributions — idempotent). @return rows updated.
     */
    suspend fun applySupplementPanel(
        supplementIds: Collection<String>,
        panel: NutritionPrompts.FoodPanel
    ): Int = withContext(Dispatchers.IO) {
        var updated = 0
        val contributions = JSONObject()
        for ((nutrientId, amount) in panel.values) contributions.put(nutrientId, amount)
        for (id in supplementIds) {
            val entity = meals.supplementsByIds(listOf(id)).firstOrNull() ?: continue
            val unresolved = entity.nutrientContributions == "[]" && entity.resolvedFoodId == null
            if (!unresolved) continue
            saveContributions(entity, panel.displayName, contributions, panel.imageSearchTerm)
            updated++
        }
        updated
    }

    /**
     * Single-supplement fallback for a group whose batch entry failed:
     * direct-label parse first (free), then one LLM panel; the result is
     * replicated to every remaining unresolved row of the group. With
     * [allowLlm]=false only the FREE label-parse pass runs and the outcome is
     * [ResolveOutcome.NotConfigured] when rows still need the LLM — used by
     * the processor's pre-batch sweep so label parses never burn call slots.
     */
    suspend fun resolveSupplementGroupSingle(
        supplementIds: Collection<String>,
        rateGuard: LlmRateGuard?,
        allowLlm: Boolean = true
    ): ResolveOutcome = withContext(Dispatchers.IO) {
        val rows = meals.supplementsByIds(supplementIds.toList())
            .filter { it.nutrientContributions == "[]" && it.resolvedFoodId == null }
        if (rows.isEmpty()) return@withContext ResolveOutcome.Missing("group already resolved")
        val s = settings.current()
        val defs = nutrientRefs()
        if (defs.isEmpty()) return@withContext ResolveOutcome.Failed("nutrient definitions not seeded yet")
        val unitsById = defs.associate { it.id to it.unit }

        // Free direct-label parse per row ("Magnesium 400 mg").
        var labelResolved = 0
        for (entity in rows) {
            val labelParsed = SupplementLabelParser.parse(entity.rawText.ifBlank { entity.label })
            val direct = directContributions(labelParsed, unitsById)
            if (direct != null) {
                saveContributions(entity, labelParsed!!.normalizedName, direct)
                labelResolved++
            }
        }
        if (labelResolved == rows.size) {
            return@withContext ResolveOutcome.Resolved(method = "label", foodKey = rows.first().label)
        }

        val remaining = rows.drop(labelResolved)
        val cfg = LlmConfig(s.baseUrl, s.apiKey, s.model)
        if (!cfg.configured || !allowLlm) return@withContext ResolveOutcome.NotConfigured
        val first = remaining.first()
        rateGuard?.acquire()
        val panel = runCatching {
            val json = llm.completeJson(
                cfg, SYSTEM_PROMPT.trimIndent(),
                NutritionPrompts.supplementUserPrompt(first.rawText.ifBlank { first.label }, defs),
                temperature = 0.2f,
                disableThinking = s.disableThinking
            )
            NutritionPrompts.parsePanel(json, unitsById.keys)
        }.onFailure { Log.w(TAG, "supplement LLM failed for '${first.label}': ${it.message}") }.getOrNull()
            ?: return@withContext ResolveOutcome.Failed("supplement '${first.label}' unparseable")

        val contributions = JSONObject()
        for ((nutrientId, amount) in panel.values) contributions.put(nutrientId, amount)
        for (entity in remaining) {
            saveContributions(entity, panel.displayName, contributions, panel.imageSearchTerm)
        }
        ResolveOutcome.Resolved(method = "llm", foodKey = panel.displayName)
    }

    /** Attempt-cap bookkeeping for every row of a failed food group. */
    suspend fun markFoodGroupFailed(group: FoodGrouper.FoodGroup) {
        for (id in group.ingredientIds) {
            runCatching { meals.ingredient(id)?.let { meals.markIngredientFailed(it) } }
                .onFailure { Log.w(TAG, "markIngredientFailed($id) failed", it) }
        }
    }

    /** Attempt-cap bookkeeping for every row of a failed supplement group. */
    suspend fun markSupplementGroupFailed(group: FoodGrouper.SupplementGroup) {
        for (id in group.supplementIds) {
            runCatching { meals.supplementsByIds(listOf(id)).firstOrNull()?.let { meals.markSupplementFailed(it) } }
                .onFailure { Log.w(TAG, "markSupplementFailed($id) failed", it) }
        }
    }

    /**
     * Response budget scaling with batch size. A 46-nutrient panel with
     * compact formatting costs ~500-600 tokens; keep the batch safely inside
     * common provider completion caps (many gateways hard-stop near 8k).
     */
    private fun batchMaxTokens(n: Int): Int = (600 * n + 400).coerceIn(4_000, 8_000)

    /** (c) web-search-prime → web-reader → LLM extraction, honoring budget. */
    private suspend fun resolveViaWeb(
        key: String,
        displayName: String,
        defs: List<NutritionPrompts.NutrientRef>,
        cfg: LlmConfig,
        s: com.example.hoot.data.local.AppSettings
    ): WebOutcome? {
        val tools = getWebTools(s) ?: return null
        val query = "$key cooked nutrition per 100g USDA"
        val results = tools.searchWeb(query)
        if (results.isEmpty()) return null
        // Prefer authoritative hosts; keep per-URL dedup (ARCHITECTURE.md §10).
        val ranked = results.sortedByDescending { r -> AUTHORITATIVE.firstOrNull { r.url.contains(it, ignoreCase = true) } != null }
        var combinedValues: MutableMap<String, Double>? = null
        var bestConfidence = 0.0
        var serving: Double? = null
        var image: String? = null
        val usedUrls = mutableListOf<String>()
        for (r in ranked.take(2)) {
            if (tools.remainingBudget <= 0) break
            val pageText = tools.readUrl(r.url)
            if (pageText.isBlank()) continue
            val panel = runCatching {
                val json = llm.completeJson(
                    cfg, SYSTEM_PROMPT.trimIndent(),
                    NutritionPrompts.webExtractUserPrompt(displayName, r.url, pageText, defs),
                    temperature = 0.1f,
                    disableThinking = s.disableThinking
                )
                NutritionPrompts.parsePanel(json, defs.associate { it.id to it.unit }.keys)
            }.getOrNull() ?: continue
            if (combinedValues == null) combinedValues = HashMap()
            for ((k, v) in panel.values) combinedValues!!.putIfAbsent(k, v)
            bestConfidence = maxOf(bestConfidence, panel.confidence)
            serving = serving ?: panel.typicalServingGrams
            image = image ?: panel.imageSearchTerm
            usedUrls += r.url
            if (bestConfidence >= CONFIDENCE_THRESHOLD && combinedValues!!.size >= 8) break
        }
        val values = combinedValues ?: return null
        if (values.isEmpty()) return null
        return WebOutcome(
            panel = NutritionPrompts.FoodPanel(displayName, values, bestConfidence.coerceAtLeast(0.6), serving, image),
            sources = usedUrls
        )
    }

    // ── Supplement resolution ────────────────────────────────────────────

    /**
     * Resolves nutrient contributions for a [SupplementEntity]: tolerant
     * "label:value" parse first (no lookup round-trip), LLM per-serving panel
     * for plain text; contributions stored as canonical-unit JSON on the row.
     */
    suspend fun resolveSupplement(supplementId: String): ResolveOutcome = withContext(Dispatchers.IO) {
        val entity = findSupplement(supplementId)
            ?: return@withContext ResolveOutcome.Missing("supplement $supplementId vanished")
        val s = settings.current()
        val defs = nutrientRefs()
        if (defs.isEmpty()) return@withContext ResolveOutcome.Failed("nutrient definitions not seeded yet")
        val unitsById = defs.associate { it.id to it.unit }

        // 1) Direct label parse — "Magnesium 400 mg" needs no LLM round-trip.
        val labelParsed = SupplementLabelParser.parse(entity.rawText.ifBlank { entity.label })
        val direct = directContributions(labelParsed, unitsById)
        if (direct != null) {
            saveContributions(entity, labelParsed!!.normalizedName, direct)
            return@withContext ResolveOutcome.Resolved(method = "label", foodKey = labelParsed.normalizedName)
        }

        // 2) LLM assist for plain text.
        val cfg = LlmConfig(s.baseUrl, s.apiKey, s.model)
        if (!cfg.configured) return@withContext ResolveOutcome.NotConfigured
        val panel = runCatching {
            val json = llm.completeJson(
                cfg, SYSTEM_PROMPT.trimIndent(),
                NutritionPrompts.supplementUserPrompt(entity.rawText.ifBlank { entity.label }, defs),
                temperature = 0.2f,
                disableThinking = s.disableThinking
            )
            NutritionPrompts.parsePanel(json, unitsById.keys)
        }.onFailure { Log.w(TAG, "supplement LLM failed for '${entity.label}': ${it.message}") }.getOrNull()

        if (panel == null) return@withContext ResolveOutcome.Failed("supplement '${entity.label}' unparseable")
        val contributions = JSONObject()
        for ((nutrientId, amount) in panel.values) contributions.put(nutrientId, amount)
        saveContributions(entity, panel.displayName, contributions, panel.imageSearchTerm)
        ResolveOutcome.Resolved(method = "llm", foodKey = panel.displayName)
    }

    /** Known-supplement quick path: label → elemental nutrient, when unambiguous. */
    private fun directContributions(
        parsed: SupplementLabelParser.ParsedSupplement?,
        unitsById: Map<String, String>
    ): JSONObject? {
        if (parsed?.doseAmount == null || parsed.doseUnit == null) return null
        val nutrientId = KNOWN_SUPPLEMENT_NUTRIENTS[parsed.normalizedName] ?: return null
        val canonical = Units.canonicalNutrientAmount(
            nutrientId, parsed.doseAmount, parsed.doseUnit, unitsById[nutrientId] ?: return null
        ) ?: return null
        return JSONObject().put(nutrientId, canonical)
    }

    private suspend fun findSupplement(id: String): SupplementEntity? =
        meals.unresolvedSupplements().firstOrNull { it.id == id }
            ?: meals.supplementsByIds(listOf(id)).firstOrNull()

    private suspend fun saveContributions(
        entity: SupplementEntity,
        displayName: String,
        contributions: JSONObject,
        imageTerm: String? = null
    ) {
        val food = ensureFood(
            FoodNormalizer.normalize(displayName), displayName,
            category = "supplement", isSupplement = true,
            imageTerm = imageTerm
        )
        meals.upsertSupplement(
            entity.copy(
                resolvedFoodId = food.id,
                nutrientContributions = contributions.toString(),
                doseAmount = entity.doseAmount,
                doseUnit = entity.doseUnit
            )
        )
    }

    // ── Persistence tier ─────────────────────────────────────────────────

    /** Cache read honoring TTL; bumps hitCount and returns (food, profile). */
    private suspend fun cacheHit(
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
    private suspend fun persistResolution(
        key: String,
        displayName: String,
        panel: NutritionPrompts.FoodPanel,
        method: String,
        sourceUrls: List<String>,
        llmModel: String,
        s: com.example.hoot.data.local.AppSettings
    ) {
        // (source-record shaping stays origin-aware below — see `sources` list)
        val food = ensureFood(key, panel.displayName.ifBlank { displayName }, imageTerm = panel.imageSearchTerm)
        val canonicalUnits = nutrients.unitMap()
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
                    publisher = AUTHORITATIVE.firstOrNull { url.contains(it, true) },
                    fetchedAt = System.currentTimeMillis(), toolName = "web-search-prime"
                )
            )
        }
        nutrients.recordSources(sources)
    }

    private suspend fun ensureFood(
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

    private suspend fun getWebTools(s: com.example.hoot.data.local.AppSettings): McpWebTools? {
        synchronized(webToolsMutex) {
            val key = "${s.mcpJson.hashCode()}:${s.mcpBudget}"
            if (webTools == null || webToolsKey != key) {
                webTools = McpWebTools(s.mcpBudget)
                webToolsKey = key
            }
        }
        return webTools?.also { runCatching { it.connect(s.mcpJson) } }
            ?.takeIf { it.remainingBudget >= 0 }
    }

    /** Tracked-nutrient refs for prompts, straight from the seeded table. */
    private suspend fun nutrientRefs(): List<NutritionPrompts.NutrientRef> =
        nutrients.definitionsAll().map { NutritionPrompts.NutrientRef(it.id, it.name, it.unit) }

    private suspend fun NutrientRepository.unitMap(): Map<String, String> =
        definitionsAll().associate { it.id to it.unit }

    sealed interface ResolveOutcome {
        data class Resolved(val method: String, val foodKey: String) : ResolveOutcome
        data class Failed(val reason: String) : ResolveOutcome
        data class Missing(val reason: String) : ResolveOutcome
        data object NotConfigured : ResolveOutcome
    }

    private data class WebOutcome(
        val panel: NutritionPrompts.FoodPanel,
        val sources: List<String>
    )

    companion object {
        private const val TAG = "HootResolver"

        /** Below this the pipeline escalates to the web tier (ARCHITECTURE.md §5). */
        const val CONFIDENCE_THRESHOLD = 0.75

        private const val SYSTEM_PROMPT = """
You are Hoot's nutrition database API. Values must be realistic USDA-grade.
Reply with ONLY one valid JSON object — no prose, no markdown fences.
"""

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
