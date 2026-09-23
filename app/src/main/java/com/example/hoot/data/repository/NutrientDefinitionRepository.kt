package com.example.hoot.data.repository

import com.example.hoot.data.local.dao.NutrientDao
import com.example.hoot.data.local.dao.NutrientGoalDao
import com.example.hoot.data.local.entity.NutrientDefinitionEntity
import com.example.hoot.data.local.entity.NutrientGoalEntity
import kotlinx.coroutines.flow.Flow

/**
 * Nutrient catalog: canonical nutrient definitions and per-user daily goals.
 * Split out of [NutrientRepository] (facade) so settings/goals UI and engines
 * depend on the narrowest possible surface.
 */
class NutrientDefinitionRepository(
    private val nutrientDao: NutrientDao,
    private val goalDao: NutrientGoalDao
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

    suspend fun goalsAll(): List<NutrientGoalEntity> = goalDao.all()

    suspend fun upsertGoals(goals: List<NutrientGoalEntity>) = goalDao.upsertAll(goals)
}
