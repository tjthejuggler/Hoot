package com.example.hoot.domain.nutrition

import com.example.hoot.data.local.entity.NutrientIntakeEntity
import com.example.hoot.data.local.entity.NutrientOverrideEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the manual-override application (feedback 2026-11-18): per-item edits
 * from the day-view breakdown sheet must REPLACE the computed ledger rows on
 * every re-aggregation — the motivating case was a bogus "3120 mcg iodine"
 * day the user could neither attribute nor correct. Pure JVM, mirrors
 * `IntakeAggregatorStructuredMacroTest`.
 */
class IntakeAggregatorOverrideTest {

    private val day = "2026-09-13"

    private fun mealRow(id: String, nutrientId: String, amount: Double) = NutrientIntakeEntity(
        id = id, nutrientId = nutrientId, day = day, amount = amount,
        sourceMealId = "m1", sourceSupplementId = null
    )

    private fun suppRow(id: String, nutrientId: String, amount: Double) = NutrientIntakeEntity(
        id = id, nutrientId = nutrientId, day = day, amount = amount,
        sourceMealId = null, sourceSupplementId = "s1"
    )

    private fun override(type: String, sourceId: String, nutrientId: String, amount: Double) =
        NutrientOverrideEntity(
            id = NutrientOverrideEntity.buildId(type, sourceId, nutrientId),
            sourceType = type, sourceId = sourceId, nutrientId = nutrientId,
            day = day, amount = amount, updatedAt = 0L
        )

    @Test
    fun `no overrides returns computed unchanged`() {
        val computed = listOf(
            mealRow("a", "iodine", 3120.0),
            mealRow("b", "iodine", 45.0), // second ingredient row, same nutrient
            mealRow("c", "protein", 20.0)
        )
        val out = IntakeAggregator.applyOverrides(computed, emptyList(), day)
        assertEquals(computed, out)
    }

    @Test
    fun `override replaces all computed rows of that item-nutrient pair`() {
        val computed = listOf(
            mealRow("a", "iodine", 3120.0), // ingredient 1 (bogus)
            mealRow("b", "iodine", 45.0),   // ingredient 2
            mealRow("c", "protein", 20.0)   // untouched nutrient
        )
        val out = IntakeAggregator.applyOverrides(
            computed, listOf(override("meal", "m1", "iodine", 150.0)), day
        )
        // protein row untouched + exactly one iodine row with the manual value
        assertEquals(2, out.size)
        val iodine = out.filter { it.nutrientId == "iodine" }
        assertEquals(1, iodine.size)
        assertEquals(150.0, iodine[0].amount, 0.0)
        assertEquals("m1", iodine[0].sourceMealId)
        assertTrue(iodine[0].id.startsWith("override:"))
        assertTrue(out.any { it.nutrientId == "protein" && it.amount == 20.0 })
    }

    @Test
    fun `zero override suppresses the nutrient for the item`() {
        val computed = listOf(mealRow("a", "iodine", 3120.0), mealRow("c", "protein", 20.0))
        val out = IntakeAggregator.applyOverrides(
            computed, listOf(override("meal", "m1", "iodine", 0.0)), day
        )
        assertEquals(listOf("protein"), out.map { it.nutrientId })
    }

    @Test
    fun `override can add a nutrient the item never had`() {
        val computed = listOf(mealRow("c", "protein", 20.0))
        val out = IntakeAggregator.applyOverrides(
            computed, listOf(override("meal", "m1", "selenium", 55.0)), day
        )
        val selenium = out.filter { it.nutrientId == "selenium" }
        assertEquals(1, selenium.size)
        assertEquals(55.0, selenium[0].amount, 0.0)
    }

    @Test
    fun `override is scoped to its own item`() {
        val computed = listOf(
            mealRow("a", "iodine", 3120.0),
            suppRow("x", "iodine", 150.0) // different source — must survive
        )
        val out = IntakeAggregator.applyOverrides(
            computed, listOf(override("meal", "m1", "iodine", 0.0)), day
        )
        assertEquals(1, out.size)
        assertEquals(150.0, out[0].amount, 0.0)
        assertEquals("s1", out[0].sourceSupplementId)
    }

    @Test
    fun `water rows are never touched`() {
        val water = NutrientIntakeEntity(
            id = "water:$day", nutrientId = "water", day = day, amount = 2.1,
            sourceMealId = null, sourceSupplementId = null
        )
        val out = IntakeAggregator.applyOverrides(
            listOf(water),
            listOf(
                override("meal", "ghost-meal", "water", 0.0),
                override("supplement", "ghost-supp", "water", 99.0)
            ),
            day
        )
        assertEquals(listOf(water), out)
    }

    @Test
    fun `supplement override keeps supplement attribution`() {
        val computed = listOf(suppRow("x", "magnesium", 80.0))
        val out = IntakeAggregator.applyOverrides(
            computed, listOf(override("supplement", "s1", "magnesium", 350.0)), day
        )
        val mg = out.single()
        assertEquals(350.0, mg.amount, 0.0)
        assertEquals("s1", mg.sourceSupplementId)
        assertEquals(null, mg.sourceMealId)
    }
}
