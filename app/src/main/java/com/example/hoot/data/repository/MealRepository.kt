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

    /**
     * Claim-time attempt bookkeeping (restart-churn fix, 2026-09-23): every
     * row queued for THIS drain is bumped BEFORE the LLM work starts, so a
     * process killed mid-drain still records the attempt and rows converge
     * to the attempt cap across launches. Successful rows leave the queue
     * via foodId regardless of the counter.
     */
    suspend fun claimIngredientAttempts(ids: List<String>) {
        if (ids.isNotEmpty()) ingredientDao.bumpResolveAttempts(ids)
    }

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

    /** Kill-safe claim bookkeeping — see [claimIngredientAttempts]. */
    suspend fun claimSupplementAttempts(ids: List<String>) {
        if (ids.isNotEmpty()) supplementDao.bumpResolveAttempts(ids)
    }

    suspend fun supplementByLabel(label: String): SupplementEntity? =
        supplementDao.byLabel(label)

    suspend fun upsertSupplement(supplement: SupplementEntity) =
        supplementDao.upsert(supplement)

    suspend fun upsertSupplements(supplements: List<SupplementEntity>) =
        supplementDao.upsertAll(supplements)

    suspend fun supplementsByIds(ids: List<String>): List<SupplementEntity> =
        supplementDao.byIds(ids)

    // ---- Resolution-state-preserving ingest (restart re-analysis fix) -----

    /**
     * Rows that were GENUINELY NEW (unchanged re-served upserts excluded).
     * [mealsUpdated] counts EXISTING rows whose Tail payload changed (Tail
     * fills meal placeholders in-place after its async analysis — the
     * hollow "Meal (0 kcal)" row is rewritten with title/macros/ingredients
     * and an EARLIER canonical timestamp); [changedDays] lists the days
     * whose ledger must be recomputed as a result.
     */
    data class IngestCounts(
        val meals: Int,
        val supplements: Int,
        val mealsUpdated: Int = 0,
        val changedDays: List<String> = emptyList()
    )

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
        var updatedMeals = 0
        val changedDays = LinkedHashSet<String>()
        if (meals.isNotEmpty()) {
            val existingById = mealDao.byIds(meals.map { it.id }).associateBy { it.id }
            newMeals = meals.count { it.id !in existingById }
            // Payload-change detection (hollow-meal refresh fix, 2026-09-23):
            // Tail creates meal rows as sparse placeholders ("Meal", 0 kcal)
            // and fills them in-place once its async analysis lands — with a
            // canonical (earlier) timestamp. The incremental cursor can never
            // re-serve such rows, so the sync full-pulls and we merge the
            // enriched payload here.
            val changed = meals.filter { fresh ->
                existingById[fresh.id]?.let { mealPayloadChanged(it, fresh) } == true
            }
            updatedMeals = changed.size
            mealDao.upsertAll(meals)
            for (m in changed) { existingById[m.id]?.day?.let(changedDays::add); m.day.let(changedDays::add) }
            if (ingredients.isNotEmpty()) {
                val mealsWithRows = allIngredientsForMeals(ingredients.map { it.mealId }.distinct())
                    .map { it.mealId }.toSet()
                val fresh = ingredients.filter { it.mealId !in mealsWithRows }
                if (fresh.isNotEmpty()) ingredientDao.insertAll(fresh)
                // Changed meals: their stored ingredient rows were parsed from
                // the STALE placeholder rawText ("Meal (0 kcal)" — the source
                // of the junk unresolved-queue entries). Replace them with
                // rows parsed from the enriched payload; fresh rows carry
                // resolveAttempts=0 so the resolver picks them up cleanly.
                val changedIds = changed.map { it.id }.toSet()
                for (mealId in changedIds) {
                    val freshRows = ingredients.filter { it.mealId == mealId }
                    if (freshRows.isNotEmpty()) {
                        ingredientDao.deleteForMeal(mealId)
                        ingredientDao.insertAll(freshRows)
                    }
                }
            }
        }
        return IngestCounts(
            meals = newMeals,
            supplements = newSupplements,
            mealsUpdated = updatedMeals,
            changedDays = changedDays.toList()
        )
    }

    companion object {
        /** Attempts before an item leaves the auto-retry queue (manual retry resets). */
        const val RESOLVE_ATTEMPT_CAP = 3

        /**
         * True when a re-served Tail meal row carries DIFFERENT content than
         * the stored row (pure, JVM-testable). Timestamp/day are compared
         * too: Tail's async analysis rewrites the placeholder's creation
         * timestamp to the canonical log instant, which is how the enriched
         * row must be detected even when every other column happened to
         * match.
         */
        fun mealPayloadChanged(existing: MealEntity, fresh: MealEntity): Boolean =
            existing.title != fresh.title ||
                existing.rawText != fresh.rawText ||
                existing.summary != fresh.summary ||
                existing.calories != fresh.calories ||
                existing.proteinGrams != fresh.proteinGrams ||
                existing.carbsGrams != fresh.carbsGrams ||
                existing.fatGrams != fresh.fatGrams ||
                existing.isVegan != fresh.isVegan ||
                existing.healthNotes != fresh.healthNotes ||
                existing.timestamp != fresh.timestamp ||
                existing.day != fresh.day

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
