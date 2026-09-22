package com.example.hoot.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the smart-picks naming fixes (feedback 2026-09-21): LLM/Tail
 * segmentation artifacts ("Plus Seaweed Sheets.", "… (700 Kcal)", "Plus
 * Kombucha. Not Vegan (Nutella Contains Milk).") must be cleaned, and
 * sea-vegetable variants ("Nori"/"Seaweed"/"Seaweed Sheets") must collapse
 * into ONE suggestion per variant group.
 */
class SmartPicksNamingTest {

    // ---- cleanFoodName -------------------------------------------------------

    @Test
    fun `strips parenthetical notes`() {
        assertEquals(
            "Vegan Burger And Seaweed",
            SmartFoodMatcher.cleanFoodName("Vegan Burger And Seaweed (700 Kcal)")
        )
    }

    @Test
    fun `strips plus prefix and trailing sentence`() {
        assertEquals("Seaweed Sheets", SmartFoodMatcher.cleanFoodName("Plus Seaweed Sheets."))
        assertEquals(
            "Kombucha",
            SmartFoodMatcher.cleanFoodName("Plus Kombucha. Not Vegan (Nutella Contains Milk).")
        )
    }

    @Test
    fun `plain names pass through unchanged`() {
        assertEquals("Chicken breast", SmartFoodMatcher.cleanFoodName("Chicken breast"))
        assertEquals("Lentils", SmartFoodMatcher.cleanFoodName("Lentils"))
    }

    @Test
    fun `blank-after-clean falls back to trimmed raw`() {
        assertEquals("(x)", SmartFoodMatcher.cleanFoodName("(x)"))
    }

    // ---- variantKey ----------------------------------------------------------

    @Test
    fun `seaweed variants share one group key`() {
        val keys = listOf("Nori", "Seaweed", "Seaweed Sheets", "Wakame", "Kombu", "Kelp")
            .map { SmartFoodMatcher.variantKey(it) }
        assertEquals(1, keys.toSet().size)
        assertTrue(keys.first() != null)
    }

    @Test
    fun `unrelated foods have no variant key`() {
        assertNull(SmartFoodMatcher.variantKey("Chicken breast"))
        assertNull(SmartFoodMatcher.variantKey("Lentils"))
    }

    @Test
    fun `almond milk wordings collapse to one name signature`() {
        assertEquals(
            SmartFoodMatcher.nameSignature("Almond Milk"),
            SmartFoodMatcher.nameSignature("Fortified Almond Milk")
        )
        assertEquals(
            SmartFoodMatcher.nameSignature("Almond Milk"),
            SmartFoodMatcher.nameSignature("Plus Almond Milk.")
        )
        assertEquals("almond milk", SmartFoodMatcher.nameSignature("Unsweetened Almond Milk"))
    }

    @Test
    fun `meal-segmentation phrasing collapses to the food`() {
        // Exact device case: "Served With Sugar Free Almond Milk" is the
        // same recommendation as "Almond Milk".
        assertEquals(
            SmartFoodMatcher.nameSignature("Almond Milk"),
            SmartFoodMatcher.nameSignature("Served With Sugar Free Almond Milk")
        )
    }

    @Test
    fun `genuinely different foods keep distinct signatures`() {
        assertNotEquals(
            SmartFoodMatcher.nameSignature("Almond Milk"),
            SmartFoodMatcher.nameSignature("Cow Milk")
        )
        assertNotEquals(
            SmartFoodMatcher.nameSignature("Greek Yogurt"),
            SmartFoodMatcher.nameSignature("Cheddar Cheese")
        )
    }

    @Test
    fun `three wordings of almond milk yield ONE suggestion`() {
        // The exact device bug (feedback 2026-09-21): three DB rows for the
        // same food, worded differently, each with a slightly different
        // profile — only the best-scored one may surface.
        val gap = SmartGap("calcium", "Calcium", 1, "mg", 1000.0, 0.0)
        val foods = listOf(
            "Almond Milk" to 250.0,
            "Fortified Almond Milk" to 480.0,   // best panel → survives
            "Plus Almond Milk." to 120.0
        ).map { (name, ca) ->
            SmartCandidateFood(
                foodId = "db:$name", displayName = name, category = "drink",
                servingGrams = 240.0, per100 = mapOf("calcium" to ca)
            )
        }
        val picks = SmartFoodMatcher.match(listOf(gap), emptyList(), foods)
        assertEquals(1, picks.size)
        assertEquals("Fortified Almond Milk", picks.single().displayName)
    }

    @Test
    fun `variant grouping keeps exactly one seaweed pick`() {
        // Three sea-vegetable candidates all covering the same iodine gap:
        // the diversity pass must keep only the best-scored member.
        val gap = SmartGap("iodine", "Iodine", 1, "mcg", 150.0, 0.0)
        val foods = listOf("Nori", "Seaweed", "Seaweed Sheets").map { name ->
            SmartCandidateFood(
                foodId = "seed:$name", displayName = name, category = "vegetable",
                servingGrams = 10.0, per100 = mapOf("iodine" to 5000.0)
            )
        }
        val picks = SmartFoodMatcher.match(listOf(gap), emptyList(), foods)
        assertEquals(1, picks.size)
        // Identical panels → identical scores → stable sort keeps input order,
        // so the first candidate survives the variant collapse.
        assertEquals("Nori", picks.single().displayName)
    }
}
