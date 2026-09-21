package com.example.hoot.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RoomWarnings
import androidx.room.Upsert
import com.example.hoot.data.local.entity.DietaryProfileEntity
import com.example.hoot.data.local.entity.NutrientGoalEntity
import com.example.hoot.data.local.entity.NutrientIntakeEntity
import com.example.hoot.data.local.entity.RecommendationEntity
import com.example.hoot.data.local.entity.ScoreSnapshotEntity
import com.example.hoot.data.local.entity.TailAppConfigEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [NutrientIntakeEntity] — per-nutrient-per-day ledger. Daily totals
 * are `SUM(amount)` grouped by (nutrient, day); stats screens observe these.
 */
@Dao
interface IntakeDao {
    @Query("SELECT * FROM nutrient_intake_log WHERE day = :day")
    fun observeByDay(day: String): Flow<List<NutrientIntakeEntity>>

    @Query("SELECT * FROM nutrient_intake_log WHERE day BETWEEN :from AND :to")
    fun observeRange(from: String, to: String): Flow<List<NutrientIntakeEntity>>

    @SuppressWarnings(RoomWarnings.QUERY_MISMATCH) // day is null by design (single-day sums)
    @Query(
        "SELECT nutrientId, SUM(amount) AS total FROM nutrient_intake_log " +
            "WHERE day = :day GROUP BY nutrientId"
    )
    suspend fun dailyTotals(day: String): List<NutrientDayTotal>

    @SuppressWarnings(RoomWarnings.QUERY_MISMATCH) // day is null by design (range sums)
    @Query(
        "SELECT nutrientId, SUM(amount) AS total FROM nutrient_intake_log " +
            "WHERE day BETWEEN :from AND :to GROUP BY nutrientId"
    )
    suspend fun rangeTotals(from: String, to: String): List<NutrientDayTotal>

    @Query(
        "SELECT nutrientId, day, SUM(amount) AS total FROM nutrient_intake_log " +
            "WHERE day BETWEEN :from AND :to GROUP BY nutrientId, day ORDER BY day ASC"
    )
    suspend fun dailyTotalsRange(from: String, to: String): List<NutrientDayTotal>

    @SuppressWarnings(RoomWarnings.QUERY_MISMATCH) // day is null by design (single-day sums)
    @Query(
        "SELECT nutrientId, SUM(amount) AS total FROM nutrient_intake_log " +
            "WHERE day = :day GROUP BY nutrientId"
    )
    fun observeDailyTotals(day: String): Flow<List<NutrientDayTotal>>

    @Query(
        "SELECT nutrientId, day, SUM(amount) AS total FROM nutrient_intake_log " +
            "WHERE day BETWEEN :from AND :to GROUP BY nutrientId, day ORDER BY day ASC"
    )
    fun observeDailyTotalsRange(from: String, to: String): Flow<List<NutrientDayTotal>>

    @Query("SELECT COUNT(*) FROM nutrient_intake_log")
    suspend fun count(): Int

    @Upsert
    suspend fun upsertAll(entries: List<NutrientIntakeEntity>)

    @Query("DELETE FROM nutrient_intake_log WHERE sourceMealId = :mealId")
    suspend fun deleteForMeal(mealId: String)

    @Query("DELETE FROM nutrient_intake_log WHERE sourceSupplementId = :supplementId")
    suspend fun deleteForSupplement(supplementId: String)

    /** Per-meal contribution rows for one nutrient over a window (top-sources). */
    @Query(
        "SELECT sourceMealId AS mealId, SUM(amount) AS total FROM nutrient_intake_log " +
            "WHERE nutrientId = :nutrientId AND day BETWEEN :from AND :to " +
            "AND sourceMealId IS NOT NULL GROUP BY sourceMealId ORDER BY total DESC"
    )
    suspend fun mealContributions(nutrientId: String, from: String, to: String): List<MealContribution>

    /** Idempotent per-day recompute: clears a day's ledger before re-aggregation. */
    @Query("DELETE FROM nutrient_intake_log WHERE day = :day")
    suspend fun deleteForDay(day: String)

    @Query("DELETE FROM nutrient_intake_log")
    suspend fun clearAll()
}

/** Projection row: summed intake of one nutrient over a day/range.
 *  `day` is only populated by the per-(nutrient, day) grouped queries. */
data class NutrientDayTotal(
    val nutrientId: String,
    val day: String? = null,
    val total: Double
)

/** Projection row: one meal's cumulative contribution to a nutrient. */
data class MealContribution(val mealId: String, val total: Double)

/** DAO for [TailAppConfigEntity] — singleton Tail-integration config. */
@Dao
interface TailConfigDao {
    @Query("SELECT * FROM tail_app_config WHERE id = 1")
    fun observe(): Flow<TailAppConfigEntity?>

