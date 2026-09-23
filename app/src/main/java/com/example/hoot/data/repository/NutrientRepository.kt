package com.example.hoot.data.repository

import com.example.hoot.data.local.dao.FoodDao
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
import com.example.hoot.data.local.entity.ScoreSnapshotEntity
import com.example.hoot.data.local.entity.SourceEntity
import com.example.hoot.data.local.entity.SupplementEntity
import kotlinx.coroutines.flow.Flow

/**
 * DEPRECATED facade over the seven concern-scoped repositories that replaced
 * this god-repository (refactor 2026-09-22, docs/REFACTORING_PLAN.md P2):
 * [NutrientDefinitionRepository], [IntakeRepository], [FoodRepository],
 * [LookupCacheRepository], [SupplementRepository], [RecommendationRepository],
 * [ScoreSnapshotRepository].
 *
 * Kept ONLY so existing call sites ([com.example.hoot.di.AppGraph] and the
 * ViewModels/engines) keep compiling while they migrate; every member is a
 * one-line delegation. New code must depend on the narrow repository it
 * needs. Delete once no caller remains.
 */
@Deprecated("Use the concern-scoped repositories instead (see class KDoc)")
class NutrientRepository(
    nutrientDao: NutrientDao,
    goalDao: NutrientGoalDao,
    intakeDao: IntakeDao,
    foodDao: FoodDao,
    profileDao: ProfileDao,
    lookupCacheDao: LookupCacheDao,
    sourceDao: SourceDao,
    supplementDao: SupplementDao,
    recommendationDao: RecommendationDao,
    scoreSnapshotDao: ScoreSnapshotDao
) {
    val definitions = NutrientDefinitionRepository(nutrientDao, goalDao)
    val intake = IntakeRepository(intakeDao)
    val foods = FoodRepository(foodDao, profileDao)
    val lookupCache = LookupCacheRepository(lookupCacheDao, sourceDao)
    val supplements = SupplementRepository(supplementDao)
    val recommendations = RecommendationRepository(recommendationDao)
    val scoreSnapshots = ScoreSnapshotRepository(scoreSnapshotDao)

    // ---- Definitions -------------------------------------------------------
    fun observeDefinitions(): Flow<List<NutrientDefinitionEntity>> = definitions.observeDefinitions()

    suspend fun definition(id: String): NutrientDefinitionEntity? = definitions.definition(id)

    suspend fun definitionsAll(): List<NutrientDefinitionEntity> = definitions.definitionsAll()

    suspend fun definitionsByIds(ids: List<String>): List<NutrientDefinitionEntity> =
        definitions.definitionsByIds(ids)

    suspend fun definitionCount(): Int = definitions.definitionCount()

    // ---- Goals ---------------------------------------------------------
    fun observeGoals(): Flow<List<NutrientGoalEntity>> = definitions.observeGoals()

    suspend fun goal(nutrientId: String): NutrientGoalEntity? = definitions.goal(nutrientId)

    suspend fun upsertGoal(goal: NutrientGoalEntity) = definitions.upsertGoal(goal)

    suspend fun deleteGoal(nutrientId: String) = definitions.deleteGoal(nutrientId)

    suspend fun goalsAll(): List<NutrientGoalEntity> = definitions.goalsAll()

    suspend fun upsertGoals(goals: List<NutrientGoalEntity>) = definitions.upsertGoals(goals)

    // ---- Intake ledger -------------------------------------------------
    fun observeIntakeByDay(day: String): Flow<List<NutrientIntakeEntity>> =
        intake.observeIntakeByDay(day)

    fun observeIntakeRange(from: String, to: String): Flow<List<NutrientIntakeEntity>> =
        intake.observeIntakeRange(from, to)

    suspend fun dailyTotals(day: String): List<NutrientDayTotal> = intake.dailyTotals(day)

    suspend fun mealContributions(
        nutrientId: String,
        from: String,
        to: String
    ): List<com.example.hoot.data.local.dao.MealContribution> =
        intake.mealContributions(nutrientId, from, to)

    suspend fun rangeTotals(from: String, to: String): List<NutrientDayTotal> =
        intake.rangeTotals(from, to)

    suspend fun dailyTotalsForWindow(from: String, to: String): List<NutrientDayTotal> =
        intake.dailyTotalsForWindow(from, to)

    suspend fun logIntake(entries: List<NutrientIntakeEntity>) = intake.logIntake(entries)

    suspend fun clearIntakeForMeal(mealId: String) = intake.clearIntakeForMeal(mealId)

    suspend fun clearIntakeForSupplement(supplementId: String) =
        intake.clearIntakeForSupplement(supplementId)

    suspend fun clearIntakeForDay(day: String) = intake.clearIntakeForDay(day)

    fun observeDailyTotals(day: String): Flow<List<NutrientDayTotal>> =
        intake.observeDailyTotals(day)

    fun observeDailyTotalsRange(from: String, to: String): Flow<List<NutrientDayTotal>> =
        intake.observeDailyTotalsRange(from, to)

    // ---- Foods / profiles / cache / sources -------------------------------
    fun observeFoods(): Flow<List<FoodEntity>> = foods.observeFoods()

    suspend fun food(id: String): FoodEntity? = foods.food(id)

    suspend fun foodByName(normalizedName: String): FoodEntity? = foods.foodByName(normalizedName)

    suspend fun foodsAll(): List<FoodEntity> = foods.foodsAll()

    suspend fun upsertFood(food: FoodEntity) = foods.upsertFood(food)

    suspend fun profileForFood(foodId: String): FoodNutrientProfileEntity? =
        foods.profileForFood(foodId)

    suspend fun profileById(id: String): FoodNutrientProfileEntity? = foods.profileById(id)

    suspend fun upsertProfile(profile: FoodNutrientProfileEntity) = foods.upsertProfile(profile)

    suspend fun cacheLookup(key: String): LookupCacheEntity? = lookupCache.byKey(key)

    suspend fun cachePut(entry: LookupCacheEntity) = lookupCache.upsert(entry)

    suspend fun cacheHit(key: String) = lookupCache.incrementHitCount(key)

    suspend fun cacheStats(): Triple<Int, Long?, Long?> = lookupCache.cacheStats()

    suspend fun lookupKeyForFood(foodId: String): LookupCacheEntity? =
        lookupCache.lookupKeyForFood(foodId)

    suspend fun sourcesForLookup(lookupKey: String): List<SourceEntity> =
        lookupCache.sourcesForLookup(lookupKey)

    suspend fun recordSources(sources: List<SourceEntity>) = lookupCache.recordSources(sources)

    // ---- Supplements (contribution parsing) --------------------------------
    suspend fun supplementByLabel(label: String): SupplementEntity? = supplements.byLabel(label)

    suspend fun upsertSupplement(supplement: SupplementEntity) = supplements.upsert(supplement)

    // ---- Recommendations -----------------------------------------------
    fun observeOpenRecommendations(limit: Int = 10): Flow<List<RecommendationEntity>> =
        recommendations.observeOpen(limit)

    fun observeRecentRecommendations(limit: Int = 20): Flow<List<RecommendationEntity>> =
        recommendations.observeRecent(limit)

    suspend fun recommendation(id: String): RecommendationEntity? = recommendations.byId(id)

    suspend fun recommendationsBetween(from: String, to: String): List<RecommendationEntity> =
        recommendations.between(from, to)

    suspend fun setRecommendationAccepted(id: String, accepted: Boolean?) =
        recommendations.setAccepted(id, accepted)

    suspend fun upsertRecommendation(recommendation: RecommendationEntity) =
        recommendations.upsert(recommendation)

    suspend fun purgeDietViolatingRecommendations(profile: DietProfile): Int =
        recommendations.purgeDietViolating(profile)

    suspend fun recommendationCountsBetween(
        from: String,
        to: String
    ): Triple<Int, Int, Int> = recommendations.countsBetween(from, to)

    suspend fun recommendedNutrientIdsForDay(day: String): List<String> =
        recommendations.nutrientIdsForDay(day)

    // ---- Score history -------------------------------------------------
    suspend fun snapshot(day: String): ScoreSnapshotEntity? = scoreSnapshots.byDay(day)

    fun observeScoreHistory(from: String): Flow<List<ScoreSnapshotEntity>> =
        scoreSnapshots.observeFrom(from)

    suspend fun upsertSnapshot(snapshot: ScoreSnapshotEntity) = scoreSnapshots.upsert(snapshot)
}
