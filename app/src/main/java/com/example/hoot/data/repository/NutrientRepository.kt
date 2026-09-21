package com.example.hoot.data.repository

import com.example.hoot.data.local.dao.FoodDao
import com.example.hoot.data.local.dao.IngredientDao
import com.example.hoot.data.local.dao.IntakeDao
import com.example.hoot.data.local.dao.LookupCacheDao
import com.example.hoot.data.local.dao.NutrientDao
import com.example.hoot.data.local.dao.NutrientDayTotal
import com.example.hoot.data.local.dao.NutrientGoalDao
import com.example.hoot.data.local.dao.ProfileDao
import com.example.hoot.data.local.dao.RecommendationDao
import com.example.hoot.data.local.dao.ScoreSnapshotDao
import com.example.hoot.data.local.dao.SourceDao
import com.example.hoot.data.local.dao.SupplementDao
import com.example.hoot.data.local.entity.FoodEntity
import com.example.hoot.data.local.entity.FoodNutrientProfileEntity
import com.example.hoot.data.local.entity.LookupCacheEntity
import com.example.hoot.data.local.entity.NutrientDefinitionEntity
import com.example.hoot.data.local.entity.NutrientGoalEntity
import com.example.hoot.data.local.entity.NutrientIntakeEntity
import com.example.hoot.data.local.entity.RecommendationEntity
import com.example.hoot.domain.insights.DietProfile
import com.example.hoot.domain.insights.DietRules
import com.example.hoot.data.local.entity.ScoreSnapshotEntity
import com.example.hoot.data.local.entity.SourceEntity
import com.example.hoot.data.local.entity.SupplementEntity
import kotlinx.coroutines.flow.Flow

/**
 * Facade over the nutrient-domain DAOs: definitions, goals, intake ledger,
 * resolved food profiles + cache + sources, recommendations, score history.
 * Aggregation-ready queries ([dailyTotals]/[rangeTotals]) back the phase-3
 * ScoreEngine.
 */
