package com.example.hoot.data.tail

import com.example.hoot.data.local.entity.MealEntity
import com.example.hoot.data.repository.MealRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hollow-meal refresh fix (2026-09-23): Tail creates meal rows as sparse
 * placeholders ("Meal", 0 kcal, no ingredients) and fills them IN-PLACE once
 * its async analysis lands — rewriting the creation timestamp to the canonical
 * (earlier) log instant. These tests pin the payload-change detector that lets
 * the sync's full pull merge the enriched row over the stored hollow one
 * (without it, the incremental `?after=` cursor never re-serves the enriched
 * row and Home shows no meal / wrong macros for the day).
 */
class TailMealPayloadChangeTest {

    private fun meal(
        id: String = "tail:abc",
        title: String? = "Meal",
        rawText: String = "Meal (0 kcal)",
        summary: String? = null,
        calories: Int = 0,
        protein: Double = 0.0,
        carbs: Double = 0.0,
        fat: Double = 0.0,
        isVegan: Boolean = false,
        healthNotes: String? = null,
        timestamp: Long = 1790168227616,
        day: String = "2026-09-23"
    ) = MealEntity(
        id = id,
        tailHabitName = "Eat",
        timestamp = timestamp,
        day = day,
        title = title,
        rawText = rawText,
        source = "tail",
        summary = summary,
        calories = calories,
        proteinGrams = protein,
        carbsGrams = carbs,
        fatGrams = fat,
        isVegan = isVegan,
        healthNotes = healthNotes
    )

    @Test fun `hollow placeholder differs from enriched row`() {
        val hollow = meal()
        val enriched = meal(
            title = "Beyond Burger with Quinoa, Veggies and Guacamole",
            rawText = "Beyond Burger with Quinoa, Veggies and Guacamole (850 kcal)\nquinoa\nbeyond burger patty\nvegan cheese\ncarrots\ncelery\nguacamole",
            summary = "A Beyond burger patty with vegan cheese served over quinoa.",
            calories = 850,
            protein = 38.0,
            carbs = 75.0,
            fat = 42.0,
            isVegan = true,
            timestamp = 1790168220000L
        )
        assertTrue(MealRepository.mealPayloadChanged(hollow, enriched))
    }

    @Test fun `timestamp-only rewrite is detected`() {
        // Analysis landed but every content column coincidentally matched —
        // the canonical-timestamp rewrite must still count as a change.
        val stored = meal(timestamp = 1790168227616)
        val fresh = meal(timestamp = 1790168220000)
        assertTrue(MealRepository.mealPayloadChanged(stored, fresh))
    }

    @Test fun `identical re-served row is NOT a change (idempotent upserts)`() {
        val stored = meal()
        assertFalse(MealRepository.mealPayloadChanged(stored, meal()))
    }

    @Test fun `macro-only update is detected`() {
        val stored = meal(title = "Lunch", calories = 500, protein = 10.0)
        val fresh = meal(title = "Lunch", calories = 500, protein = 12.5)
        assertTrue(MealRepository.mealPayloadChanged(stored, fresh))
    }

    @Test fun `day-key shift is detected`() {
        val stored = meal(day = "2026-09-22", timestamp = 1790080000000)
        val fresh = meal(day = "2026-09-23", timestamp = 1790168220000)
        assertTrue(MealRepository.mealPayloadChanged(stored, fresh))
    }

    @Test fun `healthNotes and vegan flag changes are detected`() {
        val stored = meal(title = "Salad", isVegan = false)
        assertTrue(MealRepository.mealPayloadChanged(stored, meal(title = "Salad", isVegan = true)))
        assertTrue(
            MealRepository.mealPayloadChanged(
                meal(title = "Salad"),
                meal(title = "Salad", healthNotes = "add olive oil for fats")
            )
        )
    }
}
