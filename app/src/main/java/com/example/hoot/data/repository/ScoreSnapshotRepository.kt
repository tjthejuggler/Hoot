package com.example.hoot.data.repository

import com.example.hoot.data.local.dao.ScoreSnapshotDao
import com.example.hoot.data.local.entity.ScoreSnapshotEntity
import kotlinx.coroutines.flow.Flow

/**
 * Daily score snapshots (ScoreEngine output) and their history series. Split
 * out of [NutrientRepository] (facade).
 */
class ScoreSnapshotRepository(
    private val scoreSnapshotDao: ScoreSnapshotDao
) {
    suspend fun byDay(day: String): ScoreSnapshotEntity? = scoreSnapshotDao.byDay(day)

    fun observeFrom(from: String): Flow<List<ScoreSnapshotEntity>> =
        scoreSnapshotDao.observeFrom(from)

    suspend fun upsert(snapshot: ScoreSnapshotEntity) = scoreSnapshotDao.upsert(snapshot)
}
