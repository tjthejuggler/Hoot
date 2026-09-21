package com.example.hoot.domain.nutrition

import com.example.hoot.data.local.nutrientSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Completeness contract for the nutrient-id ↔ panel-key mapping
 * ([NutrientKeys]) — the single source of truth that closes the fiber/iodine
 * silent-drop bug class:
 *
 *  1. EVERY seeded nutrient id has an alias entry (no nutrient can go missing
 *     from prompts/panels again when a seed row is added).
 *  2. Every alias maps back to EXACTLY ONE canonical id (no ambiguity).
 *  3. The id itself is always an accepted alias.
 *  4. The historical failure shapes ("fibre", "dietary_fiber", "iodide", …)
 *     fold to the correct canonical ids.
 */
class NutrientKeysMappingTest {

    // ── Completeness invariants ───────────────────────────────────────────

    @Test fun `every seeded nutrient id has an alias entry`() {
        val seeded = nutrientSeed().map { it.id }.toSet()
        val mapped = NutrientKeys.ALIASES.keys
        assertEquals("seed ids missing from NutrientKeys.ALIASES", seeded, mapped)
    }

    @Test fun `every seeded nutrient id is its own alias`() {
        for (def in nutrientSeed()) {
            assertEquals(
                "id '${def.id}' must fold to itself",
                def.id, NutrientKeys.canonicalId(def.id)
            )
        }
    }

    @Test fun `aliases are unambiguous`() {
        val keyToIds = HashMap<String, MutableSet<String>>()
        for ((id, keys) in NutrientKeys.ALIASES) {
            for (k in keys) keyToIds.getOrPut(k) { mutableSetOf() }.add(id)
        }
        val ambiguous = keyToIds.filterValues { it.size > 1 }
        assertTrue("ambiguous aliases: $ambiguous", ambiguous.isEmpty())
    }

    @Test fun `seed ids and units are paired correctly for the bug trio`() {
        val defs = nutrientSeed().associateBy { it.id }
        assertEquals("g", defs["fiber"]?.unit)
        assertEquals("mcg", defs["iodine"]?.unit)
        assertEquals("L", defs["water"]?.unit)
    }

    // ── Historical failure shapes (the bugs this mapping fixes) ───────────

    @Test fun `fiber key variants fold to fiber`() {
        for (key in listOf("fiber", "fibre", "dietary_fiber", "dietary_fibre", "total_fiber")) {
            assertEquals("fiber", NutrientKeys.canonicalId(key))
        }
        assertEquals("fiber", NutrientKeys.canonicalId("dietaryFiber"))
        assertEquals("fiber", NutrientKeys.canonicalId("Dietary Fiber"))
    }

    @Test fun `iodide folds to iodine`() {
        assertEquals("iodine", NutrientKeys.canonicalId("iodide"))
        assertEquals("iodine", NutrientKeys.canonicalId("Iodine"))
    }

    @Test fun `common llm spellings fold without silent drops`() {
        assertEquals("total_fat", NutrientKeys.canonicalId("fat"))
        assertEquals("calories", NutrientKeys.canonicalId("kcal"))
        assertEquals("calories", NutrientKeys.canonicalId("energy"))
        assertEquals("carbohydrates", NutrientKeys.canonicalId("carbs"))
        assertEquals("vitamin_b12", NutrientKeys.canonicalId("cobalamin"))
        assertEquals("vitamin_b9", NutrientKeys.canonicalId("folate"))
        assertEquals("vitamin_d", NutrientKeys.canonicalId("vitaminD3"))
        assertEquals("omega3_epa_dha", NutrientKeys.canonicalId("omega_3"))
        assertEquals("sodium", NutrientKeys.canonicalId("Na"))
        assertEquals("lutein_zeaxanthin", NutrientKeys.canonicalId("lutein"))
    }

    @Test fun `unclaimed keys return null and derived ids stay excluded from panels`() {
        assertNull(NutrientKeys.canonicalId("unobtainium"))
        assertNull(NutrientKeys.canonicalId("sugars")) // total sugars ≠ added_sugar: intentionally unmapped
        for (derived in NutrientKeys.DERIVED_IDS) {
            assertNotNull("derived id must exist in seed", nutrientSeed().firstOrNull { it.id == derived })
        }
    }

    // ── parsePanel folds aliases at the boundary ───────────────────────────

    @Test fun `parsePanel folds fibre and iodide into canonical ids`() {
        val json = """
            {"food": "Kelp", "values": {"fibre": 1.3, "iodide": 16.0, "protein": 1.7},
             "confidence": 0.9}
        """.trimIndent()
        val panel = NutritionPrompts.parsePanel(
            json, setOf("fiber", "iodine", "protein")
        )!!
        assertEquals(setOf("fiber", "iodine", "protein"), panel.values.keys)
        assertEquals(1.3, panel.values["fiber"]!!, 1e-9)
        assertEquals(16.0, panel.values["iodine"]!!, 1e-9)
    }

    @Test fun `parsePanel still rejects panels with only unknown keys`() {
        val json = """{"food": "Mystery", "values": {"zap": 1}, "confidence": 0.9}"""
        assertNull(NutritionPrompts.parsePanel(json, setOf("protein")))
    }

    @Test fun `food panel prompt excludes derived nutrients`() {
        val prompt = NutritionPrompts.foodPanelUserPrompt(
            "Rice",
            listOf(
                NutritionPrompts.NutrientRef("protein", "Protein", "g"),
                NutritionPrompts.NutrientRef("electrolyte_ratio", "Electrolyte ratio", "ratio")
            )
        )
        assertTrue(prompt.contains("\"protein\"") || prompt.contains("protein"))
        assertTrue(!prompt.contains("electrolyte_ratio"))
    }
}
