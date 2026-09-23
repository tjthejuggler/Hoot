package com.example.hoot.data.repository

import com.example.hoot.data.local.dao.RecommendationDao
import com.example.hoot.data.local.entity.RecommendationEntity
import com.example.hoot.domain.insights.DietProfile
import com.example.hoot.domain.insights.DietRules
import kotlinx.coroutines.flow.Flow

/**
 * Smart-pick recommendation rows: open/recent observation, acceptance state,
 * per-day counts, and the diet-violation purge. Split out of
 * [NutrientRepository] (facade).
 */
class RecommendationRepository(
    private val recommendationDao: RecommendationDao
) {
    fun observeOpen(limit: Int = 10): Flow<List<RecommendationEntity>> =
        recommendationDao.observeOpen(limit)

    fun observeRecent(limit: Int = 20): Flow<List<RecommendationEntity>> =
        recommendationDao.observeRecent(limit)

    suspend fun byId(id: String): RecommendationEntity? = recommendationDao.byId(id)

    suspend fun between(from: String, to: String): List<RecommendationEntity> =
        recommendationDao.between(from, to)

    suspend fun setAccepted(id: String, accepted: Boolean?) =
        recommendationDao.setAccepted(id, accepted)

    suspend fun upsert(recommendation: RecommendationEntity) =
        recommendationDao.upsert(recommendation)

    /**
     * Diet-violation purge (diet-fix hardening, 2026-09): deletes every
     * persisted recommendation row whose food name or reason text violates
     * [profile]. Rows are re-issued against the CURRENT profile by
     * [com.example.hoot.domain.insights.RecommendationEngine.generateForDay]
     * (kick it after purging), so nothing but diet-incompatible suggestions
     * is lost. Returns the number of rows removed (0 on empty input).
     */
    suspend fun purgeDietViolating(profile: DietProfile): Int {
        val violating = recommendationDao.all().filter {
            !DietRules.allowsFood(it.foodName, profile) ||
                !DietRules.allowsFood(it.reasonText, profile)
        }.map { it.id }
        if (violating.isEmpty()) return 0
        recommendationDao.deleteByIds(violating)
        return violating.size
    }

    /**
     * Ledger hygiene (feedback 2026-09-23 round 2, systemic): deletes every
     * persisted row whose food name fails the CURRENT quality gates — vessel
     * nouns ("Vegan Platter"), diet-adjective stubs ("Plant-based meal"),
     * meal-occasion titles ("Vegan Brunch Spread") and segmentation
     * artifacts ("Plus Seaweed Sheets"). Older builds persisted such rows
     * before the gates existed; purging them once at startup keeps the
     * carousel clean without relying on every render path to re-filter.
     * Engine re-issues replacements on the next recommendation pass.
     */
    suspend fun purgeLowQuality(): Int {
        val bad = recommendationDao.all().filter {
            val cleaned = com.example.hoot.domain.insights.SmartFoodMatcher
                .cleanFoodName(it.foodName)
            !com.example.hoot.domain.insights.SmartFoodMatcher.isPlausibleFoodName(cleaned) ||
                !com.example.hoot.domain.insights.NutrientSourceQuality
                    .isAcceptableSourceName(it.foodName)
        }.map { it.id }
        if (bad.isEmpty()) return 0
        recommendationDao.deleteByIds(bad)
        return bad.size
    }

    suspend fun countsBetween(
        from: String,
        to: String
    ): Triple<Int, Int, Int> = Triple(
        recommendationDao.countBetween(from, to),
        recommendationDao.acceptedCount(from, to),
        recommendationDao.answeredCount(from, to)
    )

    suspend fun nutrientIdsForDay(day: String): List<String> =
        recommendationDao.nutrientIdsForDay(day)
}
