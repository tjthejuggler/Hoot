package com.example.hoot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.example.hoot.data.local.entity.IngredientEntity
import com.example.hoot.data.local.entity.MealEntity
import com.example.hoot.data.local.entity.SupplementEntity
import kotlinx.coroutines.flow.Flow

/** DAO for [MealEntity] — the meal diary (Tail-synced or manual). */
@Dao
interface MealDao {
    @Query("SELECT * FROM meals ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<MealEntity>>

    @Query("SELECT * FROM meals WHERE day = :day ORDER BY timestamp ASC")
    fun observeByDay(day: String): Flow<List<MealEntity>>

    @Query("SELECT * FROM meals WHERE day = :day ORDER BY timestamp ASC")
    suspend fun byDay(day: String): List<MealEntity>

    @Query("SELECT * FROM meals WHERE id = :id")
    suspend fun byId(id: String): MealEntity?

    @Query("SELECT * FROM meals WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<MealEntity>

    @Query("SELECT MAX(timestamp) FROM meals WHERE source = 'tail'")
    suspend fun latestTailTimestamp(): Long?

    @Query("SELECT DISTINCT day FROM meals")
    suspend fun distinctDays(): List<String>

    /**
     * Tail meals without any ingredient rows (legacy backlog ingested before
     * the ingredient-derivation fix). The startup self-heal parses these and
     * inserts the missing rows so the resolver/aggregator can see them.
     */
    @Query(
        "SELECT * FROM meals WHERE source = 'tail' AND " +
            "id NOT IN (SELECT DISTINCT mealId FROM ingredients)"
    )
    suspend fun tailMealsWithoutIngredients(): List<MealEntity>

    @Query("SELECT COUNT(*) FROM meals")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(meal: MealEntity)

    @Upsert
    suspend fun upsertAll(meals: List<MealEntity>)

    @Query("DELETE FROM meals WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM meals")
    suspend fun clearAll()
}

/** DAO for [IngredientEntity] — parsed ingredient rows of a meal. */
@Dao
interface IngredientDao {
    @Query("SELECT * FROM ingredients WHERE mealId = :mealId")
    fun observeForMeal(mealId: String): Flow<List<IngredientEntity>>

    @Query("SELECT * FROM ingredients WHERE mealId = :mealId")
    suspend fun forMeal(mealId: String): List<IngredientEntity>

    @Query("SELECT * FROM ingredients WHERE id = :id")
    suspend fun byId(id: String): IngredientEntity?

    /**
     * Pending nutrition resolution: no food linked yet AND fewer than the
     * attempt cap. Rows that failed [RESOLVE_ATTEMPT_CAP] times stay out of
     * the queue so restarts never re-analyze permanently-failed items.
     */
    @Query(
        "SELECT * FROM ingredients WHERE foodId IS NULL " +
            "AND resolveAttempts < 3 ORDER BY rowid ASC"
    )
    suspend fun unresolved(): List<IngredientEntity>

    /** Currently-processing count for the Today chip (same filter as [unresolved]). */
    @Query(
        "SELECT COUNT(*) FROM ingredients WHERE foodId IS NULL " +
            "AND resolveAttempts < 3"
    )
    suspend fun unresolvedCount(): Int

    @Query("SELECT * FROM ingredients WHERE foodId = :foodId")
    suspend fun forFood(foodId: String): List<IngredientEntity>

    @Query("SELECT COUNT(*) FROM ingredients")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(ingredients: List<IngredientEntity>)

    @Upsert
    suspend fun upsert(ingredient: IngredientEntity)

    @Query("DELETE FROM ingredients WHERE mealId = :mealId")
    suspend fun deleteForMeal(mealId: String)

    @Query("DELETE FROM ingredients WHERE id = :id")
    suspend fun delete(id: String)

    /** Manual-retry hook: give every unresolved row a fresh set of attempts. */
    @Query("UPDATE ingredients SET resolveAttempts = 0 WHERE foodId IS NULL")
    suspend fun resetResolveAttempts()

    /**
     * Claim-time attempt bookkeeping (restart-churn fix, 2026-09-23): bumps
     * every row queued for THIS drain BEFORE any LLM work starts. A process
     * killed mid-drain still records the attempt, so rows converge to the
     * attempt cap across launches instead of re-queueing forever.
     */
    @Query("UPDATE ingredients SET resolveAttempts = resolveAttempts + 1 WHERE id IN (:ids)")
    suspend fun bumpResolveAttempts(ids: List<String>)
}

/** DAO for [SupplementEntity] — Tail "Took Pills" entries. */
@Dao
interface SupplementDao {
    @Query("SELECT * FROM supplements ORDER BY label ASC")
    fun observeAll(): Flow<List<SupplementEntity>>

    @Query("SELECT * FROM supplements WHERE day = :day ORDER BY timestamp ASC")
    fun observeByDay(day: String): Flow<List<SupplementEntity>>

    @Query("SELECT * FROM supplements WHERE day = :day ORDER BY timestamp ASC")
    suspend fun byDay(day: String): List<SupplementEntity>

    /**
     * Pending nutrition resolution: no contributions and no resolved food yet,
     * capped by attempts so permanently-failed rows stop re-enqueueing.
     */
    @Query(
        "SELECT * FROM supplements WHERE nutrientContributions = '[]' " +
            "AND resolvedFoodId IS NULL AND resolveAttempts < 3 ORDER BY rowid ASC"
    )
    suspend fun unresolved(): List<SupplementEntity>

    /** Currently-processing count for the Today chip (same filter as [unresolved]). */
    @Query(
        "SELECT COUNT(*) FROM supplements WHERE nutrientContributions = '[]' " +
            "AND resolvedFoodId IS NULL AND resolveAttempts < 3"
    )
    suspend fun unresolvedCount(): Int

    @Query("SELECT * FROM supplements WHERE id = :id")
    suspend fun byId(id: String): SupplementEntity?

    @Query("SELECT * FROM supplements WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<SupplementEntity>

    @Query("SELECT * FROM supplements WHERE label = :label LIMIT 1")
    suspend fun byLabel(label: String): SupplementEntity?

    @Query("SELECT COUNT(*) FROM supplements")
    suspend fun count(): Int

    @Query("SELECT DISTINCT day FROM supplements")
    suspend fun distinctDays(): List<String>

    @Upsert
    suspend fun upsert(supplement: SupplementEntity)

    @Upsert
    suspend fun upsertAll(supplements: List<SupplementEntity>)

    @Query("DELETE FROM supplements WHERE id = :id")
    suspend fun delete(id: String)

    /** Manual-retry hook: give every unresolved row a fresh set of attempts. */
    @Query("UPDATE supplements SET resolveAttempts = 0 WHERE nutrientContributions = '[]' AND resolvedFoodId IS NULL")
    suspend fun resetResolveAttempts()

    /** Kill-safe claim bookkeeping — see [IngredientDao.bumpResolveAttempts]. */
    @Query("UPDATE supplements SET resolveAttempts = resolveAttempts + 1 WHERE id IN (:ids)")
    suspend fun bumpResolveAttempts(ids: List<String>)

    @Query("DELETE FROM supplements")
    suspend fun clearAll()
}
