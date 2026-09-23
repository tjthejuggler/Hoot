package com.example.hoot.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the school-style nutrient grading (feedback 2026-09-23):
 * direction-agnostic F–A letter grades, untracked days pull the grade down,
 * never-logged = F, perfect = A.
 */
class NutrientGradesTest {

    private fun gradeOf(
        coverages: List<Double>,
        windowDays: Int = 7,
        isExcess: Boolean = false,
        isScoreable: Boolean = true
    ) = NutrientGrades.grade(
        nutrientId = "n", name = "N", tier = 1, unit = "mg",
        isExcess = isExcess, coverages = coverages,
        windowDays = windowDays, isScoreable = isScoreable
    ).grade

    @Test
    fun `perfect coverage every day is A`() {
        assertEquals(Grade.A, gradeOf(List(7) { 1.0 }))
        assertEquals(Grade.A, gradeOf(List(7) { 1.2 }))   // over target is fine too
    }

    @Test
    fun `never logged is F - unknown must not look good`() {
        assertEquals(Grade.F, gradeOf(emptyList()))
    }

    @Test
    fun `consistently near-zero intake is F regardless of data gaps`() {
        // Iodine case: 2 logged days at ~0% + 5 untracked days.
        assertEquals(Grade.F, gradeOf(List(2) { 0.05 }, windowDays = 7))
    }

    @Test
    fun `every day at 50 percent is D`() {
        assertEquals(Grade.D, gradeOf(List(7) { 0.5 }))
    }

    @Test
    fun `every day at 75 percent is minor drift - B`() {
        assertEquals(Grade.B, gradeOf(List(7) { 0.75 }))
    }

    @Test
    fun `excess direction degrades the same way - too high is bad`() {
        // Limit-tracker under its cap on every day → A.
        assertEquals(Grade.A, gradeOf(List(7) { 1.0 }, isExcess = true))
        // Way over the cap on every day → F, same as a severe shortfall.
        assertEquals(Grade.F, gradeOf(List(7) { 0.0 }, isExcess = true))
    }

    @Test
    fun `untracked days pull the grade down`() {
        // Full-week 90% days = A territory; only 2 of 7 logged days cannot be A.
        val sparse = gradeOf(List(2) { 0.9 }, windowDays = 7)
        assertTrue("expected worse than A for sparse data, got $sparse", sparse.ordinal < Grade.A.ordinal)
    }

    @Test
    fun `grade worsens monotonically as coverage drops`() {
        val grades = listOf(1.0, 0.8, 0.6, 0.4, 0.1).map { gradeOf(List(7) { it.toDouble() }) }
        assertTrue("expected non-decreasing severity, got $grades",
            grades.zipWithNext().all { (a, b) -> a.ordinal <= b.ordinal })
    }

    @Test
    fun `untracked nutrient row counts untracked days`() {
        val row = NutrientGrades.grade(
            nutrientId = "iodine", name = "Iodine", tier = 1, unit = "mcg",
            isExcess = false, coverages = emptyList(),
            windowDays = 30, isScoreable = true
        )
        assertEquals(Grade.F, row.grade)
        assertEquals(0, row.daysWithData)
        assertEquals(30, row.untrackedDays)
    }

    @Test
    fun `row order sorts worst-first then tier then name`() {
        val f = NutrientGrades.grade("iodine", "Iodine", 1, "mcg", false, emptyList(), 7, true)
        val a = NutrientGrades.grade("vitamin_c", "Vitamin C", 1, "mg", false, List(7) { 1.0 }, 7, true)
        val a2 = NutrientGrades.grade("zinc", "Zinc", 2, "mg", false, List(7) { 1.1 }, 7, true)
        val sorted = listOf(a2, a, f).sortedWith(NutrientGrades.ROW_ORDER)
        assertEquals(listOf("Iodine", "Vitamin C", "Zinc"), sorted.map { it.name })
    }

    @Test
    fun `unscoreable rows are neutral C`() {
        assertEquals(Grade.C, gradeOf(emptyList(), isScoreable = false))
    }
}
