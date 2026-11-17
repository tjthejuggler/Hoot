package com.example.hoot.domain.nutrition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tail option-description → canonical contributions parsing (iodine-drops
 * bug, 2026-11-17). The units map mirrors the seed's canonical units.
 */
class SupplementCompositionParserTest {

    private val units = mapOf(
        "iodine" to "mcg", "magnesium" to "mg", "calcium" to "mg", "zinc" to "mg",
        "copper" to "mcg", "vitamin_d" to "mcg", "vitamin_k" to "mcg",
        "vitamin_b1" to "mg", "vitamin_b2" to "mg", "vitamin_b3" to "mg",
        "vitamin_b5" to "mg", "vitamin_b6" to "mg", "vitamin_b7" to "mcg",
        "vitamin_b9" to "mcg", "vitamin_b12" to "mcg", "vitamin_c" to "mg",
        "omega3_epa_dha" to "mg", "selenium" to "mcg", "iron" to "mg",
        "boron" to "mg"
    )

    @Test fun `iodine drops description credits iodine in canonical mcg`() {
        val json = SupplementCompositionParser.contributionsJson("Iodine 150mcg", units)!!
        val parsed = org.json.JSONObject(json)
        assertEquals(150.0, parsed.getDouble("iodine"), 1e-9)
    }

    @Test fun `multi-nutrient mineral stack parses every item`() {
        // Tail catalog entry: "Calcium, Magnesium, Zinc, D3, K2, Copper, Boron"
        val description = "Calcium 500mg, Magnesium 200mg, Zinc 5mg, D3 20mcg, K2 200mcg, Copper 1mg, Boron 0.6mg"
        val c = SupplementCompositionParser.contributions(description, units)
        assertEquals(500.0, c["calcium"]!!, 1e-9)
        assertEquals(200.0, c["magnesium"]!!, 1e-9)
        assertEquals(5.0, c["zinc"]!!, 1e-9)
        assertEquals(20.0, c["vitamin_d"]!!, 1e-9)
        assertEquals(200.0, c["vitamin_k"]!!, 1e-9)
        // Copper canonical unit is mcg → 1 mg = 1000 mcg.
        assertEquals(1000.0, c["copper"]!!, 1e-9)
        // Boron is not a tracked nutrient → dropped, never an error.
        assertNull(c["boron"])
    }

    @Test fun `b complex description parses full b spectrum`() {
        val description = "B1 40mg, B2 15mg, B3 110mg, B5 100mg, B6 20mg, B7 400mcg, B9 400mcg, B12 500mcg"
        val c = SupplementCompositionParser.contributions(description, units)
        assertEquals(40.0, c["vitamin_b1"]!!, 1e-9)
        assertEquals(15.0, c["vitamin_b2"]!!, 1e-9)
        assertEquals(110.0, c["vitamin_b3"]!!, 1e-9)
        assertEquals(100.0, c["vitamin_b5"]!!, 1e-9)
        assertEquals(20.0, c["vitamin_b6"]!!, 1e-9)
        assertEquals(400.0, c["vitamin_b7"]!!, 1e-9)
        assertEquals(400.0, c["vitamin_b9"]!!, 1e-9)
        assertEquals(500.0, c["vitamin_b12"]!!, 1e-9)
    }

    @Test fun `dha and epa fold and sum into omega3`() {
        val c = SupplementCompositionParser.contributions("DHA 350mg, EPA 180mg", units)
        assertEquals(530.0, c["omega3_epa_dha"]!!, 1e-9)
    }

    @Test fun `g amounts convert down the mass ladder`() {
        val c = SupplementCompositionParser.contributions("Vitamin C 1g", units)
        assertEquals(1000.0, c["vitamin_c"]!!, 1e-9)
    }

    @Test fun `greek mu and ui units fold`() {
        val c = SupplementCompositionParser.contributions("D3 25µg", units)
        assertEquals(25.0, c["vitamin_d"]!!, 1e-9)
        val c2 = SupplementCompositionParser.contributions("Selenium 55ui", units)
        assertNull(c2["selenium"]) // no IU rule for selenium → dropped, not crashed
    }

    @Test fun `brand-suffixed names match by longest prefix`() {
        val c = SupplementCompositionParser.contributions("Magnesium Glycinate 150mg", units)
        assertEquals(150.0, c["magnesium"]!!, 1e-9)
    }

    @Test fun `blank and dose-free descriptions yield null json`() {
        assertNull(SupplementCompositionParser.contributionsJson("", units))
        assertNull(SupplementCompositionParser.contributionsJson("Ashwagandha", units))
        assertNull(SupplementCompositionParser.contributionsJson("Iodine drops", units))
    }

    @Test fun `comma decimals parse like dot decimals`() {
        val c = SupplementCompositionParser.contributions("Zinc 4,5mg", units)
        assertEquals(4.5, c["zinc"]!!, 1e-9)
    }

    @Test fun `newline separated items parse too`() {
        val c = SupplementCompositionParser.contributions("Iodine 150mcg\nSelenium 55mcg", units)
        assertEquals(150.0, c["iodine"]!!, 1e-9)
        assertEquals(55.0, c["selenium"]!!, 1e-9)
    }
}
