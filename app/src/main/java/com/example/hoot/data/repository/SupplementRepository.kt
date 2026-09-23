package com.example.hoot.data.repository

import com.example.hoot.data.local.dao.SupplementDao
import com.example.hoot.data.local.entity.SupplementEntity

/**
 * Supplement rows captured from Tail pills habits / manual entry, including
 * the JSON `nutrientContributions` written by supplement resolution. Split
 * out of [NutrientRepository] (facade).
 */
class SupplementRepository(
    private val supplementDao: SupplementDao
) {
    suspend fun byLabel(label: String): SupplementEntity? =
        supplementDao.byLabel(label)

    suspend fun upsert(supplement: SupplementEntity) = supplementDao.upsert(supplement)
}
