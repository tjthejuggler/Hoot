package com.example.hoot.domain.nutrition

import com.example.hoot.data.local.entity.MealEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the structured-macro fill (bug 2026-09-21): Tail v2 meal rows / v6
 * captures carry authoritative kcal/protein/carbs/fat that the aggregator
 * previously IGNORED — Tail-synced meals with no resolvable ingredients
 * credited 0 g protein/carbs. [IntakeAggregator.structuredMacroRows] must
 * credit ONLY the macros the meal's ingredient rows left uncovered.
 */
class IntakeAggregatorStructuredMacroTest {

    private val units = mapOf(
        "calories" to "kcal", "protein" to "g",
        "carbohydrates" to "g", "total_fat" to "g"
    )

    private fun meal(
        protein: Double = 0.0, carbs: Double = 0.0, fat: Double = 0.0, kcal: Int = 0
    ) = MealEntity(
        id = "tail:m1", tailHabitName = "Food", timestamp = 0L, day = "2026-09-21",
        title = null, rawText = "", source = "tail",
        calories = kcal, proteinGrams = protein, carbsGrams = carbs, fatGrams = fat
    )

    @Test
    fun `zero macros contribute nothing`() {
        val rows = IntakeAggregator.structuredMacroRows(meal(), "2026-09-21", emptySet(), units)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `uncovered macros are credited with deterministic ids`() {
        val rows = IntakeAggregator.structuredMacroRows(
            meal(protein = 28.0, carbs = 105.0), "2026-09-21", emptySet(), units
        )
        assertEquals(listOf("protein", "carbohydrates"), rows.map { it.nutrientId })
        assertEquals(listOf("macro:tail:m1:protein", "macro:tail:m1:carbohydrates"), rows.map { it.id })
        assertEquals(listOf(28.0, 105.0), rows.map { it.amount })
        assertTrue(rows.all { it.sourceMealId == "tail:m1" && it.day == "2026-09-21" })
    }

    @Test
    fun `nutrients already covered by ingredients are NOT double counted`() {
        val rows = IntakeAggregator.structuredMacroRows(
            meal(protein = 28.0, carbs = 105.0),
            "2026-09-21",
            coveredByIngredients = setOf("protein"),
            canonicalUnits = units
        )
        assertEquals(listOf("carbohydrates"), rows.map { it.nutrientId })
    }

    @Test
    fun `unknown nutrient ids are skipped instead of crashing`() {
        // Canonical units missing a key (e.g. fresh DB before seed) must not throw.
        val rows = IntakeAggregator.structuredMacroRows(meal(protein = 10.0), "d", emptySet(), emptyMap())
        assertTrue(rows.isEmpty())
    }
}
