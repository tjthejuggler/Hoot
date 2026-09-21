package com.example.hoot.domain.nutrition

import com.example.hoot.data.remote.LlmClient
import com.example.hoot.data.remote.McpWebTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sanity tests for the strict-JSON helper, panel parsing, search parsing. */
class LlmJsonTest {

    @Test fun `chat url normalization matches Inuit semantics`() {
        assertEquals("https://api.x.com/v1/chat/completions",
            LlmClient.normalizeChatUrl("https://api.x.com"))
        assertEquals("https://api.x.com/v1/chat/completions",
            LlmClient.normalizeChatUrl("https://api.x.com/v1"))
        assertEquals("https://api.x.com/v4/chat/completions",
            LlmClient.normalizeChatUrl("https://api.x.com/v4/"))
        assertEquals("https://api.x.com/v1/chat/completions",
            LlmClient.normalizeChatUrl("https://api.x.com/v1/chat/completions"))
    }

    @Test fun `extracts first object from fenced prose`() {
        val reply = "Here you go:\n```json\n{\"a\": 1, \"b\": {\"c\": 2}}\n```\nDone."
        assertEquals("{\"a\": 1, \"b\": {\"c\": 2}}", LlmClient.extractJson(reply))
    }

    @Test fun `extracts arrays too`() {
        val reply = "prefix [{\"x\": 1}] suffix"
        assertEquals("[{\"x\": 1}]", LlmClient.extractJson(reply))
    }

    @Test fun `braces inside strings do not confuse the scanner`() {
        val reply = "{\"note\": \"use } carefully\", \"v\": 3}"
        assertTrue(LlmClient.isJson(LlmClient.extractJson(reply)))
    }

    @Test fun `panel parsing filters unknown nutrient ids`() {
        val json = """
            {"food": "Rice", "per_amount": 100, "per_unit": "g",
             "values": {"protein": 2.6, "calories": 130, "unobtainium": 9},
             "confidence": 0.8, "typical_serving_grams": 160,
             "image_search_term": "cooked white rice"}
        """.trimIndent()
        val panel = NutritionPrompts.parsePanel(json, setOf("protein", "calories"))!!
        assertEquals("Rice", panel.displayName)
        assertEquals(2, panel.values.size)
        assertEquals(0.8, panel.confidence, 1e-9)
        assertEquals(160.0, panel.typicalServingGrams!!, 1e-9)
        assertEquals("cooked white rice", panel.imageSearchTerm)
    }

    @Test fun `panel parsing rejects panels with no known values`() {
        val json = """{"food": "Mystery", "values": {"zap": 1}, "confidence": 0.9}"""
        assertFalse(NutritionPrompts.parsePanel(json, setOf("protein")) != null)
    }

    @Test fun `search result parsing handles json arrays`() {
        val text = """[{"title": "Rice", "url": "https://fdc.nal.usda.gov/rice", "snippet": "per 100g"}]"""
        val results = McpWebTools.parseSearchResults(text)
        assertEquals(1, results.size)
        assertEquals("https://fdc.nal.usda.gov/rice", results[0].url)
        assertEquals("Rice", results[0].title)
    }

    @Test fun `search result parsing falls back to url sniffing`() {
        val text = "- Rice, white, cooked — https://fdc.nal.usda.gov/fdc-app.html#/food-details/168878\n" +
            "Second line mentions https://ods.od.nih.gov/factsheets/Magnesium-Consumer/ for minerals."
        val results = McpWebTools.parseSearchResults(text)
        assertEquals(2, results.size)
        assertTrue(results[0].url.startsWith("https://fdc.nal.usda.gov"))
        assertTrue(results[1].url.startsWith("https://ods.od.nih.gov"))
    }

    @Test fun `mcp config parse skips stdio servers`() {
        val json = """
            {"mcpServers": {
              "web-search-prime": {"type": "streamable-http", "url": "https://api.z.ai/x",
                                   "headers": {"Authorization": "Bearer k"}},
              "local-thing": {"command": "node", "args": ["server.js"]}
            }}
        """.trimIndent()
        val parsed = com.example.hoot.data.remote.McpConfig.parse(json)
        assertEquals(1, parsed.servers.size)
        assertEquals("web-search-prime", parsed.servers[0].name)
        assertEquals(listOf("local-thing"), parsed.skipped)
    }
}
