package com.example.hoot.data.repository

import com.example.hoot.data.local.dao.FoodDao
import com.example.hoot.data.local.dao.ProfileDao
import com.example.hoot.data.local.entity.FoodEntity
import com.example.hoot.data.local.entity.FoodNutrientProfileEntity
import kotlinx.coroutines.flow.Flow

/**
 * Resolved food library: canonical food rows plus their per-100 g nutrient
 * profiles written by the resolution pipeline. Split out of
 * [NutrientRepository] (facade).
 */
class FoodRepository(
    private val foodDao: FoodDao,
    private val profileDao: ProfileDao
) {
    fun observeFoods(): Flow<List<FoodEntity>> = foodDao.observeFoods()

    /** Every food row INCLUDING supplements (Settings → Food library list). */
    fun observeAllFoods(): Flow<List<FoodEntity>> = foodDao.observeAll()

    /** All resolved nutrient panels (reactive; used by the Settings Food library). */
    fun observeProfiles(): Flow<List<FoodNutrientProfileEntity>> = profileDao.observeAll()

    suspend fun food(id: String): FoodEntity? = foodDao.byId(id)

    suspend fun foodByName(normalizedName: String): FoodEntity? =
        foodDao.byNormalizedName(normalizedName)

    suspend fun foodsAll(): List<FoodEntity> = foodDao.all()

    suspend fun upsertFood(food: FoodEntity) = foodDao.upsert(food)

    suspend fun profileForFood(foodId: String): FoodNutrientProfileEntity? =
        profileDao.forFood(foodId)

    suspend fun profileById(id: String): FoodNutrientProfileEntity? = profileDao.byId(id)

    suspend fun upsertProfile(profile: FoodNutrientProfileEntity) = profileDao.upsert(profile)

    /** Removes one panel (Food library "clear nutrition data" action). */
    suspend fun deleteProfile(id: String) = profileDao.delete(id)
}
