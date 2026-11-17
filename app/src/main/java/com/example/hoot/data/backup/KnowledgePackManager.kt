package com.example.hoot.data.backup

import com.example.hoot.data.repository.FoodKnowledgeRepository
import com.example.hoot.data.repository.NutrientDefinitionRepository
import com.example.hoot.data.repository.NutrientSymptomRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestrates knowledge-pack export/import (feedback 2026-11-17) across the
 * three knowledge-bearing stores: canonical nutrient definitions, the
 * nutrient ↔ symptom association table and the permanent food-knowledge
 * base. See [KnowledgePackCodec] for the file format — deliberately excludes
 * EVERYTHING personal (meals, supplements, ledger, scores, goals, profile).
 */
class KnowledgePackManager(
    private val definitions: NutrientDefinitionRepository,
    private val symptoms: NutrientSymptomRepository,
    private val knowledge: FoodKnowledgeRepository
) {

    /** Builds the shareable JSON (no personal data). Null on unexpected failure. */
    suspend fun exportJson(): String? = withContext(Dispatchers.IO) {
        runCatching {
            KnowledgePackCodec.exportJson(
                nutrients = definitions.definitionsAll(),
                symptomAssociations = symptoms.all(),
                foods = knowledge.all()
            )
        }.getOrNull()
    }

    /** Outcome surfaced to the UI snackbar. */
    data class ImportResult(
        val nutrients: Int,
        val foods: Int,
        val symptomAssociations: Int
    ) {
        fun summary(): String = buildString {
            append("Imported ")
            append(
                listOf(
                    "$nutrients nutrient definitions",
                    "$foods foods",
                    "$symptomAssociations symptom links"
                ).joinToString(", ")
            )
        }
    }

    /**
     * Applies a pack: definitions and symptom associations upsert (id-keyed,
     * idempotent), foods merge-import (existing local panels win; high-in
     * knowledge unions). Returns null when the text is not a knowledge pack.
     */
    suspend fun importJson(text: String): ImportResult? = withContext(Dispatchers.IO) {
        val pack = KnowledgePackCodec.parse(text) ?: return@withContext null
        if (pack.nutrients.isNotEmpty()) {
            runCatching { definitions.upsertDefinitions(pack.nutrients) }
        }
        if (pack.symptomAssociations.isNotEmpty()) {
            runCatching { symptoms.importAssociations(pack.symptomAssociations) }
        }
        val foods = runCatching { knowledge.importPack(pack.foods) }.getOrDefault(0)
        ImportResult(
            nutrients = pack.nutrients.size,
            foods = foods,
            symptomAssociations = pack.symptomAssociations.size
        )
    }
}
