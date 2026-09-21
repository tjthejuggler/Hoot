package com.example.hoot.domain.nutrition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seed LUT integrity: every primary key must be FoodNormalizer-stable (the
 * resolver looks seeds up BY the normalized key — an unstable key would
 * silently orphan the row), aliases must point at existing primaries, every
 * nutrient id must be a canonical seeded id, and spot-check values must be
 * physically plausible.
 */
class SeedFoodLibraryTest {

    @Test
    fun `every primary key is a fixed point of FoodNormalizer normalize`() {
        val unstable = SeedFoodLibrary.primaryKeys.filter { FoodNormalizer.normalize(it) != it }
        assertTrue("Unstable seed keys (normalize changes them): $unstable", unstable.isEmpty())
    }

    @Test
    fun `aliases resolve to foods`() {
        for (alias in setOf("chicken", "ground beef", "scrambled egg", "cheese", "spaghetti")) {
            assertNotNull("Alias '$alias' missing from seed map", SeedFoodLibrary.foods[alias])
        }
    }

    @Test
    fun `every nutrient id is a known canonical id`() {
        // Seed ids are exact canonical ids → canonicalId(id) must round-trip.
        val unknown = SeedFoodLibrary.foods.values
            .flatMap { it.per100.keys }
            .filter { NutrientKeys.canonicalId(it) != it }
        assertTrue("Seed rows carry non-canonical nutrient ids: $unknown", unknown.isEmpty())
    }

    @Test
    fun `every panel has calories and plausible macros`() {
        for (food in SeedFoodLibrary.foods.values) {
            val kcal = food.per100["calories"]
            assertNotNull("${food.key}: missing calories", kcal)
            assertTrue("${food.key}: kcal out of range $kcal", kcal!! in 0.0..900.0)
            val protein = food.per100["protein"] ?: 0.0
            val fat = food.per100["total_fat"] ?: 0.0
            val carbs = food.per100["carbohydrates"] ?: 0.0
            val macroKcal = protein * 4 + fat * 9 + carbs * 4
            // 25 % slack: fiber rounding, alcohol-free assumption, USDA rounding.
            assertTrue(
                "${food.key}: macros ($protein p / $fat f / $carbs c) imply $macroKcal kcal vs stated $kcal",
                macroKcal <= kcal * 1.25 + 15
            )
        }
    }

    @Test
    fun `typical servings are plausible`() {
        for (food in SeedFoodLibrary.foods.values) {
            assertTrue("${food.key}: serving ${food.typicalServingGrams}", food.typicalServingGrams in 3.0..500.0)
        }
    }

    @Test
    fun `lookup covers common logged foods`() {
        for (key in setOf("tomato", "spinach", "salmon", "egg", "oat", "tofu", "almond")) {
            assertNotNull(SeedFoodLibrary.lookup(key))
        }
        assertEquals(null, SeedFoodLibrary.lookup("dragon fruit"))
    }

    @Test
    fun `spot check spinach values match USDA SR`() {
        val spinach = SeedFoodLibrary.lookup("spinach")!!
        assertEquals(23.0, spinach.per100["calories"]!!, 0.01)
        assertEquals(2.7, spinach.per100["iron"]!!, 0.01)
        assertEquals(483.0, spinach.per100["vitamin_k"]!!, 0.01)
        assertEquals(79.0, spinach.per100["magnesium"]!!, 0.01)
    }
}
