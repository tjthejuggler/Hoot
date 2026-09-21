package com.example.hoot.domain.nutrition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Multi-food batch prompt/parse: full per-food schema keyed by foodKey,
 * partial-failure tolerance (bad panels simply drop out → per-food fallback),
 * supplement batch shape.
 */
class PanelBatchParseTest {

    private val knownIds = setOf("protein", "calories", "vitamin_c")

    @Test fun `parses wrapped panels reply keyed by foodKey`() {
        val reply = """
            {"panels": {
              "rice": {"food": "Rice", "values": {"protein": 2.6, "calories": 130}, "confidence": 0.9},
              "egg": {"food": "Egg", "values": {"protein": 13.0}, "confidence": 0.8}
            }}
        """.trimIndent()
        val out = NutritionPrompts.parsePanelBatch(reply, setOf("rice", "egg"), knownIds)
        assertEquals(2, out.size)
        assertEquals(130.0, out["rice"]!!.values["calories"]!!, 1e-9)
        assertEquals(13.0, out["egg"]!!.values["protein"]!!, 1e-9)
    }

    @Test fun `partial failures drop only the broken panels`() {
        val reply = """
            {"panels": {
              "rice": {"food": "Rice", "values": {"protein": 2.6}, "confidence": 0.9},
              "mystery": {"food": "??", "values": {"zap": 5}, "confidence": 0.9},
              "empty": {"food": "Nothing", "values": {}, "confidence": 0.9}
            }}
        """.trimIndent()
        val out = NutritionPrompts.parsePanelBatch(reply, setOf("rice", "mystery", "empty"), knownIds)
        assertEquals(setOf("rice"), out.keys)     // mystery: unknown ids; empty: no values
    }

    @Test fun `unknown food keys in the reply are ignored`() {
        val reply = """
            {"panels": {
              "rice": {"food": "Rice", "values": {"protein": 2.6}, "confidence": 0.9},
              "injected": {"food": "Hax", "values": {"protein": 1.0}, "confidence": 0.9}
            }}
        """.trimIndent()
        val out = NutritionPrompts.parsePanelBatch(reply, setOf("rice"), knownIds)
        assertEquals(setOf("rice"), out.keys)
    }

    @Test fun `garbage reply yields empty map so every food falls back`() {
        val out = NutritionPrompts.parsePanelBatch("not json at all {", setOf("rice"), knownIds)
        assertTrue(out.isEmpty())
    }

    @Test fun `array reply with key fields is tolerated`() {
        val reply = """
            [{"key": "rice", "food": "Rice", "values": {"protein": 2.6}, "confidence": 0.85}]
        """.trimIndent()
        val out = NutritionPrompts.parsePanelBatch(reply, setOf("rice"), knownIds)
        assertEquals(1, out.size)
        assertEquals(0.85, out["rice"]!!.confidence, 1e-9)
    }

    @Test fun `batch prompt requests one panel per requested key`() {
        val prompt = NutritionPrompts.foodPanelBatchUserPrompt(
            listOf("rice" to "Rice", "egg" to "Egg"),
            listOf(NutritionPrompts.NutrientRef("protein", "Protein", "g"))
        )
        assertTrue(prompt.contains("\"rice\""))
        assertTrue(prompt.contains("\"egg\""))
        assertTrue(prompt.contains("\"panels\""))
    }

    @Test fun `supplement batch prompt uses per-serving schema`() {
        val prompt = NutritionPrompts.supplementBatchUserPrompt(
            listOf("magnesium|400|mg" to "Magnesium"),
            listOf(NutritionPrompts.NutrientRef("magnesium", "Magnesium", "mg"))
        )
        assertTrue(prompt.contains("\"magnesium|400|mg\""))
        assertTrue(prompt.contains("PER"))
        assertTrue(prompt.contains("SERVING"))
    }
}
