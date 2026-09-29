package com.example.hoot.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.hoot.data.local.entity.FoodKnowledgeEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [FoodKnowledgeEntity] — the permanent food-knowledge base
 * (feedback 2026-09-27). Independent of the TTL lookup cache: rows live
 * until the user explicitly deletes them.
 */
@Dao
interface FoodKnowledgeDao {
    @Query("SELECT * FROM food_knowledge ORDER BY displayName ASC")
    fun observeAll(): Flow<List<FoodKnowledgeEntity>>

    @Query("SELECT * FROM food_knowledge")
    suspend fun all(): List<FoodKnowledgeEntity>

    @Query("SELECT * FROM food_knowledge WHERE normalizedName = :key LIMIT 1")
    suspend fun byName(key: String): FoodKnowledgeEntity?

    @Query("SELECT COUNT(*) FROM food_knowledge")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(entry: FoodKnowledgeEntity)

    @Upsert
    suspend fun upsertAll(entries: List<FoodKnowledgeEntity>)

    @Query("DELETE FROM food_knowledge WHERE normalizedName = :key")
    suspend fun deleteByName(key: String)

    @Query("DELETE FROM food_knowledge")
    suspend fun clearAll()
}
