package com.example.hoot.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the STILL-LEAKING diet bug (diet-fix hardening,
 * 2026-09). The generation-time filter ([InsightsEngine] + `dietFilter`) is
 * necessary but not sufficient: text produced BEFORE a diet change — or by
 * any producer that skipped the filter — reached the Insights "What stands
 * out" cards verbatim ("Good sources: Meat, fish, eggs, dairy, …"). These
 * tests pin the RENDER-BOUNDARY sanitizers ([DietAwareSources
 * .sanitizeForDisplay]) and the safety-biased DataStore↔Room merge
 * ([DietTextFilter.merged]).
 */
class DietLeakRenderBoundaryTest {

    private val vegan = DietProfile(dietStyle = "vegan")

    // ---- The exact user-visible failure ------------------------------------

    /**
     * The user's bug: a DEFICIENCY insight built WITHOUT a diet filter (the
     * pre-fix / omnivore-era producer shape) renders the seeded omnivore
     * sources. The render-boundary sanitize must strip every animal food
     * from the "Good sources" tail while keeping the sentence readable.
     */
    @Test
    fun `stale omnivore-era Good sources text is sanitized at the render boundary`() {
        val leaky = InsightsEngine.analyze(
            WindowData(
                from = "2026-09-01", to = "2026-09-07",
                intakeByDay = mapOf(
                    ("protein" to "2026-09-01") to 10.0,
                    ("protein" to "2026-09-02") to 10.0,
                    ("protein" to "2026-09-03") to 10.0
                ),
                definitions = mapOf(
                    "protein" to NutrientInsightDef(
                        id = "protein", name = "Protein", unit = "g", tier = 1,
                        rda = 56.0, ul = null,
                        foodSources = "Meat, fish, eggs, dairy, legumes, tofu, seitan"
                    )
                )
                // NOTE: no dietFilter — the omnivore-era producer shape.
            )
        ).first { it.kind == InsightKind.DEFICIENCY }

        // The leak reproduces: unfiltered text names animal foods.
        assertTrue(leaky.message.contains("Meat"))
        assertTrue(leaky.message.contains("fish"))
        assertTrue(leaky.message.contains("eggs"))
        assertTrue(leaky.message.contains("dairy"))

        // The fix: rendering goes through the boundary sanitizer.
        val safe = DietAwareSources.sanitizeForDisplay(leaky.message, vegan)
        for (animal in listOf("Meat", "meat", "fish", "eggs", "dairy")) {
            assertFalse("sanitized text must not contain '$animal'", safe.contains(animal))
        }
        // Plant sources from the same seed survive.
        assertTrue(safe.contains("legumes"))
        assertTrue(safe.contains("tofu"))
        assertTrue(safe.contains("seitan"))
    }

    /**
     * Fully-emptied tails disappear instead of rendering an empty
     * "Good sources:" (B12's seed is all-animal + a generic vegan pointer).
     */
    @Test
    fun `b12 tail emptied by vegan filter loses the whole tail`() {
        val stale =
            "Averaging 30% of your Vitamin B12 target across 5 days (4 low days)." +
                " Good sources: Meat, fish, eggs, dairy; fortified foods/supplements for vegans"
        val safe = DietAwareSources.sanitizeForDisplay(stale, vegan)
        assertFalse(safe.contains("Meat"))
        assertFalse(safe.contains("dairy"))
        // The prefix sentence survives; the empty tail does not linger.
        assertTrue(safe.startsWith("Averaging 30%"))
        assertFalse(safe.contains("Good sources"))
    }

    /** GAP messages are pure food lists — stale ones get filtered as lists. */
    @Test
    fun `stale GAP list message is filtered`() {
        val stale = "Fatty fish (salmon, mackerel, sardines), algae oil"
        val safe = DietAwareSources.sanitizeForDisplay(stale, vegan)
        assertFalse(safe.contains("salmon"))
        assertTrue(safe.contains("algae oil"))
    }

    /** A already-conforming message passes through unchanged (idempotent). */
    @Test
    fun `compliant text is untouched`() {
        val clean =
            "Averaging 55% of your Protein target across 6 days (4 low days)." +
                " Good sources: lentils, tofu, tempeh"
        assertEquals(clean, DietAwareSources.sanitizeForDisplay(clean, vegan))
    }

    // ---- InsightsEngine still filters at generation time -------------------

