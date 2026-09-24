package com.example.hoot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.example.hoot.data.local.entity.FoodEntity
import com.example.hoot.data.local.entity.FoodNutrientProfileEntity
import com.example.hoot.data.local.entity.LookupCacheEntity
import com.example.hoot.data.local.entity.NutrientDefinitionEntity
import com.example.hoot.data.local.entity.SourceEntity
import kotlinx.coroutines.flow.Flow

/** DAO for [NutrientDefinitionEntity] — the seeded canonical nutrient table. */
@Dao
interface NutrientDao {
    @Query("SELECT * FROM nutrient_definitions ORDER BY tier ASC, name ASC")
    fun observeAll(): Flow<List<NutrientDefinitionEntity>>

    @Query("SELECT * FROM nutrient_definitions WHERE id = :id")
    suspend fun byId(id: String): NutrientDefinitionEntity?

    @Query("SELECT * FROM nutrient_definitions WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<NutrientDefinitionEntity>

    @Query("SELECT COUNT(*) FROM nutrient_definitions")
    suspend fun count(): Int

    @Query("SELECT * FROM nutrient_definitions")
    suspend fun all(): List<NutrientDefinitionEntity>

    @Upsert
    suspend fun upsertAll(definitions: List<NutrientDefinitionEntity>)

    @Query("DELETE FROM nutrient_definitions WHERE id = :id")
    suspend fun delete(id: String)
}

/** DAO for [FoodEntity] — known foods. */
@Dao
interface FoodDao {
    @Query("SELECT * FROM foods ORDER BY displayName ASC")
    fun observeAll(): Flow<List<FoodEntity>>

    @Query("SELECT * FROM foods WHERE id = :id")
    suspend fun byId(id: String): FoodEntity?

    @Query("SELECT * FROM foods WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun byNormalizedName(normalizedName: String): FoodEntity?

    @Query("SELECT * FROM foods WHERE isSupplement = 0 ORDER BY displayName ASC")
    fun observeFoods(): Flow<List<FoodEntity>>

    @Query("SELECT * FROM foods")
    suspend fun all(): List<FoodEntity>

    @Query("SELECT COUNT(*) FROM foods")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(food: FoodEntity)

    @Upsert
    suspend fun upsertAll(foods: List<FoodEntity>)

    @Query("DELETE FROM foods WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM foods")
    suspend fun clearAll()
}

/** DAO for [FoodNutrientProfileEntity] — per-100g nutrition panels. */
@Dao
interface ProfileDao {
    @Query("SELECT * FROM food_nutrient_profile WHERE foodId = :foodId LIMIT 1")
    suspend fun forFood(foodId: String): FoodNutrientProfileEntity?

    @Query("SELECT * FROM food_nutrient_profile")
    fun observeAll(): Flow<List<FoodNutrientProfileEntity>>

    @Query("SELECT * FROM food_nutrient_profile WHERE id = :id")
    suspend fun byId(id: String): FoodNutrientProfileEntity?

    @Query("SELECT COUNT(*) FROM food_nutrient_profile")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(profile: FoodNutrientProfileEntity)

    @Upsert
    suspend fun upsertAll(profiles: List<FoodNutrientProfileEntity>)

    @Query("DELETE FROM food_nutrient_profile")
    suspend fun clearAll()

    @Query("DELETE FROM food_nutrient_profile WHERE id = :id")
    suspend fun delete(id: String)
}

/** DAO for [LookupCacheEntity] — first stop of the resolution pipeline. */
@Dao
interface LookupCacheDao {
    @Query("SELECT * FROM lookup_cache WHERE normalizedKey = :key LIMIT 1")
    suspend fun byKey(key: String): LookupCacheEntity?

    @Query("SELECT * FROM lookup_cache WHERE resolvedFoodId = :foodId")
    suspend fun byResolvedFoodId(foodId: String): List<LookupCacheEntity>

    @Query("SELECT * FROM lookup_cache ORDER BY hitCount DESC")
    fun observeAllByHits(): Flow<List<LookupCacheEntity>>

    @Query("SELECT COUNT(*) FROM lookup_cache")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM lookup_cache")
    suspend fun count(): Int

    @Query("SELECT MIN(fetchedAt) FROM lookup_cache")
    suspend fun oldestFetchedAt(): Long?

    @Query("SELECT MAX(fetchedAt) FROM lookup_cache")
    suspend fun newestFetchedAt(): Long?

    @Upsert
    suspend fun upsert(entry: LookupCacheEntity)

    /** Popularity telemetry — called on every cache hit. */
    @Query("UPDATE lookup_cache SET hitCount = hitCount + 1 WHERE normalizedKey = :key")
    suspend fun incrementHitCount(key: String)

    @Query("DELETE FROM lookup_cache WHERE normalizedKey = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM lookup_cache")
    suspend fun clearAll()
}

/** DAO for [SourceEntity] — citation records backing resolved profiles. */
@Dao
interface SourceDao {
    @Query("SELECT * FROM sources WHERE lookupKey = :lookupKey")
    fun observeForLookup(lookupKey: String): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE lookupKey = :lookupKey")
    suspend fun forLookup(lookupKey: String): List<SourceEntity>

    @Query("SELECT COUNT(*) FROM sources")
    suspend fun count(): Int

    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertAll(sources: List<SourceEntity>)

    @Query("DELETE FROM sources")
    suspend fun clearAll()
}
