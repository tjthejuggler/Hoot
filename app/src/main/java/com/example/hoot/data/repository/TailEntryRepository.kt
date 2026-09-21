package com.example.hoot.data.repository

import com.example.hoot.data.local.dao.TailEntryDao
import com.example.hoot.data.local.entity.TailEntryEntity
import kotlinx.coroutines.flow.Flow

import com.example.hoot.domain.nutrition.WaterIntake

/** Thin facade over [TailEntryDao] for Tail water/misc habit entries. */
class TailEntryRepository(private val dao: TailEntryDao) {
    fun observeByDay(day: String): Flow<List<TailEntryEntity>> = dao.observeByDay(day)

    suspend fun byDay(day: String): List<TailEntryEntity> = dao.byDay(day)

    suspend fun byKindAndDay(kind: String, day: String): List<TailEntryEntity> =
        dao.byKindAndDay(kind, day)

    /** Days holding water rows (ledger recompute coverage for water-only days). */
    suspend fun distinctWaterDays(): List<String> =
        dao.distinctDaysForKind(WaterIntake.KIND_WATER)

    suspend fun upsertAll(entries: List<TailEntryEntity>) = dao.upsertAll(entries)
}
