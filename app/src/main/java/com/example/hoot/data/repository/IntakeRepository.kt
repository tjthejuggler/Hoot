package com.example.hoot.data.repository

import com.example.hoot.data.local.dao.IntakeDao
import com.example.hoot.data.local.dao.NutrientDayTotal
import com.example.hoot.data.local.entity.NutrientIntakeEntity
import kotlinx.coroutines.flow.Flow

/**
 * The intake ledger: per-(nutrient, day, source-row) amounts plus the
 * aggregation-ready total queries that back the ScoreEngine, dashboards and
 * history charts. Split out of [NutrientRepository] (facade).
 */
class IntakeRepository(
    private val intakeDao: IntakeDao
) {
    fun observeIntakeByDay(day: String): Flow<List<NutrientIntakeEntity>> =
        intakeDao.observeByDay(day)

    fun observeIntakeRange(from: String, to: String): Flow<List<NutrientIntakeEntity>> =
        intakeDao.observeRange(from, to)

    suspend fun dailyTotals(day: String): List<NutrientDayTotal> = intakeDao.dailyTotals(day)

    /** Per-meal contribution ranking for one nutrient over a window (top sources). */
    suspend fun mealContributions(
        nutrientId: String,
        from: String,
        to: String
    ): List<com.example.hoot.data.local.dao.MealContribution> =
        intakeDao.mealContributions(nutrientId, from, to)

    suspend fun rangeTotals(from: String, to: String): List<NutrientDayTotal> =
        intakeDao.rangeTotals(from, to)

    /** Per-(nutrient, day) totals across a window (history series). */
    suspend fun dailyTotalsForWindow(from: String, to: String): List<NutrientDayTotal> =
        intakeDao.dailyTotalsRange(from, to)

    suspend fun logIntake(entries: List<NutrientIntakeEntity>) = intakeDao.upsertAll(entries)

    suspend fun clearIntakeForMeal(mealId: String) = intakeDao.deleteForMeal(mealId)

    suspend fun clearIntakeForSupplement(supplementId: String) =
        intakeDao.deleteForSupplement(supplementId)

    /** Idempotent per-day recompute: wipe the day's ledger before re-aggregation. */
    suspend fun clearIntakeForDay(day: String) = intakeDao.deleteForDay(day)

    /** Reactive per-nutrient totals for one day (dashboard bars). */
    fun observeDailyTotals(day: String): Flow<List<NutrientDayTotal>> =
        intakeDao.observeDailyTotals(day)

    /** Reactive per-(nutrient, day) totals across a range (history charts). */
    fun observeDailyTotalsRange(from: String, to: String): Flow<List<NutrientDayTotal>> =
        intakeDao.observeDailyTotalsRange(from, to)
}
