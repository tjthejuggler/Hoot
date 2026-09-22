package com.example.hoot.data.repository

import com.example.hoot.data.local.dao.IngredientDao
import com.example.hoot.data.local.dao.MealDao
import com.example.hoot.data.local.dao.SupplementDao
import com.example.hoot.data.local.entity.IngredientEntity
import com.example.hoot.data.local.entity.MealEntity
import com.example.hoot.data.local.entity.SupplementEntity
import kotlinx.coroutines.flow.Flow

/** Thin facade over [MealDao]/[IngredientDao]/[SupplementDao]. */
class MealRepository(
    private val mealDao: MealDao,
    private val ingredientDao: IngredientDao,
    private val supplementDao: SupplementDao
) {
    // ---- Meals -----------------------------------------------------------
    fun observeAll(): Flow<List<MealEntity>> = mealDao.observeAll()

    fun observeByDay(day: String): Flow<List<MealEntity>> = mealDao.observeByDay(day)

    suspend fun mealsByDay(day: String): List<MealEntity> = mealDao.byDay(day)

    fun observeSupplementsByDay(day: String): Flow<List<SupplementEntity>> =
        supplementDao.observeByDay(day)

    suspend fun meal(id: String): MealEntity? = mealDao.byId(id)

    suspend fun mealsByIds(ids: List<String>): List<MealEntity> = mealDao.byIds(ids)

    suspend fun latestTailTimestamp(): Long? = mealDao.latestTailTimestamp()

    /** Tail meals missing their derived ingredient rows (startup self-heal source). */
    suspend fun tailMealsWithoutIngredients(): List<MealEntity> =
        mealDao.tailMealsWithoutIngredients()

    suspend fun distinctMealDays(): List<String> = mealDao.distinctDays()

    suspend fun distinctSupplementDays(): List<String> = supplementDao.distinctDays()

    suspend fun count(): Int = mealDao.count()

    suspend fun upsert(meal: MealEntity) = mealDao.upsert(meal)

    suspend fun upsertAll(meals: List<MealEntity>) = mealDao.upsertAll(meals)

    /** Deletes a meal and its ingredient rows (aggregation ledger rows are cleared separately). */
    suspend fun delete(meal: String) = mealDao.delete(meal)

    // ---- Ingredients -----------------------------------------------------
    fun observeIngredients(mealId: String): Flow<List<IngredientEntity>> =
        ingredientDao.observeForMeal(mealId)

    suspend fun ingredientsForMeal(mealId: String): List<IngredientEntity> =
        ingredientDao.forMeal(mealId)

    suspend fun ingredient(id: String): IngredientEntity? = ingredientDao.byId(id)

    /**
     * Ingredients still pending nutrition resolution (foodId IS NULL, under
     * the attempt cap). Rows at the cap are skipped — restarts never re-run them.
     */
    suspend fun unresolvedIngredients(): List<IngredientEntity> = ingredientDao.unresolved()

    suspend fun unresolvedIngredientCount(): Int = ingredientDao.unresolvedCount()

    suspend fun updateIngredient(ingredient: IngredientEntity) = ingredientDao.upsert(ingredient)

    /** Bumps the failure counter after a failed resolution attempt. */
    suspend fun markIngredientFailed(ingredient: IngredientEntity) =
        ingredientDao.upsert(ingredient.copy(resolveAttempts = ingredient.resolveAttempts + 1))

    suspend fun allIngredientsForMeals(mealIds: List<String>): List<IngredientEntity> =
        mealIds.flatMap { ingredientDao.forMeal(it) }

    /** Manual-retry hook: clears failure counters so capped rows re-queue. */
    suspend fun resetResolveAttempts() {
        ingredientDao.resetResolveAttempts()
        supplementDao.resetResolveAttempts()
    }

    suspend fun replaceIngredients(mealId: String, ingredients: List<IngredientEntity>) {
        ingredientDao.deleteForMeal(mealId)
        ingredientDao.insertAll(ingredients)
    }

    // ---- Supplements -----------------------------------------------------
    fun observeSupplements(): Flow<List<SupplementEntity>> = supplementDao.observeAll()

    suspend fun supplementsByDay(day: String): List<SupplementEntity> = supplementDao.byDay(day)

    /** Supplements still pending nutrient-contribution resolution (under the attempt cap). */
    suspend fun unresolvedSupplements(): List<SupplementEntity> = supplementDao.unresolved()

    suspend fun unresolvedSupplementCount(): Int = supplementDao.unresolvedCount()

    /** Bumps the failure counter after a failed resolution attempt. */
    suspend fun markSupplementFailed(supplement: SupplementEntity) =
        supplementDao.upsert(supplement.copy(resolveAttempts = supplement.resolveAttempts + 1))

    suspend fun supplementByLabel(label: String): SupplementEntity? =
        supplementDao.byLabel(label)

    suspend fun upsertSupplement(supplement: SupplementEntity) =
        supplementDao.upsert(supplement)

    suspend fun upsertSupplements(supplements: List<SupplementEntity>) =
        supplementDao.upsertAll(supplements)

    suspend fun supplementsByIds(ids: List<String>): List<SupplementEntity> =
        supplementDao.byIds(ids)

    // ---- Resolution-state-preserving ingest (restart re-analysis fix) -----

    /** Rows that were GENUINELY NEW (unchanged re-served upserts excluded). */
    data class IngestCounts(val meals: Int, val supplements: Int)

    /**
     * Upserts freshly-mapped Tail rows WITHOUT clobbering persisted
     * resolution state of rows already in the DB. Tail re-serves the same
     * entry ids on every full-backlog pass; a plain upsert would reset
     * `resolvedFoodId`/`nutrientContributions` to the fresh "unresolved"
     * mapping and force every item through the resolver again after each
     * restart (the reported "re-analyzes everything" bug). Calls
     * [mergePreservingResolved] per supplement.
     *
     * [ingredients] (derived from Tail meal texts by the sync layer) are
     * inserted only for meals that have none yet — re-inserting would wipe
     * the `foodId` link on already-resolved rows.
     *
     * @return [IngestCounts] with only genuinely-new rows. Callers must not
     * treat unchanged re-served upserts as "ingested" — the post-sync
     * resolver kick would otherwise fire on every restart even when nothing
     * new arrived (restart re-analysis bug, feedback 2026-09).
     */
    suspend fun ingestPreservingResolution(
        meals: List<MealEntity>,
        supplements: List<SupplementEntity>,
        ingredients: List<IngredientEntity> = emptyList()
    ): IngestCounts {
        var newSupplements = 0
        if (supplements.isNotEmpty()) {
            val ids = supplements.map { it.id }
            val existing = supplementDao.byIds(ids).associateBy { it.id }
            newSupplements = supplements.count { it.id !in existing }
            val merged = supplements.map { fresh ->
                existing[fresh.id]?.let { mergePreservingResolved(fresh, it) } ?: fresh
            }
            supplementDao.upsertAll(merged)
        }
        var newMeals = 0
        if (meals.isNotEmpty()) {
            val existingMealIds = mealDao.byIds(meals.map { it.id }).map { it.id }.toSet()
            newMeals = meals.count { it.id !in existingMealIds }
            mealDao.upsertAll(meals)
            if (ingredients.isNotEmpty()) {
                val mealsWithRows = allIngredientsForMeals(ingredients.map { it.mealId }.distinct())
                    .map { it.mealId }.toSet()
                val fresh = ingredients.filter { it.mealId !in mealsWithRows }
                if (fresh.isNotEmpty()) ingredientDao.insertAll(fresh)
            }
        }
        return IngestCounts(meals = newMeals, supplements = newSupplements)
    }

    companion object {
        /** Attempts before an item leaves the auto-retry queue (manual retry resets). */
        const val RESOLVE_ATTEMPT_CAP = 3

        /**
         * Pure merge: when an existing row already carries resolution state
         * (contributions or a resolved food), the fresh Tail payload may only
         * refresh provenance (label/text/day/timestamp) — never reset it.
         *
         * UNRESOLVED rows keep their failure counter too when the underlying
         * text is unchanged: Tail's full-history pull re-serves the same row
         * on every pass, and returning `fresh` unconditionally reset
         * `resolveAttempts` — attempt-capped rows were un-capped at every
         * sync and re-queued forever (the "Analyzing nutrition… N foods
         * left" chip on each fresh launch, feedback 2026-09). A genuinely
         * changed text is a new input and earns fresh attempts.
         */
        fun mergePreservingResolved(fresh: SupplementEntity, existing: SupplementEntity): SupplementEntity {
            val alreadyResolved = existing.nutrientContributions != "[]" || existing.resolvedFoodId != null
            if (!alreadyResolved) {
                val sameText = fresh.rawText == existing.rawText && fresh.label == existing.label
                return if (sameText) fresh.copy(resolveAttempts = existing.resolveAttempts) else fresh
            }
            return fresh.copy(
                resolvedFoodId = existing.resolvedFoodId,
                nutrientContributions = existing.nutrientContributions,
                doseAmount = existing.doseAmount ?: fresh.doseAmount,
                doseUnit = existing.doseUnit ?: fresh.doseUnit,
                resolveAttempts = existing.resolveAttempts
            )
        }
    }
}
