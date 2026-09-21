package com.example.hoot.domain.nutrition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sanity tests for parsing + normalization (phase 3 pure-JVM core). */
class IngredientParserTest {

    @Test fun `parses the quick-add example string`() {
        val parsed = IngredientParser.parse("2 eggs, 1 cup rice, spinach")
        assertEquals(3, parsed.size)
        assertEquals("egg", parsed[0].foodKey)
        assertEquals(2.0, parsed[0].quantity!!, 1e-9)
        assertEquals("piece", parsed[0].unit)
        assertEquals("rice", parsed[1].foodKey)
        assertEquals(1.0, parsed[1].quantity!!, 1e-9)
        assertEquals("cup", parsed[1].unit)
        assertEquals("spinach", parsed[2].foodKey)
        assertNull(parsed[2].quantity)
    }

    @Test fun `handles attached gram units and decimals`() {
        val parsed = IngredientParser.parse("150g lentils")
        assertEquals("lentil", parsed.single().foodKey)
        assertEquals(150.0, parsed.single().quantity!!, 1e-9)
        assertEquals("g", parsed.single().unit)
    }

    @Test fun `splits on newlines pluses and semicolons`() {
        val parsed = IngredientParser.parse("1 apple\n150 g yogurt + handful almonds; olive oil")
        assertEquals(4, parsed.size)
        assertEquals(listOf("apple", "yogurt", "almond", "olive oil"), parsed.map { it.foodKey })
    }

    @Test fun `keeps parenthesized notes out of the food key`() {
        val parsed = IngredientParser.parse("2 slices bread (toasted), 1 tsp butter (salted)")
        assertEquals("bread", parsed[0].foodKey)
        assertEquals("butter", parsed[1].foodKey)
    }

    @Test fun `grams estimate falls back for count units with per-item hint`() {
        val grams = IngredientParser.gramsEstimate(
            quantity = 2.0, unit = "slice", perItemGrams = 30.0, portionDefaultGrams = null
        )
        assertEquals(60.0, grams!!, 1e-9)
    }

    @Test fun `unspecified quantity uses portion default`() {
        val grams = IngredientParser.gramsEstimate(
            quantity = null, unit = null, perItemGrams = 50.0, portionDefaultGrams = 80.0
        )
        assertEquals(80.0, grams!!, 1e-9)
    }
}

class FoodNormalizerTest {

    @Test fun `folds plurals`() {
        assertEquals("tomato", FoodNormalizer.normalize("tomatoes"))
        assertEquals("egg", FoodNormalizer.normalize("eggs"))
        assertEquals("berry", FoodNormalizer.normalize("berries"))
    }

    @Test fun `strips descriptors and quantities from the front`() {
        assertEquals("spinach", FoodNormalizer.normalize("2 cups fresh spinach"))
        assertEquals("chicken", FoodNormalizer.normalize("grilled chicken"))
    }

    @Test fun `synonym folding`() {
        assertEquals("eggplant", FoodNormalizer.normalize("aubergine"))
        assertEquals("chickpea", FoodNormalizer.normalize("garbanzo beans"))
    }

    @Test fun `idempotent on already-normal keys`() {
        for (key in listOf("rice", "salmon", "olive oil", "sweet potato")) {
            assertEquals(key, FoodNormalizer.normalize(key))
        }
    }
}

class SupplementLabelParserTest {

    @Test fun `name dose unit pattern`() {
        val p = SupplementLabelParser.parse("Magnesium 400 mg")!!
        assertEquals("magnesium", p.normalizedName)
        assertEquals(400.0, p.doseAmount!!, 1e-9)
        assertEquals("mg", p.doseUnit)
    }

    @Test fun `label colon pattern`() {
        val p = SupplementLabelParser.parse("Vitamin D: 2000 IU")!!
        assertEquals("vitamin d", p.normalizedName)
        assertEquals(2000.0, p.doseAmount!!, 1e-9)
        assertEquals("iu", p.doseUnit)
    }

    @Test fun `thousands separators and mcg`() {
        val p = SupplementLabelParser.parse("B12 2,500 mcg")!!
        assertEquals(2500.0, p.doseAmount!!, 1e-9)
        assertEquals("mcg", p.doseUnit)
    }

    @Test fun `plain name without dose still parses`() {
        val p = SupplementLabelParser.parse("Multivitamin")
        assertNotNull(p)
        assertNull(p!!.doseAmount)
        assertEquals("multivitamin", p.normalizedName)
    }

    @Test fun `hyphenated names keep their name intact`() {
        // The dash must not be read as a "Name - dose" separator.
        assertEquals("Vitamin B-12", SupplementLabelParser.parse("Vitamin B-12")!!.name)
        assertEquals("Omega-3", SupplementLabelParser.parse("Omega-3")!!.name)
    }

    @Test fun `dash dose label still splits before a number`() {
        val p = SupplementLabelParser.parse("Vitamin D - 2000 IU")!!
        assertEquals("vitamin d", p.normalizedName)
        assertEquals(2000.0, p.doseAmount!!, 1e-9)
        assertEquals("iu", p.doseUnit)
    }
}
