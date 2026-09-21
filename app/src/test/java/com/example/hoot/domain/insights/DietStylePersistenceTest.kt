package com.example.hoot.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the diet-leak ROOT CAUSE found on-device (2026-09): the Settings Save
 * button persisted diet_style="omnivore" while "Vegan" sat only in the
 * allergies set — no exclusion keyword matches "vegan", so every filter ran
 * as omnivore and meat/fish/eggs/dairy kept appearing in "Good sources".
 *
 * Two layers are pinned here:
 *  1. [DietTextFilter.merged] must PROMOTE a lifestyle chip ("vegan" /
 *     "vegetarian" / "pescatarian") found in the allergies to the effective
 *     style whenever neither store carries a restricted style (self-healing
 *     for already-corrupted persisted state).
 *  2. The Settings derivation ([com.example.hoot.ui.settings.derivedDietStyle])
 *     must map style chips to their persisted style values, and the save
 *     must strip lifestyle chips from the allergies set.
 */
class DietStylePersistenceTest {

    // ---- 1. merged() self-healing promotion --------------------------------

    @Test
    fun `merged promotes Vegan allergy chip when style is omnivore`() {
        // The exact corrupted state observed via run-as DataStore dump.
        val f = DietTextFilter.merged(
            datastoreStyle = "omnivore",
            datastoreAllergies = setOf("Vegan"),
            datastoreDislikes = emptySet(),
            roomStyle = "omnivore",
            roomAllergies = listOf("Vegan"),
            roomDislikes = emptyList()
        )
        assertEquals("vegan", f.dietStyle)
        // And the style must actually exclude animal foods now.
        assertFalse(f.allows("salmon"))
        assertFalse(f.allows("beef liver"))
        assertFalse(f.allows("eggs"))
        assertFalse(f.allows("greek yogurt"))
        assertTrue(f.allows("lentils"))
        assertTrue(f.allows("fortified plant milk"))
    }

    @Test
    fun `merged promotion fires from room side alone`() {
        val f = DietTextFilter.merged(
            datastoreStyle = null,
            datastoreAllergies = emptySet(),
            datastoreDislikes = emptySet(),
            roomStyle = null,
            roomAllergies = listOf("pescatarian"),
            roomDislikes = emptyList()
        )
        assertEquals("pescatarian", f.dietStyle)
        assertFalse(f.allows("beef"))
        assertTrue(f.allows("salmon"))
    }

    @Test
    fun `merged restricted style still wins over promotion`() {
        val f = DietTextFilter.merged(
            datastoreStyle = "vegan",
            datastoreAllergies = setOf("vegetarian"),
            datastoreDislikes = emptySet(),
            roomStyle = null,
            roomAllergies = emptyList(),
            roomDislikes = emptyList()
        )
        assertEquals("vegan", f.dietStyle)
    }

    @Test
    fun `merged keeps omnivore when no style signals exist`() {
        val f = DietTextFilter.merged(
            datastoreStyle = "omnivore",
            datastoreAllergies = setOf("Soy allergy"),
            datastoreDislikes = emptySet(),
            roomStyle = null,
            roomAllergies = emptyList(),
            roomDislikes = emptyList()
        )
        assertEquals("omnivore", f.dietStyle)
    }

    // ---- 2. Settings Save derivation ----------------------------------------

    @Test
    fun `derived style maps vegan chip`() {
        assertEquals(
            "vegan",
            com.example.hoot.ui.settings.derivedDietStyle(setOf("Vegan", "Soy allergy"))
        )
    }

    @Test
    fun `derived style most restrictive lifestyle wins`() {
        assertEquals(
            "vegan",
            com.example.hoot.ui.settings.derivedDietStyle(setOf("Pescatarian", "Vegan"))
        )
    }

    @Test
    fun `derived style secondary chip without lifestyle`() {
        assertEquals(
            "gluten-free",
            com.example.hoot.ui.settings.derivedDietStyle(setOf("Gluten-free", "Nut allergy"))
        )
    }

    @Test
    fun `derived style empty when only allergy chips`() {
        assertEquals(
            "",
            com.example.hoot.ui.settings.derivedDietStyle(setOf("Nut allergy", "Egg allergy"))
        )
    }

    @Test
    fun `save strips lifestyle chips from allergies`() {
        // Mirrors the Save onClick: selected - LIFESTYLE_CHIPS.
        val selected = setOf("Vegan", "Gluten-free", "Soy allergy")
        val savedAllergies = selected - com.example.hoot.ui.settings.LIFESTYLE_CHIPS
        assertEquals(setOf("Gluten-free", "Soy allergy"), savedAllergies)
    }
}
