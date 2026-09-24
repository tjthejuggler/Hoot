package com.example.hoot.domain.insights

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the food-name plausibility gate (quality rework 2026-09-22): LLM meal
 * segmentation produces junk DB rows ("Dark Beverage", compound meal titles)
 * that must NEVER surface as smart-pick recommendations.
 */
class FoodNamePlausibilityTest {

    @Test
    fun `vessel nouns are rejected even with qualifiers`() {
        assertFalse(SmartFoodMatcher.isPlausibleFoodName("Dark Beverage"))
        assertFalse(SmartFoodMatcher.isPlausibleFoodName("A Mixed Meal"))
        assertFalse(SmartFoodMatcher.isPlausibleFoodName("Protein Drink"))
        assertFalse(SmartFoodMatcher.isPlausibleFoodName("Snack"))
    }

    @Test
    fun `compound meal titles are rejected`() {
        assertFalse(
            SmartFoodMatcher.isPlausibleFoodName(
                "A Mixed Meal Of Cucumber With Hummus Plus Granola With Peanut Butter"
            )
        )
        assertFalse(SmartFoodMatcher.isPlausibleFoodName("Burger And Fries"))
        assertFalse(SmartFoodMatcher.isPlausibleFoodName("Chicken With Rice"))
    }

    @Test
    fun `real foods pass`() {
        assertTrue(SmartFoodMatcher.isPlausibleFoodName("Dark chocolate"))
        assertTrue(SmartFoodMatcher.isPlausibleFoodName("Almond Milk"))
        assertTrue(SmartFoodMatcher.isPlausibleFoodName("Nori"))
        assertTrue(SmartFoodMatcher.isPlausibleFoodName("Chicken breast"))
        assertTrue(SmartFoodMatcher.isPlausibleFoodName("Greek Yogurt"))
        assertTrue(SmartFoodMatcher.isPlausibleFoodName("Olive Oil"))
    }

    @Test
    fun `word cap keeps multi-ingredient titles out`() {
        // 5+ tokens without connectors — still a compound dish.
        assertFalse(SmartFoodMatcher.isPlausibleFoodName("Big Breakfast Plate Deluxe Supreme"))
        assertTrue(SmartFoodMatcher.isPlausibleFoodName("Peanut Butter"))
    }

    @Test
    fun `combination names are rejected (single-ingredient rule)`() {
        // Feedback 2026-09-24: "Ginger & Carrots" surfaced as a suggestion —
        // recommendations must be ONE ingredient, never a pair/list.
        assertFalse(SmartFoodMatcher.isPlausibleFoodName("Ginger & Carrots"))
        assertFalse(SmartFoodMatcher.isPlausibleFoodName("Rice + Beans"))
        assertFalse(SmartFoodMatcher.isPlausibleFoodName("Honey, Lemon"))
        assertFalse(SmartFoodMatcher.isPlausibleFoodName("Fish Oil; Flaxseed"))
        // Real single foods with compound NAMES are unaffected (no separators).
        assertTrue(SmartFoodMatcher.isPlausibleFoodName("Peanut Butter"))
        assertTrue(SmartFoodMatcher.isPlausibleFoodName("Olive Oil"))
    }
}
