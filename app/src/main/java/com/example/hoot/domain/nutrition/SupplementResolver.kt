package com.example.hoot.domain.nutrition

import android.util.Log
import com.example.hoot.data.local.SettingsRepository
import com.example.hoot.data.local.entity.SupplementEntity
import com.example.hoot.data.remote.LlmClient
import com.example.hoot.data.remote.LlmConfig
import com.example.hoot.data.repository.MealRepository
import org.json.JSONObject

/**
 * Supplement resolution tier (extracted from [NutritionResolver], refactor
 * 2026-09-22 P4): tolerant label-parse first, then LLM per-serving panels —
 * single-row and batched group paths — persisting canonical-unit
 * contribution JSON on each supplement row.
 */
internal class SupplementResolver(
    private val meals: MealRepository,
    private val settings: SettingsRepository,
    private val llm: LlmClient,
    private val store: ResolutionStore
) {
    /**
     * Resolves nutrient contributions for a [SupplementEntity]: tolerant
     * "label:value" parse first (no lookup round-trip), LLM per-serving panel
     * for plain text; contributions stored as canonical-unit JSON on the row.
     */
    suspend fun resolveSupplement(supplementId: String): NutritionResolver.ResolveOutcome {
        val entity = findSupplement(supplementId)
            ?: return NutritionResolver.ResolveOutcome.Missing("supplement $supplementId vanished")
        val s = settings.current()
        val defs = store.nutrientRefs()
        if (defs.isEmpty()) return NutritionResolver.ResolveOutcome.Failed("nutrient definitions not seeded yet")
        val unitsById = defs.associate { it.id to it.unit }

        // 1) Direct label parse — "Magnesium 400 mg" needs no LLM round-trip.
        val labelParsed = SupplementLabelParser.parse(entity.rawText.ifBlank { entity.label })
        val direct = directContributions(labelParsed, unitsById)
        if (direct != null) {
            saveContributions(entity, labelParsed!!.normalizedName, direct)
            return NutritionResolver.ResolveOutcome.Resolved(method = "label", foodKey = labelParsed.normalizedName)
        }

        // 2) LLM assist for plain text.
        val cfg = LlmConfig(s.baseUrl, s.apiKey, s.model)
        if (!cfg.configured) return NutritionResolver.ResolveOutcome.NotConfigured
        val panel = runCatching {
            val json = llm.completeJson(
                cfg, RESOLUTION_SYSTEM_PROMPT.trimIndent(),
                NutritionPrompts.supplementUserPrompt(entity.rawText.ifBlank { entity.label }, defs),
                temperature = 0.2f,
                disableThinking = s.disableThinking
            )
            NutritionPrompts.parsePanel(json, unitsById.keys)
        }.onFailure { Log.w(TAG, "supplement LLM failed for '${entity.label}': ${it.message}") }.getOrNull()

        if (panel == null) return NutritionResolver.ResolveOutcome.Failed("supplement '${entity.label}' unparseable")
        val contributions = JSONObject()
        for ((nutrientId, amount) in panel.values) contributions.put(nutrientId, amount)
        saveContributions(entity, panel.displayName, contributions, panel.imageSearchTerm)
        return NutritionResolver.ResolveOutcome.Resolved(method = "llm", foodKey = panel.displayName)
    }

    /**
     * ONE batched LLM call for supplement name-groups (per-serving panels
     * keyed by group key). Returns only the panels that parsed.
     */
    suspend fun resolveSupplementGroupsBatch(
        items: List<Pair<String, String>>,   // groupKey → display label
        rateGuard: LlmRateGuard?
    ): Map<String, NutritionPrompts.FoodPanel> {
        if (items.isEmpty()) return emptyMap()
        val s = settings.current()
        val cfg = LlmConfig(s.baseUrl, s.apiKey, s.model)
        if (!cfg.configured) return emptyMap()
        val defs = store.nutrientRefs()
        if (defs.isEmpty()) return emptyMap()
        rateGuard?.acquire()
        return runCatching {
            // Tolerant ONE-shot batch — same rationale as resolveFoodsBatch.
            val json = llm.chat(
                cfg, RESOLUTION_SYSTEM_PROMPT.trimIndent(),
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
    ): Int {
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
        return updated
    }

    /**
     * Single-supplement fallback for a group whose batch entry failed:
     * direct-label parse first (free), then one LLM panel; the result is
     * replicated to every remaining unresolved row of the group. With
     * [allowLlm]=false only the FREE label-parse pass runs and the outcome is
     * [NutritionResolver.ResolveOutcome.NotConfigured] when rows still need
     * the LLM — used by the processor's pre-batch sweep so label parses never
     * burn call slots.
     */
    suspend fun resolveSupplementGroupSingle(
        supplementIds: Collection<String>,
        rateGuard: LlmRateGuard?,
        allowLlm: Boolean = true
    ): NutritionResolver.ResolveOutcome {
        val rows = meals.supplementsByIds(supplementIds.toList())
            .filter { it.nutrientContributions == "[]" && it.resolvedFoodId == null }
        if (rows.isEmpty()) return NutritionResolver.ResolveOutcome.Missing("group already resolved")
        val s = settings.current()
        val defs = store.nutrientRefs()
        if (defs.isEmpty()) return NutritionResolver.ResolveOutcome.Failed("nutrient definitions not seeded yet")
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
            return NutritionResolver.ResolveOutcome.Resolved(method = "label", foodKey = rows.first().label)
        }

        val remaining = rows.drop(labelResolved)
        val cfg = LlmConfig(s.baseUrl, s.apiKey, s.model)
        if (!cfg.configured || !allowLlm) return NutritionResolver.ResolveOutcome.NotConfigured
        val first = remaining.first()
        rateGuard?.acquire()
        val panel = runCatching {
            val json = llm.completeJson(
                cfg, RESOLUTION_SYSTEM_PROMPT.trimIndent(),
                NutritionPrompts.supplementUserPrompt(first.rawText.ifBlank { first.label }, defs),
                temperature = 0.2f,
                disableThinking = s.disableThinking
            )
            NutritionPrompts.parsePanel(json, unitsById.keys)
        }.onFailure { Log.w(TAG, "supplement LLM failed for '${first.label}': ${it.message}") }.getOrNull()
            ?: return NutritionResolver.ResolveOutcome.Failed("supplement '${first.label}' unparseable")

        val contributions = JSONObject()
        for ((nutrientId, amount) in panel.values) contributions.put(nutrientId, amount)
        for (entity in remaining) {
            saveContributions(entity, panel.displayName, contributions, panel.imageSearchTerm)
        }
        return NutritionResolver.ResolveOutcome.Resolved(method = "llm", foodKey = panel.displayName)
    }

    /** Known-supplement quick path: label → elemental nutrient, when unambiguous. */
    private fun directContributions(
        parsed: SupplementLabelParser.ParsedSupplement?,
        unitsById: Map<String, String>
    ): JSONObject? {
        if (parsed?.doseAmount == null || parsed.doseUnit == null) return null
        val nutrientId = NutritionResolver.KNOWN_SUPPLEMENT_NUTRIENTS[parsed.normalizedName] ?: return null
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
        val food = store.ensureFood(
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

    companion object {
        private const val TAG = "HootResolver"
    }
}
