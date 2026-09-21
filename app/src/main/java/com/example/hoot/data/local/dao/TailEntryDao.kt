package com.example.hoot.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.hoot.data.local.entity.TailEntryEntity
import kotlinx.coroutines.flow.Flow

/** DAO for [TailEntryEntity] — Tail water/misc habit log entries. */
@Dao
interface TailEntryDao {
    @Query("SELECT * FROM tail_entries WHERE day = :day ORDER BY timestamp ASC")
    fun observeByDay(day: String): Flow<List<TailEntryEntity>>

    @Query("SELECT * FROM tail_entries WHERE day = :day ORDER BY timestamp ASC")
    suspend fun byDay(day: String): List<TailEntryEntity>

    @Query("SELECT * FROM tail_entries WHERE kind = :kind AND day = :day ORDER BY timestamp ASC")
    suspend fun byKindAndDay(kind: String, day: String): List<TailEntryEntity>

    /** Distinct day keys carrying [kind] rows (water-day recompute support). */
    @Query("SELECT DISTINCT day FROM tail_entries WHERE kind = :kind")
    suspend fun distinctDaysForKind(kind: String): List<String>

    @Upsert
    suspend fun upsertAll(entries: List<TailEntryEntity>)

    @Query("DELETE FROM tail_entries")
    suspend fun clearAll()
}
