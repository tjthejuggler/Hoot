package com.example.hoot.data.tail

import com.example.hoot.data.local.entity.SupplementEntity
import com.example.hoot.data.repository.MealRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tail meal ingestion must produce ingredient rows (Today-zeros bug) and
 * re-served pills entries must never reset persisted resolution state
 * (restart re-analysis bug).
 */
class TailMealIngredientMappingTest {

    private fun mealEntry(
        entryId: String,
        ts: Long = 1_758_240_000_000,
        ingredients: List<String> = emptyList(),
        title: String? = "Lunch"
    ) = TailMealEntry(
        entryId = entryId, habitName = "Food", timestamp = ts,
        title = title, summary = null, calories = null,
        proteinGrams = null, carbsGrams = null, fatGrams = null,
        ingredientsDetected = ingredients, isManual = false
    )

    // ── Bug 2: Tail meals now get ingredient rows for the aggregator ─────

    @Test fun `ingredientsDetected become ingredient rows linked to the meal`() {
        val e = mealEntry("e1", ingredients = listOf("150 g lentils", "spinach"))
        val rows = mealIngredientEntities(listOf(e))
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.mealId == "tail:e1" })
        assertTrue(rows.all { it.foodId == null })   // pending resolution
        // Quantities parsed for the resolver's gram estimation.
        val lentils = rows.first { it.rawText == "150 g lentils" }
        assertEquals(150.0, lentils.amount!!, 0.001)
        assertEquals("g", lentils.unit)
    }

    @Test fun `v1 text meals produce ingredient rows too`() {
        val text = TailTextEntry(
            entryId = "e9", habitName = "Food",
            timestampRaw = "2026-09-19 12:00:00", timestampMs = 1_758_240_000_000,
            text = "2 eggs, 1 cup rice"
        )
        val rows = textMealIngredientEntities(listOf(text), "Food")
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.mealId == "tail:e9" })
    }

    @Test fun `ingredient row ids are deterministic so re-sync is idempotent`() {
        val e = mealEntry("e1", ingredients = listOf("150 g lentils", "spinach"))
        val first = mealIngredientEntities(listOf(e))
        val second = mealIngredientEntities(listOf(e))
        assertEquals(first.map { it.id }, second.map { it.id })
    }

    @Test fun `invalid-timestamp meals contribute no ingredient rows`() {
        val rows = mealIngredientEntities(listOf(mealEntry("e1", ts = 0, ingredients = listOf("rice"))))
        assertEquals(0, rows.size)
    }

    // ── Bug 1: re-served rows keep persisted resolution state ────────────

    private fun freshSupp(id: String) = SupplementEntity(
        id = id, label = "iron", description = null, resolvedFoodId = null,
        doseAmount = null, doseUnit = null, nutrientContributions = "[]"
    )

    private fun resolvedSupp(id: String) = SupplementEntity(
        id = id, label = "iron", description = null, resolvedFoodId = "food-1",
        doseAmount = null, doseUnit = null, nutrientContributions = """{"iron":18.0}"""
    )

    @Test fun `re-synced fresh payload does not clobber a resolved row`() {
        val merged = MealRepository.mergePreservingResolved(freshSupp("s1"), resolvedSupp("s1"))
        assertEquals("food-1", merged.resolvedFoodId)
        assertEquals("""{"iron":18.0}""", merged.nutrientContributions)
    }

    @Test fun `re-synced payload refreshes provenance on a resolved row`() {
        val reSynced = freshSupp("s1").copy(rawText = "iron bisglycinate", timestamp = 99L, day = "2026-09-18")
        val merged = MealRepository.mergePreservingResolved(reSynced, resolvedSupp("s1"))
        assertEquals("iron bisglycinate", merged.rawText)
        assertEquals(99L, merged.timestamp)
        assertEquals("2026-09-18", merged.day)
        // ...but keeps resolution state.
        assertEquals("food-1", merged.resolvedFoodId)
    }

    @Test fun `unresolved rows take the fresh payload unchanged`() {
        val existing = freshSupp("s1").copy(resolveAttempts = 2)
        val merged = MealRepository.mergePreservingResolved(freshSupp("s1"), existing)
        assertEquals("[]", merged.nutrientContributions)
        assertEquals(null, merged.resolvedFoodId)
    }
}
