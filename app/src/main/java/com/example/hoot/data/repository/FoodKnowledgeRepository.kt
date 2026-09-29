package com.example.hoot.data.repository

import com.example.hoot.data.local.dao.FoodKnowledgeDao
import com.example.hoot.data.local.entity.FoodKnowledgeEntity
import com.example.hoot.domain.nutrition.FoodKnowledgeIndex
import kotlinx.coroutines.flow.Flow

/**
 * Permanent food-knowledge base repository (feedback 2026-09-27): read/write
 * access to the amassing "food → high in" database. Deliberately narrow and
 * independent of [FoodRepository]/[LookupCacheRepository] so cache clears
 * never touch accumulated knowledge.
 */
class FoodKnowledgeRepository(private val dao: FoodKnowledgeDao) {

    fun observeAll(): Flow<List<FoodKnowledgeEntity>> = dao.observeAll()

    suspend fun all(): List<FoodKnowledgeEntity> = dao.all()

    suspend fun byName(normalizedName: String): FoodKnowledgeEntity? = dao.byName(normalizedName)

    suspend fun count(): Int = dao.count()

    /**
     * Records/refreshes knowledge for one food. Existing rows keep their
     * identity and provenance; the panel, high-in index and research
     * bookkeeping are updated. New rows get [researchedFor] attached.
     *
     * @param per100 per-100 g panel in canonical units
     * @param dailyTarget canonical daily target per nutrient id (RDA/goal)
     * @param researchedFor gap nutrient ids that motivated this research pass
     * @param sourceUrls provenance URLs from this research pass
     */
    suspend fun record(
        normalizedName: String,
        displayName: String,
        per100: Map<String, Double>,
        dailyTarget: Map<String, Double>,
        confidence: Double,
        resolutionMethod: String,
        sourceUrls: List<String> = emptyList(),
        researchedFor: List<String> = emptyList()
    ) {
        if (normalizedName.isBlank() || per100.isEmpty()) return
        val now = System.currentTimeMillis()
        val highIn = FoodKnowledgeIndex.highIn(per100, dailyTarget)
        val existing = dao.byName(normalizedName)
        if (existing != null) {
            dao.upsert(
                existing.copy(
                    displayName = displayName.ifBlank { existing.displayName },
                    valuesJson = FoodKnowledgeIndex.valuesToJson(per100),
                    // Merge: keep historical high-in entries — nutrients stay
                    // valid knowledge even when a refreshed panel is leaner.
                    highInJson = FoodKnowledgeIndex.toJson(
                        (FoodKnowledgeIndex.fromJson(existing.highInJson) + highIn).distinct()
                    ),
                    confidence = maxOf(existing.confidence, confidence),
                    resolutionMethod = resolutionMethod,
                    sourceUrlsJson = mergeUrls(existing.sourceUrlsJson, sourceUrls),
                    researchedForJson = FoodKnowledgeIndex.toJson(
                        (FoodKnowledgeIndex.fromJson(existing.researchedForJson) + researchedFor)
                            .distinct()
                    ),
                    lastResearchedAt = now,
                    researchCount = existing.researchCount + 1
                )
            )
        } else {
            dao.upsert(
                FoodKnowledgeEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    normalizedName = normalizedName,
                    displayName = displayName.ifBlank { normalizedName },
                    valuesJson = FoodKnowledgeIndex.valuesToJson(per100),
                    highInJson = FoodKnowledgeIndex.toJson(highIn),
                    confidence = confidence,
                    resolutionMethod = resolutionMethod,
                    sourceUrlsJson = mergeUrls(null, sourceUrls),
                    researchedForJson = FoodKnowledgeIndex.toJson(researchedFor),
                    firstResearchedAt = now,
                    lastResearchedAt = now,
                    researchCount = 1
                )
            )
        }
    }

    /**
     * Links a knowledge row to the gap nutrients it was researched for
     * (no-op when the row doesn't exist — used by gap-targeted research
     * after [persistFoodPanel] already recorded the panel).
     */
    suspend fun tagResearchedFor(normalizedName: String, gapIds: List<String>) {
        if (gapIds.isEmpty()) return
        val existing = dao.byName(normalizedName) ?: return
        val merged = (FoodKnowledgeIndex.fromJson(existing.researchedForJson) + gapIds).distinct()
        val newJson = FoodKnowledgeIndex.toJson(merged)
        if (newJson != existing.researchedForJson) {
            dao.upsert(existing.copy(researchedForJson = newJson))
        }
    }

    suspend fun deleteByName(normalizedName: String) = dao.deleteByName(normalizedName)

    suspend fun clearAll() = dao.clearAll()

    /** Re-derives high-in for every row against CURRENT targets (goals edit). */
    suspend fun reindexAll(dailyTarget: Map<String, Double>) {
        for (row in dao.all()) {
            val values = FoodKnowledgeIndex.valuesFromJson(row.valuesJson)
            if (values.isEmpty()) continue
            val highIn = FoodKnowledgeIndex.highIn(values, dailyTarget)
            val merged = (FoodKnowledgeIndex.fromJson(row.highInJson) + highIn).distinct()
            if (FoodKnowledgeIndex.toJson(merged) != row.highInJson) {
                dao.upsert(row.copy(highInJson = FoodKnowledgeIndex.toJson(merged)))
            }
        }
    }

    private fun mergeUrls(existingRaw: String?, added: List<String>): String {
        if (added.isEmpty()) return existingRaw ?: "[]"
        val existing = FoodKnowledgeIndex.fromJson(existingRaw)
        // Cap provenance growth; oldest survive first.
        return FoodKnowledgeIndex.toJson((existing + added).distinct().takeLast(20))
    }
}