    @Query("SELECT * FROM tail_app_config WHERE id = 1")
    suspend fun get(): TailAppConfigEntity?

    @Upsert
    suspend fun upsert(config: TailAppConfigEntity)
}

/** DAO for [DietaryProfileEntity] — singleton dietary profile. */
@Dao
interface DietaryProfileDao {
    @Query("SELECT * FROM dietary_profile WHERE id = 1")
    fun observe(): Flow<DietaryProfileEntity?>

    @Query("SELECT * FROM dietary_profile WHERE id = 1")
    suspend fun get(): DietaryProfileEntity?

    @Upsert
    suspend fun upsert(profile: DietaryProfileEntity)
}

/** DAO for [NutrientGoalEntity] — per-nutrient targets (RDA default or custom). */
@Dao
interface NutrientGoalDao {
    @Query("SELECT * FROM nutrient_goals ORDER BY priority ASC")
    fun observeAll(): Flow<List<NutrientGoalEntity>>

    @Query("SELECT * FROM nutrient_goals WHERE nutrientId = :nutrientId LIMIT 1")
    suspend fun byNutrientId(nutrientId: String): NutrientGoalEntity?

    @Query("SELECT * FROM nutrient_goals")
    suspend fun all(): List<NutrientGoalEntity>

    @Query("SELECT COUNT(*) FROM nutrient_goals")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(goal: NutrientGoalEntity)

    @Upsert
    suspend fun upsertAll(goals: List<NutrientGoalEntity>)

    @Query("DELETE FROM nutrient_goals WHERE nutrientId = :nutrientId")
    suspend fun deleteForNutrient(nutrientId: String)
}

/** DAO for [RecommendationEntity] — issued recommendations (phase 3+). */
@Dao
interface RecommendationDao {
    @Query("SELECT * FROM recommendation_log WHERE day = :day ORDER BY rowid DESC")
    fun observeByDay(day: String): Flow<List<RecommendationEntity>>

    @Query(
        "SELECT * FROM recommendation_log WHERE accepted IS NULL " +
            "ORDER BY day DESC LIMIT :limit"
    )
    fun observeOpen(limit: Int): Flow<List<RecommendationEntity>>

    @Query("SELECT * FROM recommendation_log WHERE id = :id")
    suspend fun byId(id: String): RecommendationEntity?

    @Query(
        "SELECT * FROM recommendation_log WHERE day BETWEEN :from AND :to " +
            "ORDER BY day DESC"
    )
    suspend fun between(from: String, to: String): List<RecommendationEntity>

    @Query("SELECT * FROM recommendation_log ORDER BY day DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RecommendationEntity>>

    @Query(
        "SELECT COUNT(*) FROM recommendation_log WHERE day BETWEEN :from AND :to " +
            "AND accepted = 1"
    )
    suspend fun acceptedCount(from: String, to: String): Int

    @Query(
        "SELECT COUNT(*) FROM recommendation_log WHERE day BETWEEN :from AND :to " +
            "AND accepted IS NOT NULL"
    )
    suspend fun answeredCount(from: String, to: String): Int

    @Query("SELECT COUNT(*) FROM recommendation_log WHERE day BETWEEN :from AND :to")
    suspend fun countBetween(from: String, to: String): Int

    /** Distinct deficiencies already targeted on a day (dedup on re-issue). */
    @Query(
        "SELECT DISTINCT nutrientId FROM recommendation_log WHERE day = :day"
    )
    suspend fun nutrientIdsForDay(day: String): List<String>

    @Query("UPDATE recommendation_log SET accepted = :accepted WHERE id = :id")
    suspend fun setAccepted(id: String, accepted: Boolean?)

    @Upsert
    suspend fun upsert(recommendation: RecommendationEntity)

    @Query("DELETE FROM recommendation_log")
    suspend fun clearAll()

    /** Full scan for the diet-violation purge (diet-fix hardening, 2026-09). */
    @Query("SELECT * FROM recommendation_log")
    suspend fun all(): List<RecommendationEntity>

    /** Deletes specific rows (no-op guard on empty input lives in the repository). */
    @Query("DELETE FROM recommendation_log WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

/** DAO for [ScoreSnapshotEntity] — daily score history. */
@Dao
interface ScoreSnapshotDao {
    @Query("SELECT * FROM score_snapshot WHERE day = :day")
    suspend fun byDay(day: String): ScoreSnapshotEntity?

    @Query("SELECT * FROM score_snapshot WHERE day >= :from ORDER BY day ASC")
    fun observeFrom(from: String): Flow<List<ScoreSnapshotEntity>>

    @Query("SELECT * FROM score_snapshot ORDER BY day DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ScoreSnapshotEntity>>

    @Upsert
    suspend fun upsert(snapshot: ScoreSnapshotEntity)

    @Query("DELETE FROM score_snapshot")
    suspend fun clearAll()
}
