package com.example.hoot.domain.nutrition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Abbreviation supplement labels ("D3 25µg", "K2 20µg", "C 1g") must resolve
 * to canonical nutrient ids WITHOUT an LLM round-trip — the vitamin-D-2%
 * bug (2026-11-16): FoodNormalizer strips the digits so "D3" → "d" missed
 * the known-supplement map, the LLM fallback burned all 3 attempts, and
 * every supplement row sat capped with empty contributions.
 */
class SupplementAbbreviationTest {

    @Test fun `D3 with micro-gram dose maps to vitamin d`() {
        val p = SupplementLabelParser.parse("D3 25µg")!!
        assertEquals("vitamin_d", p.nutrientId)
        assertEquals(25.0, p.doseAmount!!, 1e-9)
        assertEquals("mcg", p.doseUnit)
    }

    @Test fun `greek mu variant parses identically`() {
        val p = SupplementLabelParser.parse("D3 25μg")!!
        assertEquals("vitamin_d", p.nutrientId)
        assertEquals(25.0, p.doseAmount!!, 1e-9)
        assertEquals("mcg", p.doseUnit)
    }

    @Test fun `bare D3 without dose still identifies the nutrient`() {
        val p = SupplementLabelParser.parse("D3")!!
        assertEquals("vitamin_d", p.nutrientId)
        assertNull(p.doseAmount)
    }

    @Test fun `K2 maps to vitamin k`() {
        assertEquals("vitamin_k", SupplementLabelParser.parse("K2 20µg")!!.nutrientId)
    }

    @Test fun `C 1g maps to vitamin c`() {
        val p = SupplementLabelParser.parse("C 1g")!!
        assertEquals("vitamin_c", p.nutrientId)
        assertEquals(1.0, p.doseAmount!!, 1e-9)
        assertEquals("g", p.doseUnit)
    }

    @Test fun `B12 maps to vitamin b12`() {
        assertEquals("vitamin_b12", SupplementLabelParser.parse("B12")!!.nutrientId)
    }

    @Test fun `full word labels keep the word-based identity`() {
        val p = SupplementLabelParser.parse("Vitamin D 2000 IU")!!
        assertEquals("vitamin d", p.normalizedName)
        assertEquals(2000.0, p.doseAmount!!, 1e-9)
        assertEquals("iu", p.doseUnit)
    }

    @Test fun `food-like names never map to a nutrient id`() {
        assertNull(SupplementLabelParser.parse("Alkaline Drops")!!.nutrientId)
        assertNull(SupplementLabelParser.parse("Omega-3 Sunday Natural")!!.nutrientId)
        assertNull(SupplementLabelParser.parse("Creatine 3g")!!.nutrientId)
    }

    @Test fun `IU dose for D3 converts to canonical micrograms`() {
        val p = SupplementLabelParser.parse("D3 2000 IU")!!
        assertEquals("vitamin_d", p.nutrientId)
        val canonical = Units.canonicalNutrientAmount("vitamin_d", p.doseAmount!!, p.doseUnit, "mcg")
        assertEquals(50.0, canonical!!, 1e-9)   // 2000 IU / 40
    }

    @Test fun `abbreviation ids are exact-match only`() {
        // "d3" as a whole token matches; embedded text must not.
        assertEquals("vitamin_d", SupplementLabelParser.nutrientIdFor("D3"))
        assertEquals("vitamin_d", SupplementLabelParser.nutrientIdFor("d3 "))
        assertNull(SupplementLabelParser.nutrientIdFor("D3 1000"))
        assertNull(SupplementLabelParser.nutrientIdFor("Sandwich"))
    }

    @Test fun `group key for abbreviation labels stays stable`() {
        val k1 = FoodGrouper.supplementGroupKey("D3 25µg")
        val k2 = FoodGrouper.supplementGroupKey("D3 25μg")
        assertEquals(k1, k2)
        assertNotNull(k1.normalizedName)
    }
}
