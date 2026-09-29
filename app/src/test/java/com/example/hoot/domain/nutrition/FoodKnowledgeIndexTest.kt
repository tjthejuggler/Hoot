package com.example.hoot.domain.nutrition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the food-knowledge base's "high in" derivation
 * (feedback 2026-09-27): pure JVM, pins the RDA-share threshold, the
 * density fallback, ordering and the JSON round-trips.
 */
class FoodKnowledgeIndexTest {

    // Canonical daily targets (RDA-ish) for the tests.
    private val targets = mapOf(
        "iron" to 18.0,          // mg
        "magnesium" to 400.0,    // mg
        "vitamin_c" to 90.0,     // mg
        "protein" to 50.0,       // g
        "potassium" to 3500.0    // mg
    )

    @Test
    fun `100g covering 15 percent of RDA qualifies as high`() {
        // 2.7 mg iron = 15% of 18 mg → exactly at threshold.
        val out = FoodKnowledgeIndex.highIn(mapOf("iron" to 2.7), targets)
        assertEquals(listOf("iron"), out)
    }

    @Test
    fun `trace amounts never qualify`() {
        val out = FoodKnowledgeIndex.highIn(mapOf("iron" to 0.5), targets)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `richest nutrient is listed first`() {
        val panel = mapOf(
            "iron" to 9.0,         // 50% RDA
            "magnesium" to 240.0,  // 60% RDA
            "vitamin_c" to 4.5     // 5% RDA → below floor
        )
        val out = FoodKnowledgeIndex.highIn(panel, targets)
        assertEquals(listOf("magnesium", "iron"), out)
    }

    @Test
    fun `zero and negative values are skipped`() {
        val out = FoodKnowledgeIndex.highIn(
            mapOf("iron" to 0.0, "magnesium" to -5.0),
            targets
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `unknown nutrient without target falls back to density ranking`() {
        // choline has no target here; typical density 30 mg/100g → 3x factor
        // qualifies (HIGH_DENSITY_FACTOR = 3).
        val out = FoodKnowledgeIndex.highIn(mapOf("choline" to 95.0), targets)
        assertEquals(listOf("choline"), out)
    }

    @Test
    fun `unknown nutrient below density factor is dropped`() {
        val out = FoodKnowledgeIndex.highIn(mapOf("choline" to 40.0), targets)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `completely unknown nutrient id without density reference is skipped`() {
        val out = FoodKnowledgeIndex.highIn(mapOf("mysterium" to 999.0), targets)
        assertTrue(out.isEmpty())
    }

    @Test
    fun `ordering is deterministic on ties`() {
        // Both 50% of RDA → id tiebreak: iron before magnesium alphabetically.
        val panel = mapOf("magnesium" to 200.0, "iron" to 9.0)
        assertEquals(
            listOf("iron", "magnesium"),
            FoodKnowledgeIndex.highIn(panel, targets)
        )
    }

    // ---- JSON round-trips -------------------------------------------------

    @Test
    fun `highIn json round trip`() {
        val ids = listOf("iron", "vitamin_b12")
        val json = FoodKnowledgeIndex.toJson(ids)
        assertEquals(ids, FoodKnowledgeIndex.fromJson(json))
    }

    @Test
    fun `fromJson tolerates garbage`() {
        assertTrue(FoodKnowledgeIndex.fromJson(null).isEmpty())
        assertTrue(FoodKnowledgeIndex.fromJson("").isEmpty())
        assertTrue(FoodKnowledgeIndex.fromJson("not json").isEmpty())
    }

    @Test
    fun `values json round trip`() {
        val panel = mapOf("iron" to 2.7, "protein" to 12.3)
        val json = FoodKnowledgeIndex.valuesToJson(panel)
        assertEquals(panel, FoodKnowledgeIndex.valuesFromJson(json))
    }

    @Test
    fun `valuesFromJson drops non-positive and NaN values`() {
        val out = FoodKnowledgeIndex.valuesFromJson(
            """{"iron":2.7,"protein":0,"magnesium":-1,"vitamin_c":null}"""
        )
        assertEquals(mapOf("iron" to 2.7), out)
    }

    @Test
    fun `valuesFromJson tolerates garbage`() {
        assertTrue(FoodKnowledgeIndex.valuesFromJson("garbage").isEmpty())
    }
}
