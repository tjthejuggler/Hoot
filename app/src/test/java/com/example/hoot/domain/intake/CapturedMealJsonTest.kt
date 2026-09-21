package com.example.hoot.domain.intake

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the Tail-compatible capture JSON contract: the parser must
 * produce the same structured meal from the same LLM replies Tail's pipeline
 * elicits (text/voice prompt shape and the vision food_data wrapper).
 */
class CapturedMealJsonTest {

    @Test fun `parses bare text-pipeline reply`() {
        val reply = """
            {"title": "Lentil bowl", "summary": "Lentils with spinach",
             "is_vegan_verified": true, "estimated_calories": 620,
             "macronutrients": {"protein_grams": 28.4, "carbs_grams": 71.0, "fat_grams": 22.6},
             "ingredients_detected": ["150 g cooked lentils", "spinach", "1 tbsp tahini"],
             "health_notes": "High iron", "macro_ratings": {"protein": 3, "carbs": 2, "fat": 2}}
        """.trimIndent()
        val meal = CapturedMealJson.parse(reply)!!
        assertEquals("Lentil bowl", meal.title)
        assertEquals(620, meal.calories)
        assertEquals(28.4, meal.proteinGrams, 1e-9)
        assertEquals(71.0, meal.carbsGrams, 1e-9)
        assertEquals(22.6, meal.fatGrams, 1e-9)
        assertEquals(listOf("150 g cooked lentils", "spinach", "1 tbsp tahini"), meal.ingredientsDetected)
        assertTrue(meal.isVeganVerified)
        assertEquals("High iron", meal.healthNotes)
        assertEquals(3, meal.macroRatings?.protein)
        assertFalse(meal.isEmpty)
    }

    @Test fun `parses vision food_data wrapper`() {
        val inner = JSONObject().apply {
            put("title", "Pasta plate")
            put("estimated_calories", 950)
            put("macronutrients", JSONObject().apply {
                put("protein_grams", 30); put("carbs_grams", 120); put("fat_grams", 25)
            })
            put("ingredients_detected", JSONArray().put("pasta").put("tomato sauce"))
        }
        val wrapped = JSONObject().apply {
            put("classification", "FOOD_MEAL")
            put("food_data", inner)
            put("processing_notes", "Description: a plate of pasta")
        }
        val meal = CapturedMealJson.parse(wrapped.toString())!!
        assertEquals("Pasta plate", meal.title)
        assertEquals(950, meal.calories)
        assertEquals(listOf("pasta", "tomato sauce"), meal.ingredientsDetected)
    }

    @Test fun `tolerates markdown fences and preamble`() {
        val reply = "Sure!\n```json\n{\"title\": \"Eggs\", \"estimated_calories\": 140," +
            "\"ingredients_detected\": [\"2 eggs\"]}\n```\nEnjoy."
        val meal = CapturedMealJson.parse(reply)!!
        assertEquals("Eggs", meal.title)
        assertEquals(140, meal.calories)
    }

    @Test fun `numeric strings are coerced`() {
        val reply = """{"title": "X", "estimated_calories": "950 kcal",
            "macronutrients": {"protein_grams": "28.4 g", "carbs_grams": "71", "fat_grams": "22.6"}}"""
        val meal = CapturedMealJson.parse(reply)!!
        assertEquals(950, meal.calories)
        assertEquals(28.4, meal.proteinGrams, 1e-9)
        assertEquals(22.6, meal.fatGrams, 1e-9)
    }

    @Test fun `null and blank strings become nulls not literal null`() {
        val reply = """{"title": "Soup", "health_notes": null, "summary": null,
            "estimated_calories": 0, "ingredients_detected": []}"""
        val meal = CapturedMealJson.parse(reply)!!
        assertNull(meal.healthNotes)
        assertNull(meal.summary)
    }

    @Test fun `garbage input returns null not a crash`() {
        assertNull(CapturedMealJson.parse(""))
        assertNull(CapturedMealJson.parse("I could not analyze that, sorry!"))
        assertNull(CapturedMealJson.parse("{\"classification\": \"UNCERTAIN_OTHER\"}"))
    }

    @Test fun `balanced extraction skips leading prose`() {
        val text = "prefix {\"a\": {\"b\": 1}} suffix"
        assertEquals("{\"a\": {\"b\": 1}}", CapturedMealJson.balancedFrom(text, text.indexOf('{')))
    }

    @Test fun `ratings unset stays null`() {
        val reply = """{"title": "Y", "macro_ratings": {"protein": 0, "carbs": 0, "fat": 0}}"""
        val meal = CapturedMealJson.parse(reply)!!
        assertNull(meal.macroRatings)
    }
}
