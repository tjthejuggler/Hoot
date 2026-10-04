package com.example.hoot.ui.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the any-aspect library search (2026-10-04). */
class FoodLibrarySearchTest {

    private val json = """
        {"calories":165.0,"protein":31.0,"vitamin_b12":0.4,"magnesium":29.0}
    """.trimIndent()

    private fun matches(q: String) = foodLibraryMatches(
        query = q,
        displayName = "Chicken breast",
        normalizedName = "chicken breast",
        category = "poultry",
        valuesJson = json
    )

    @Test fun matchesFoodName() = assertTrue(matches("chicken"))

    @Test fun matchesCategory() = assertTrue(matches("poultry"))

    @Test fun matchesMacro() = assertTrue(matches("protein"))

    @Test fun matchesMineral() = assertTrue(matches("magnesium"))

    @Test fun matchesUnderscoredNutrientAsSpaced() = assertTrue(matches("vitamin b12"))

    @Test fun matchesNutrientShorthand() = assertTrue(matches("b12"))

    @Test fun matchesExactValue() = assertTrue(matches("31"))

    @Test fun noMatchForAbsentNutrient() = assertFalse(matches("fiber"))

    @Test fun blankQueryMatchesEverything() = assertTrue(matches(""))

    @Test fun nullProfileStillMatchesName() = assertTrue(
        foodLibraryMatches("chicken", "Chicken breast", "chicken breast", null, null)
    )
}
