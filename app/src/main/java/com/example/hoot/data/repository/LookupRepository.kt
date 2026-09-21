package com.example.hoot.data.repository

import com.example.hoot.data.local.dao.FoodDao
import com.example.hoot.data.local.dao.IngredientDao
import com.example.hoot.data.local.dao.LookupCacheDao
import com.example.hoot.data.local.dao.ProfileDao
import com.example.hoot.data.local.dao.SourceDao
import com.example.hoot.data.local.entity.FoodEntity
import com.example.hoot.data.local.entity.FoodNutrientProfileEntity
import com.example.hoot.data.local.entity.LookupCacheEntity
import com.example.hoot.data.local.entity.SourceEntity
import kotlinx.coroutines.flow.Flow

/**
 * LookupCache + Sources management (the phase-3 resolver's persistence tier).
 * Split from [NutrientRepository] so cache-view UI (phase 4 settings) only
 * depends on this narrow surface.
 */
class LookupRepository(
    private val lookupCacheDao: LookupCacheDao,
    private val profileDao: ProfileDao,
    private val foodDao: FoodDao,
    private val sourceDao: SourceDao,
    private val ingredientDao: IngredientDao
) {
    fun observeCacheByHits(): Flow<List<LookupCacheEntity>> = lookupCacheDao.observeAllByHits()

    fun observeCacheCount(): Flow<Int> = lookupCacheDao.observeCount()

    suspend fun cacheEntry(key: String): LookupCacheEntity? = lookupCacheDao.byKey(key)

    /** key → food → sources viewer for the cache-management settings section. */
    suspend fun resolve(key: String): LookupResolution? {
        val entry = lookupCacheDao.byKey(key) ?: return null
        val food = entry.resolvedFoodId?.let { foodDao.byId(it) }
        val profile = entry.profileId?.let { profileDao.byId(it) }
        val sources = sourceDao.forLookup(key)
        return LookupResolution(entry, food, profile, sources)
    }

    suspend fun ingredientsUsingFood(foodId: String): Int =
        ingredientDao.forFood(foodId).size

    /** "Clear cache" keeps Sources (citation history) — per ARCHITECTURE.md §8.6. */
    suspend fun clearCacheKeepSources() = lookupCacheDao.clearAll()

    suspend fun clearAll() {
        lookupCacheDao.clearAll()
        sourceDao.clearAll()
        profileDao.clearAll()
    }
}

/** Joined view for the cache viewer: entry + resolved food + profile + citations. */
data class LookupResolution(
    val entry: LookupCacheEntity,
    val food: FoodEntity?,
    val profile: FoodNutrientProfileEntity?,
    val sources: List<SourceEntity>
)
