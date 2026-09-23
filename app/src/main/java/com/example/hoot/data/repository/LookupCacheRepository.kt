package com.example.hoot.data.repository

import com.example.hoot.data.local.dao.LookupCacheDao
import com.example.hoot.data.local.dao.SourceDao
import com.example.hoot.data.local.entity.LookupCacheEntity
import com.example.hoot.data.local.entity.SourceEntity

/**
 * Resolution cache: lookup-cache rows (key → resolved food) and their citation
 * sources. Split out of [NutrientRepository] (facade).
 */
class LookupCacheRepository(
    private val lookupCacheDao: LookupCacheDao,
    private val sourceDao: SourceDao
) {
    suspend fun byKey(key: String): LookupCacheEntity? = lookupCacheDao.byKey(key)

    suspend fun byResolvedFoodId(foodId: String): List<LookupCacheEntity> =
        lookupCacheDao.byResolvedFoodId(foodId)

    suspend fun lookupKeyForFood(foodId: String): LookupCacheEntity? =
        lookupCacheDao.byResolvedFoodId(foodId).firstOrNull()

    suspend fun upsert(entry: LookupCacheEntity) = lookupCacheDao.upsert(entry)

    /** Popularity telemetry on cache hit (ARCHITECTURE.md §5). */
    suspend fun incrementHitCount(key: String) = lookupCacheDao.incrementHitCount(key)

    suspend fun cacheStats(): Triple<Int, Long?, Long?> = Triple(
        lookupCacheDao.count(),
        lookupCacheDao.oldestFetchedAt(),
        lookupCacheDao.newestFetchedAt()
    )

    suspend fun sourcesForLookup(lookupKey: String): List<SourceEntity> =
        sourceDao.forLookup(lookupKey)

    suspend fun recordSources(sources: List<SourceEntity>) = sourceDao.insertAll(sources)
}
