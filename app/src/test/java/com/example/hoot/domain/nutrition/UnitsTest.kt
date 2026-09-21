package com.example.hoot.domain.nutrition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sanity tests for the unit/quantity model (docs/NUTRIENTS.md §6). */
class UnitsTest {

    @Test fun `mass units convert to grams`() {
        assertEquals(150.0, Units.toGrams(150.0, "g")!!, 1e-9)
        assertEquals(1500.0, Units.toGrams(1.5, "kg")!!, 1e-9)
        assertEquals(28.3495, Units.toGrams(1.0, "oz")!!, 1e-9)
    }

    @Test fun `volume units use water density`() {
        assertEquals(240.0, Units.toGrams(1.0, "cup")!!, 1e-9)
        assertEquals(15.0, Units.toGrams(1.0, "tbsp")!!, 1e-9)
        assertEquals(5.0, Units.toGrams(1.0, "tsp")!!, 1e-9)
        assertEquals(200.0, Units.toGrams(200.0, "ml")!!, 1e-9)
    }

    @Test fun `count units need a per-item hint`() {
        assertNull(Units.toGrams(2.0, "piece"))
        assertTrue(Units.isCountUnit("piece"))
        assertTrue(Units.isCountUnit("slice"))
        assertFalse(Units.isCountUnit("g"))
    }

    @Test fun `aliases fold to canonical keys`() {
        assertEquals("g", Units.normalizeUnit("Grams"))
        assertEquals("tbsp", Units.normalizeUnit("Tablespoon"))
        assertEquals("piece", Units.normalizeUnit("pcs"))
        assertNull(Units.normalizeUnit("blorbs"))
    }

    @Test fun `vitamin D IU to mcg`() {
        assertEquals(50.0, Units.canonicalNutrientAmount("vitamin_d", 2000.0, "IU", "mcg")!!, 1e-9)
    }

    @Test fun `vitamin A IU to mcg RAE (retinol rule)`() {
        assertEquals(300.0, Units.canonicalNutrientAmount("vitamin_a", 1000.0, "iu", "mcg")!!, 1e-9)
    }

    @Test fun `mass ladder converts mg to g and mcg to mg`() {
        assertEquals(0.4, Units.canonicalNutrientAmount("fiber", 400.0, "mg", "g")!!, 1e-9)
        assertEquals(2.0, Units.canonicalNutrientAmount("vitamin_b12", 2000.0, "mcg", "mg")!!, 1e-9)
    }

    @Test fun `NE and DFE are identity in canonical units`() {
        assertEquals(16.0, Units.canonicalNutrientAmount("vitamin_b3", 16.0, "NE", "mg")!!, 1e-9)
        assertEquals(400.0, Units.canonicalNutrientAmount("vitamin_b9", 400.0, "DFE", "mcg")!!, 1e-9)
    }

    @Test fun `quantity parsing handles decimals vulgar fractions and words`() {
        assertEquals(1.5, Units.parseQuantity("1.5")!!, 1e-9)
        assertEquals(0.5, Units.parseQuantity("½")!!, 1e-9)
        assertEquals(0.5, Units.parseQuantity("1/2")!!, 1e-9)
        assertEquals(2.0, Units.parseQuantity("two")!!, 1e-9)
        assertNull(Units.parseQuantity("lots"))
    }
}