    /** Generation-time path (the fixed producer): no animal foods at all. */
    @Test
    fun `analyze with vegan dietFilter never emits animal sources`() {
        val insights = InsightsEngine.analyze(
            WindowData(
                from = "2026-09-01", to = "2026-09-07",
                intakeByDay = mapOf(
                    ("protein" to "2026-09-01") to 10.0,
                    ("protein" to "2026-09-02") to 10.0,
                    ("protein" to "2026-09-03") to 10.0
                ),
                definitions = mapOf(
                    "protein" to NutrientInsightDef(
                        id = "protein", name = "Protein", unit = "g", tier = 1,
                        rda = 56.0, ul = null,
                        foodSources = "Meat, fish, eggs, dairy, legumes, tofu, seitan"
                    )
                ),
                dietFilter = DietTextFilter(dietStyle = "vegan")
            )
        )
        val all = insights.joinToString(" | ") { it.message }
        for (animal in listOf("Meat", "fish", "eggs", "dairy")) {
            assertFalse("generated message must not contain '$animal'", all.contains(animal))
        }
        // Plant survivors from the seed (legumes/tofu/seitan survive vegan,
        // so NO curated-alternative substitution happens for protein).
        assertTrue(all.contains("legumes"))
        assertTrue(all.contains("tofu"))
    }

    /** Belt-and-braces: sanitize of an ALREADY filtered message is a no-op. */
    @Test
    fun `generation-time filter and render-boundary sanitizer compose`() {
        val insights = InsightsEngine.analyze(
            WindowData(
                from = "2026-09-01", to = "2026-09-07",
                intakeByDay = mapOf(
                    ("protein" to "2026-09-01") to 10.0,
                    ("protein" to "2026-09-02") to 10.0,
                    ("protein" to "2026-09-03") to 10.0
                ),
                definitions = mapOf(
                    "protein" to NutrientInsightDef(
                        id = "protein", name = "Protein", unit = "g", tier = 1,
                        rda = 56.0, ul = null,
                        foodSources = "Meat, fish, eggs, dairy, legumes, tofu, seitan"
                    )
                ),
                dietFilter = DietTextFilter(dietStyle = "vegan")
            )
        )
        for (insight in insights) {
            val reSanitized = DietAwareSources.sanitizeForDisplay(insight.message, vegan)
            assertEquals(insight.message, reSanitized)
        }
    }

    // ---- DataStore ↔ Room safety-biased merge ------------------------------

    /** The desync bug: Settings shows vegan but the Room mirror is missing. */
    @Test
    fun `missing room mirror does not downgrade vegan to omnivore`() {
        val filter = DietTextFilter.merged(
            datastoreStyle = "vegan", datastoreAllergies = emptySet(), datastoreDislikes = emptySet(),
            roomStyle = null, roomAllergies = emptyList(), roomDislikes = emptyList()
        )
        assertEquals("vegan", filter.dietStyle)
        assertFalse(filter.allows("beef"))
    }

    /** The inverse desync: a stale omnivore Room row cannot reopen the diet. */
    @Test
    fun `stale omnivore room row does not downgrade vegan`() {
        val filter = DietTextFilter.merged(
            datastoreStyle = "vegan", datastoreAllergies = emptySet(), datastoreDislikes = emptySet(),
            roomStyle = "omnivore", roomAllergies = emptyList(), roomDislikes = emptyList()
        )
        assertEquals("vegan", filter.dietStyle)
        assertFalse(filter.allows("salmon"))
    }

    /** Allergy/dislike chips UNION across stores (over-filtering is safe). */
    @Test
    fun `allergies and dislikes union across stores`() {
        val filter = DietTextFilter.merged(
            datastoreStyle = "vegan",
            datastoreAllergies = setOf("soy"),
            datastoreDislikes = emptySet(),
            roomStyle = "vegan",
            roomAllergies = listOf("peanut"),
            roomDislikes = listOf("mushrooms")
        )
        assertFalse(filter.allows("tofu"))       // soy group (DataStore chip)
        assertFalse(filter.allows("peanut butter")) // peanut (Room chip)
        assertFalse(filter.allows("mushrooms"))  // dislike (Room chip)
        assertTrue(filter.allows("lentils"))
    }

    /** Omnivore on both sides stays omnivore. */
    @Test
    fun `omnivore default is preserved`() {
        val filter = DietTextFilter.merged(
            datastoreStyle = "omnivore", datastoreAllergies = emptySet(), datastoreDislikes = emptySet(),
            roomStyle = null, roomAllergies = emptyList(), roomDislikes = emptyList()
        )
        assertEquals("omnivore", filter.dietStyle)
        assertTrue(filter.allows("beef"))
    }
}
