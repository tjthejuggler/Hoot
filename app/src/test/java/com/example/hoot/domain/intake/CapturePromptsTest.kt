package com.example.hoot.domain.intake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the Tail-parity capture prompts + vision content builder. */
class CapturePromptsTest {

    @Test fun `text prompt carries the tail json schema keys`() {
        val p = CapturePrompts.mealSystemPrompt(hasPhoto = false, dietaryRules = null)
        listOf(
            "\"estimated_calories\"", "\"macronutrients\"", "\"protein_grams\"",
            "\"ingredients_detected\"", "\"is_vegan_verified\"", "\"macro_ratings\""
        ).forEach { key -> assertTrue("missing $key", p.contains(key)) }
        assertTrue(p.contains("ONE SINGLE JSON object"))
        assertFalse(p.contains("photo of the meal is attached"))
    }

    @Test fun `photo prompt adds the see-plus-said instruction`() {
        val p = CapturePrompts.mealSystemPrompt(hasPhoto = true, dietaryRules = null)
        assertTrue(p.contains("photo of the meal is attached"))
        assertTrue(p.contains("takes priority"))
    }

    @Test fun `dietary rules are injected verbatim`() {
        val p = CapturePrompts.mealSystemPrompt(hasPhoto = false, dietaryRules = "Vegan since 2019.")
        assertTrue(p.contains("USER DIETARY RULES"))
        assertTrue(p.contains("Vegan since 2019."))
    }

    @Test fun `user text wraps the transcript like tail`() {
        assertEquals("Meal description: \"2 eggs and toast\"",
            CapturePrompts.mealUserText("2 eggs and toast"))
    }

    @Test fun `dietary rules line builds from profile fields`() {
        assertEquals(
            "Diet style: vegan; Allergies (strictly exclude): peanuts, shellfish",
            CapturePrompts.dietaryRulesLine("vegan", listOf("peanuts", "shellfish"))
        )
        assertNull(CapturePrompts.dietaryRulesLine("omnivore", emptySet()))
        assertNull(CapturePrompts.dietaryRulesLine(null, emptySet()))
    }

    @Test fun `vision content builder emits openai image_url shape`() {
        // Raw capture text in; the "Meal description: …" envelope is applied
        // exactly once by the builder (Tail-parity).
        val content = VisionContent.userContent("salad", "data:image/jpeg;base64,QUJD")
            as org.json.JSONArray
        assertEquals(2, content.length())
        val textPart = content.getJSONObject(0)
        val imagePart = content.getJSONObject(1)
        assertEquals("text", textPart.getString("type"))
        assertEquals("Meal description: \"salad\"", textPart.getString("text"))
        assertEquals("image_url", imagePart.getString("type"))
        assertEquals(
            "data:image/jpeg;base64,QUJD",
            imagePart.getJSONObject("image_url").getString("url")
        )
    }

    @Test fun `vision content without photo falls back to plain text`() {
        val content = VisionContent.userContent("soup", null)
        assertTrue(content is String)
        assertEquals("Meal description: \"soup\"", content)
    }
}
