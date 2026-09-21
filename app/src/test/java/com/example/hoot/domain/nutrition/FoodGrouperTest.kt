package com.example.hoot.domain.nutrition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-FOOD grouping math: 891 rows → distinct foodKeys; batching; distinct
 * day-coverage sets; remaining-count arithmetic (Today chip number).
 */
class FoodGrouperTest {

    private fun ing(id: String, raw: String, day: String? = "2026-09-19") =
        FoodGrouper.IngredientRow(id, raw, day)

    @Test fun `rows sharing a foodKey collapse into one group`() {
        val groups = FoodGrouper.groupIngredients(
            listOf(
                ing("1", "2 cups fresh Tomatoes"),
                ing("2", "150g tomatoes"),
                ing("3", "tomato")
            )
        )
        assertEquals(1, groups.size)
        assertEquals("tomato", groups[0].foodKey)
        assertEquals(listOf("1", "2", "3"), groups[0].ingredientIds)
    }

    @Test fun `distinct foodKeys produce distinct groups preserving order`() {
        val groups = FoodGrouper.groupIngredients(
            listOf(ing("1", "rice"), ing("2", "spinach"), ing("3", "EGGS"), ing("4", "RICE"))
        )
        // "RICE" folds onto "rice"; "EGGS" singularizes to its own "egg" group.
        assertEquals(listOf("rice", "spinach", "egg"), groups.map { it.foodKey })
        assertEquals(listOf("1", "4"), groups[0].ingredientIds)
        assertEquals(listOf("3"), groups[2].ingredientIds)
    }

    @Test fun `touched days are the distinct set across a group's rows`() {
        val groups = FoodGrouper.groupIngredients(
            listOf(
                ing("1", "rice", "2026-09-01"),
                ing("2", "rice", "2026-09-01"),
                ing("3", "rice", "2026-09-02"),
                ing("4", "rice", null)
            )
        )
        assertEquals(setOf("2026-09-01", "2026-09-02"), groups[0].touchedDays)
    }

    @Test fun `blank-key rows are dropped (unresolvable)`() {
        val groups = FoodGrouper.groupIngredients(
            listOf(ing("1", "rice"), ing("2", "123"), ing("3", "!!!"))
        )
        assertEquals(listOf("rice"), groups.map { it.foodKey })
        assertEquals(1, groups[0].ingredientIds.size)
    }

    @Test fun `row count vs group count is the 891-to-74 collapse`() {
        // Simulate 100 days × 3 meals × 3 ingredients with only 8 distinct foods.
        val foods = listOf("rice", "egg", "spinach", "chicken", "tomato", "oat", "banana", "salmon")
        val rows = (0 until 900).map { i ->
            ing("r$i", foods[i % foods.size], "2026-09-${(i % 28) + 1}")
        }
        val groups = FoodGrouper.groupIngredients(rows)
        assertEquals(foods.size, groups.size)
        assertEquals(rows.size, groups.sumOf { it.ingredientIds.size }) // no row lost
    }

    @Test fun `supplements group by normalized name AND dose`() {
        val groups = FoodGrouper.groupSupplements(
            listOf(
                FoodGrouper.SupplementRow("1", "Magnesium 400 mg", "Magnesium 400 mg", "2026-09-19"),
                FoodGrouper.SupplementRow("2", "magnesium 400mg", "", "2026-09-18"),
                FoodGrouper.SupplementRow("3", "Magnesium 200 mg", "", "2026-09-19"),
                FoodGrouper.SupplementRow("4", "Vitamin D3", "", "2026-09-19")
            )
        )
        // 400 mg ×2 share a group; 200 mg and Vitamin D3 are separate.
        assertEquals(3, groups.size)
        val big = groups.first { it.supplementIds.size == 2 }
        assertEquals(listOf("1", "2"), big.supplementIds)
        assertEquals(setOf("2026-09-19", "2026-09-18"), big.touchedDays)
    }

    @Test fun `batch partitions exactly with remainder`() {
        val items = (1..23).toList()
        val batches = FoodGrouper.batch(items, 10)
        assertEquals(listOf(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), listOf(11, 12, 13, 14, 15, 16, 17, 18, 19, 20), listOf(21, 22, 23)), batches)
        // 74 distinct foods at batch size 10 → 8 calls (the reported math).
        assertEquals(8, FoodGrouper.batch((1..74).toList(), 10).size)
        assertEquals(1, FoodGrouper.batch(listOf(1), 0).size) // size coerced ≥ 1
        assertEquals(0, FoodGrouper.batch(emptyList<Int>(), 10).size)
    }

    @Test fun `remaining count never goes negative`() {
        assertEquals(64, FoodGrouper.remainingCount(74, 10))
        assertEquals(0, FoodGrouper.remainingCount(74, 80))
        assertEquals(0, FoodGrouper.remainingCount(0, 0))
    }

    @Test fun `supplement group keys are stable and dose-discriminated`() {
        val a = FoodGrouper.supplementGroupKey("Magnesium 400 mg")
        val b = FoodGrouper.supplementGroupKey("magnesium 400mg")
        val c = FoodGrouper.supplementGroupKey("Magnesium 200 mg")
        assertEquals(a.groupKey, b.groupKey)
        assertTrue(a.groupKey != c.groupKey)
        assertEquals("magnesium", a.normalizedName)
    }
}
