package com.example.hoot.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the vague-suggestion gates (feedback 2026-09-23: the Insights carousel
 * suggested "vegan platter" and "plant-based meal" — diet CONSTRAINTS, not
 * foods — and repeated the same cards day after day; round 2 added "Plus
 * Seaweed Sheets" / "Vegan Brunch Spread" artifacts).
 *
 * Layer 1: [SmartFoodMatcher.isPlausibleFoodName] rejects vessel nouns,
 * packaging heads and meal-occasion words.
 * Layer 2: [NutrientSourceQuality.isAcceptableSourceName] rejects names whose
 * every meaningful token is a diet/claim adjective with no food noun.
 * Identity: [SmartFoodMatcher.suggestionIdentity] collapses variant families
 * and qualifier wordings so the same food can only dedupe to ONE card.
 */
class VagueSuggestionGateTest {

    // ---- The exact user-reported offenders ----------------------------------

    @Test
    fun `vegan platter is rejected by the plausibility gate`() {
        assertFalse(SmartFoodMatcher.isPlausibleFoodName("Vegan Platter"))
        assertFalse(NutrientSourceQuality.isAcceptableSourceName("Vegan Platter"))
    }

    @Test
    fun `plant-based meal is rejected`() {
        assertFalse(SmartFoodMatcher.isPlausibleFoodName("Plant-based Meal"))
        assertFalse(NutrientSourceQuality.isAcceptableSourceName("Plant-based meal"))
    }

    // ---- Full adjective-stub family (either gate) ---------------------------

    @Test
    fun `diet-label stubs without a food noun are rejected`() {
        // NOTE: "protein" is NOT an adjective ("Whey protein" must pass),
        // so adjective-stub status is keyed on the diet/claim vocabulary.
        val stubs = listOf(
            "Vegan", "Vegan meal", "Vegan dish", "Vegan foods",
            "Vegetarian meal", "Plant-based", "Plant Based Foods",
            "Fortified vegan", "Healthy plant based", "Meat free",
            "Nutritious snack"
        )
        for (s in stubs) {
            assertFalse("should be rejected: $s", NutrientSourceQuality.isAcceptableSourceName(s))
        }
    }

    // ---- Food-qualified names must still pass -------------------------------

    @Test
    fun `food-qualified diet wordings survive the gates`() {
        val real = listOf(
            "Fortified plant milk",   // vegan-critical calcium/B12 source
            "Nutritional yeast",      // B12 (fortified)
            "Fortified almond milk",
            "Calcium-set tofu",
            "Ground flaxseed",
            "Chia seeds",
            "Canned sardines",
            "Whey protein"            // "protein" carries food identity
        )
        for (f in real) {
            assertTrue("should pass: $f", SmartFoodMatcher.isPlausibleFoodName(f))
            assertTrue("should pass quality gate: $f", NutrientSourceQuality.isAcceptableSourceName(f))
        }
    }

    // ---- Specificity demanded of the LLM prompt ------------------------------

    @Test
    fun `system prompt demands single purchasable foods`() {
        val p = RecommendationEngine.SYSTEM_PROMPT.lowercase()
        assertTrue(p.contains("purchasable"))
        assertTrue(p.contains("never output meal names"))
    }

    // ---- Round 2: segmentation artifacts ------------------------------------

    @Test
    fun `plus-prefix packaging artifacts are rejected`() {
        assertFalse(SmartFoodMatcher.isPlausibleFoodName("Plus Seaweed Sheets"))
        assertFalse(SmartFoodMatcher.isPlausibleFoodName("Seaweed Sheets"))
        assertFalse(SmartFoodMatcher.isPlausibleFoodName("Rice Paper Sheets"))
    }

    @Test
    fun `plus-prefix over a REAL food cleans to the food and passes`() {
        // "Plus Kombucha" → cleaned "Kombucha" is a real food; the pipeline
        // stores the cleaned name, which is the intended outcome.
        assertEquals(
            "Kombucha",
            SmartFoodMatcher.cleanFoodName("Plus Kombucha.")
        )
        assertTrue(SmartFoodMatcher.isPlausibleFoodName("Kombucha"))
    }

    @Test
    fun `meal-occasion titles are rejected`() {
        assertFalse(SmartFoodMatcher.isPlausibleFoodName("Vegan Brunch Spread"))
        assertFalse(SmartFoodMatcher.isPlausibleFoodName("Sunday Dinner"))
        assertFalse(SmartFoodMatcher.isPlausibleFoodName("Breakfast Plate"))
        assertFalse(SmartFoodMatcher.isPlausibleFoodName("Dessert Pudding"))
    }

    @Test
    fun `real foods with sheet-or-spread wordings are judged correctly`() {
        // "Spread"/"sheets" as head noun = packaging → rejected.
        assertFalse(SmartFoodMatcher.isPlausibleFoodName("Peanut Spread"))
        // Concrete foods (even close wordings) still pass.
        assertTrue(SmartFoodMatcher.isPlausibleFoodName("Peanut Butter"))
        assertTrue(SmartFoodMatcher.isPlausibleFoodName("Nori"))
        assertTrue(SmartFoodMatcher.isPlausibleFoodName("Kelp"))
    }

    // ---- Canonical suggestion identity ----------------------------------------

    @Test
    fun `seaweed variants share one canonical identity`() {
        val identities = listOf("Seaweed", "Plus Seaweed Sheets", "Nori", "Wakame")
            .map { SmartFoodMatcher.suggestionIdentity(it) }
        assertEquals(1, identities.toSet().size)
    }

    @Test
    fun `cleaned wordings share the canonical identity`() {
        assertEquals(
            SmartFoodMatcher.suggestionIdentity("Almond Milk"),
            SmartFoodMatcher.suggestionIdentity("Plus Almond Milk.")
        )
        assertNotEquals(
            SmartFoodMatcher.suggestionIdentity("Almond Milk"),
            SmartFoodMatcher.suggestionIdentity("Chicken breast")
        )
    }

    @Test
    fun `unrelated foods keep distinct identities`() {
        assertNotEquals(
            SmartFoodMatcher.suggestionIdentity("Lentils"),
            SmartFoodMatcher.suggestionIdentity("Chickpeas")
        )
    }

    // ---- Round 3: dish / composed-food nouns ----------------------------------

    @Test
    fun `bare sandwich is rejected`() {
        assertFalse(SmartFoodMatcher.isPlausibleFoodName("Sandwich"))
        assertFalse(SmartFoodMatcher.isPlausibleFoodName("Sandwiches"))
        assertFalse(NutrientSourceQuality.isAcceptableSourceName("Sandwich"))
    }

    @Test
    fun `dish nouns are rejected in any position and plural form`() {
        val dishes = listOf(
            "Chicken Sandwich", "Tofu Burger", "Veggie Wrap",
            "Bean Burrito", "Miso Soup", "Chickpea Curry", "Tuna Salad",
            "Protein Shake", "Fruit Smoothie", "Salmon Sushi",
            "Chicken Nuggets", "Turkey Tacos"
        )
        for (d in dishes) {
            assertFalse("should be rejected: $d", SmartFoodMatcher.isPlausibleFoodName(d))
        }
    }

    @Test
    fun `plain ingredients still pass the dish gate`() {
        val foods = listOf(
            "Chicken breast", "Tofu", "Whole grain bread", "Turkey",
            "Chickpeas", "Miso", "Salmon", "Greek Yogurt", "Spinach"
        )
        for (f in foods) {
            assertTrue("should pass: $f", SmartFoodMatcher.isPlausibleFoodName(f))
        }
    }
}
