package com.example.hoot.domain.nutrition

import com.example.hoot.data.local.entity.MealEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the TAIL-IS-FACT precedence (feedback 2026-11-17): Hoot's daily
 * calories/macros must match Tail exactly. Structured macros on the meal row
 * (Tail v2 rows / v6 captures) are credited UNCONDITIONALLY — ingredient
 * estimates never override them — while ingredients keep enriching every
 * nutrient Tail doesn't assert (fiber, micronutrients, …). The former
 * fill-missing semantics made Hoot disagree with Tail whenever the resolver
 * produced different numbers.
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
        val rows = IntakeAggregator.structuredMacroRows(meal(), "2026-09-21", units)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `structured macros are credited with deterministic ids`() {
        val rows = IntakeAggregator.structuredMacroRows(
            meal(protein = 28.0, carbs = 105.0), "2026-09-21", units
        )
        assertEquals(listOf("protein", "carbohydrates"), rows.map { it.nutrientId })
        assertEquals(listOf("macro:tail:m1:protein", "macro:tail:m1:carbohydrates"), rows.map { it.id })
        assertEquals(listOf(28.0, 105.0), rows.map { it.amount })
        assertTrue(rows.all { it.sourceMealId == "tail:m1" && it.day == "2026-09-21" })
    }

    @Test
    fun `TAIL IS FACT - structured macros win over ingredient estimates`() {
        // The resolver credits 50 g protein from ingredients, but Tail's row
        // says 28 g. Hoot must display Tail's 28 g — never 50, never 78.
        val rows = IntakeAggregator.structuredMacroRows(
            meal(protein = 28.0, carbs = 105.0), "2026-09-21", units
        )
        assertEquals(28.0, rows.first { it.nutrientId == "protein" }.amount, 0.0)
    }

    @Test
    fun `structuredMacroNutrientIds lists only asserted macros`() {
        // The per-meal skip-set the ingredient loop consults: exactly the
        // macros with amount > 0, so ingredient enrichment never touches them
        // but still credits fiber/iron/micros Tail doesn't carry.
        assertEquals(
            setOf("calories", "protein", "carbohydrates"),
            IntakeAggregator.structuredMacroNutrientIds(meal(kcal = 620, protein = 28.0, carbs = 105.0))
        )
        assertEquals(
            setOf<String>(),
            IntakeAggregator.structuredMacroNutrientIds(meal())
        )
        // Partial rows (Tail can send kcal-only hollow analyses): only the
        // asserted macro is authoritative; protein stays ingredient-driven.
        assertEquals(
            setOf("calories"),
            IntakeAggregator.structuredMacroNutrientIds(meal(kcal = 450))
        )
    }

    @Test
    fun `partial Tail macros keep ingredient enrichment for the missing ones`() {
        // Tail says 450 kcal but no macros → calories is authoritative,
        // protein/carbs/fat remain covered by ingredient resolution.
        val authoritative = IntakeAggregator.structuredMacroNutrientIds(meal(kcal = 450))
        assertFalse("protein" in authoritative)
        assertFalse("carbohydrates" in authoritative)
        assertFalse("total_fat" in authoritative)
        val rows = IntakeAggregator.structuredMacroRows(meal(kcal = 450), "d", units)
        assertEquals(listOf("calories"), rows.map { it.nutrientId })
    }

    @Test
    fun `unknown nutrient ids are skipped instead of crashing`() {
        // Canonical units missing a key (e.g. fresh DB before seed) must not throw.
        val rows = IntakeAggregator.structuredMacroRows(meal(protein = 10.0), "d", emptyMap())
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `supplement panels never credit energy macros`() {
        // Bug 2026-11-17 ("11 g protein today" with nothing eaten): the
        // collagen/glutamine panel's protein key must be skipped exactly
        // like calories — pills contribute micronutrients only.
        val skip = IntakeAggregator.SUPPLEMENT_MACRO_SKIP
        assertTrue("calories" in skip)
        assertTrue("protein" in skip)
        assertTrue("carbohydrates" in skip)
        assertTrue("total_fat" in skip)
        // Micronutrients stay eligible.
        assertFalse("iron" in skip)
        assertFalse("vitamin_d" in skip)
    }
}