class NutrientRepository(
    private val nutrientDao: NutrientDao,
    private val goalDao: NutrientGoalDao,
    private val intakeDao: IntakeDao,
    private val foodDao: FoodDao,
    private val profileDao: ProfileDao,
    private val lookupCacheDao: LookupCacheDao,
    private val sourceDao: SourceDao,
    private val supplementDao: SupplementDao,
    private val recommendationDao: RecommendationDao,
    private val scoreSnapshotDao: ScoreSnapshotDao
) {
    // ---- Definitions -------------------------------------------------------
    fun observeDefinitions(): Flow<List<NutrientDefinitionEntity>> = nutrientDao.observeAll()

    suspend fun definition(id: String): NutrientDefinitionEntity? = nutrientDao.byId(id)

    suspend fun definitionsAll(): List<NutrientDefinitionEntity> = nutrientDao.all()

    suspend fun definitionsByIds(ids: List<String>): List<NutrientDefinitionEntity> =
        nutrientDao.byIds(ids)

    suspend fun definitionCount(): Int = nutrientDao.count()

    // ---- Goals ---------------------------------------------------------
    fun observeGoals(): Flow<List<NutrientGoalEntity>> = goalDao.observeAll()

    suspend fun goal(nutrientId: String): NutrientGoalEntity? = goalDao.byNutrientId(nutrientId)

    suspend fun upsertGoal(goal: NutrientGoalEntity) = goalDao.upsert(goal)

    /** Reset one nutrient to its RDA default (removes the custom goal row). */
    suspend fun deleteGoal(nutrientId: String) = goalDao.deleteForNutrient(nutrientId)

    // ---- Intake ledger -------------------------------------------------
    fun observeIntakeByDay(day: String): Flow<List<NutrientIntakeEntity>> =
        intakeDao.observeByDay(day)

    fun observeIntakeRange(from: String, to: String): Flow<List<NutrientIntakeEntity>> =
        intakeDao.observeRange(from, to)

    suspend fun dailyTotals(day: String): List<NutrientDayTotal> = intakeDao.dailyTotals(day)

    /** Per-meal contribution ranking for one nutrient over a window (top sources). */
    suspend fun mealContributions(
        nutrientId: String,
        from: String,
        to: String
    ): List<com.example.hoot.data.local.dao.MealContribution> =
        intakeDao.mealContributions(nutrientId, from, to)

    suspend fun lookupKeyForFood(foodId: String): com.example.hoot.data.local.entity.LookupCacheEntity? =
        lookupCacheDao.byResolvedFoodId(foodId).firstOrNull()

    suspend fun rangeTotals(from: String, to: String): List<NutrientDayTotal> =
        intakeDao.rangeTotals(from, to)

    /** Per-(nutrient, day) totals across a window (history series). */
    suspend fun dailyTotalsForWindow(from: String, to: String): List<NutrientDayTotal> =
        intakeDao.dailyTotalsRange(from, to)

    suspend fun logIntake(entries: List<NutrientIntakeEntity>) = intakeDao.upsertAll(entries)

    suspend fun clearIntakeForMeal(mealId: String) = intakeDao.deleteForMeal(mealId)

    suspend fun clearIntakeForSupplement(supplementId: String) =
        intakeDao.deleteForSupplement(supplementId)

    /** Idempotent per-day recompute: wipe the day's ledger before re-aggregation. */
    suspend fun clearIntakeForDay(day: String) = intakeDao.deleteForDay(day)

    // ---- Foods / profiles / cache / sources -------------------------------
    fun observeFoods(): Flow<List<FoodEntity>> = foodDao.observeFoods()

    suspend fun food(id: String): FoodEntity? = foodDao.byId(id)

    suspend fun foodByName(normalizedName: String): FoodEntity? =
        foodDao.byNormalizedName(normalizedName)

    suspend fun foodsAll(): List<FoodEntity> = foodDao.all()

    suspend fun upsertFood(food: FoodEntity) = foodDao.upsert(food)

    suspend fun profileForFood(foodId: String): FoodNutrientProfileEntity? =
        profileDao.forFood(foodId)

    suspend fun profileById(id: String): FoodNutrientProfileEntity? = profileDao.byId(id)

    suspend fun upsertProfile(profile: FoodNutrientProfileEntity) = profileDao.upsert(profile)

    suspend fun cacheLookup(key: String): LookupCacheEntity? = lookupCacheDao.byKey(key)

    suspend fun cachePut(entry: LookupCacheEntity) = lookupCacheDao.upsert(entry)

    /** Popularity telemetry on cache hit (ARCHITECTURE.md §5). */
    suspend fun cacheHit(key: String) = lookupCacheDao.incrementHitCount(key)

    suspend fun cacheStats(): Triple<Int, Long?, Long?> = Triple(
        lookupCacheDao.count(),
        lookupCacheDao.oldestFetchedAt(),
        lookupCacheDao.newestFetchedAt()
    )

    suspend fun sourcesForLookup(lookupKey: String): List<SourceEntity> =
        sourceDao.forLookup(lookupKey)

    suspend fun recordSources(sources: List<SourceEntity>) = sourceDao.insertAll(sources)

    // ---- Supplements (contribution parsing) --------------------------------
    suspend fun supplementByLabel(label: String): SupplementEntity? =
        supplementDao.byLabel(label)

    suspend fun upsertSupplement(supplement: SupplementEntity) = supplementDao.upsert(supplement)

    // ---- Recommendations -----------------------------------------------
    fun observeOpenRecommendations(limit: Int = 10): Flow<List<RecommendationEntity>> =
        recommendationDao.observeOpen(limit)

    fun observeRecentRecommendations(limit: Int = 20): Flow<List<RecommendationEntity>> =
        recommendationDao.observeRecent(limit)

    suspend fun recommendation(id: String): RecommendationEntity? = recommendationDao.byId(id)

    suspend fun recommendationsBetween(from: String, to: String): List<RecommendationEntity> =
        recommendationDao.between(from, to)

    suspend fun setRecommendationAccepted(id: String, accepted: Boolean?) =
        recommendationDao.setAccepted(id, accepted)

    suspend fun upsertRecommendation(recommendation: RecommendationEntity) =
        recommendationDao.upsert(recommendation)

    /**
     * Diet-violation purge (diet-fix hardening, 2026-09): deletes every
     * persisted recommendation row whose food name or reason text violates
     * [profile]. Rows are re-issued against the CURRENT profile by
     * [com.example.hoot.domain.insights.RecommendationEngine.generateForDay]
     * (kick it after purging), so nothing but diet-incompatible suggestions
     * is lost. Returns the number of rows removed (0 on empty input).
     */
    suspend fun purgeDietViolatingRecommendations(profile: DietProfile): Int {
        val violating = recommendationDao.all().filter {
            !DietRules.allowsFood(it.foodName, profile) ||
                !DietRules.allowsFood(it.reasonText, profile)
        }.map { it.id }
        if (violating.isEmpty()) return 0
        recommendationDao.deleteByIds(violating)
        return violating.size
    }

    suspend fun recommendationCountsBetween(
        from: String,
        to: String
    ): Triple<Int, Int, Int> = Triple(
        recommendationDao.countBetween(from, to),
        recommendationDao.acceptedCount(from, to),
        recommendationDao.answeredCount(from, to)
    )

    suspend fun recommendedNutrientIdsForDay(day: String): List<String> =
        recommendationDao.nutrientIdsForDay(day)

    // ---- Score history -------------------------------------------------
    suspend fun snapshot(day: String): ScoreSnapshotEntity? = scoreSnapshotDao.byDay(day)

    fun observeScoreHistory(from: String): Flow<List<ScoreSnapshotEntity>> =
        scoreSnapshotDao.observeFrom(from)

    suspend fun upsertSnapshot(snapshot: ScoreSnapshotEntity) = scoreSnapshotDao.upsert(snapshot)

    /** Reactive per-nutrient totals for one day (dashboard bars). */
    fun observeDailyTotals(day: String): Flow<List<NutrientDayTotal>> =
        intakeDao.observeDailyTotals(day)

    /** Reactive per-(nutrient, day) totals across a range (history charts). */
    fun observeDailyTotalsRange(from: String, to: String): Flow<List<NutrientDayTotal>> =
        intakeDao.observeDailyTotalsRange(from, to)

    suspend fun goalsAll(): List<NutrientGoalEntity> = goalDao.all()

    suspend fun upsertGoals(goals: List<NutrientGoalEntity>) = goalDao.upsertAll(goals)
}
