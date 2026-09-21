package com.example.hoot.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the central diet-aware source pipeline (diet-fix 2026-09):
 * [DietRules] keyword gating (vegan/vegetarian/pescatarian/dairy-free/
 * gluten-free/allergies/dislikes, word-boundary + plant-phrase handling) and
 * [DietAwareSources] seeded-line filtering + curated alternatives for
 * diet-emptied nutrients.
 */
class DietAwareSourcesTest {

    private val vegan = DietProfile(dietStyle = "vegan")
    private val vegetarian = DietProfile(dietStyle = "vegetarian")
    private val pescatarian = DietProfile(dietStyle = "pescatarian")
    private val dairyFree = DietProfile(dietStyle = "dairy-free")
    private val glutenFree = DietProfile(dietStyle = "gluten-free")
    private val omnivore = DietProfile(dietStyle = "omnivore")

    // ---- DietRules: style gating --------------------------------------------

    @Test
    fun `vegan rejects meat fish eggs dairy`() {
        for (food in listOf(
            "meat", "beef", "pork", "chicken", "poultry", "liver", "bacon",
            "fish", "salmon", "tuna", "sardines", "shellfish", "shrimp",
            "eggs", "egg", "milk", "cheese", "yogurt", "butter", "cream",
            "whey", "casein", "gelatin", "honey", "dairy"
        )) {
            assertFalse("vegan must reject '$food'", DietRules.allowsFood(food, vegan))
        }
    }

    @Test
    fun `vegan accepts plant foods`() {
        for (food in listOf(
            "lentils", "tofu", "tempeh", "seitan", "chickpeas", "quinoa",
            "hemp seeds", "fortified plant milk", "kale", "spinach",
            "nutritional yeast", "peanut butter", "almonds"
        )) {
            assertTrue("vegan must accept '$food'", DietRules.allowsFood(food, vegan))
        }
    }

    @Test
    fun `word boundary - egg does not block eggplant`() {
        assertTrue(DietRules.allowsFood("eggplant", vegan))
    }

    @Test
    fun `word boundary - ham prefix is not a whole-word match`() {
        // "hamburg" contains "ham" as prefix, not word → allowed; real "ham" is not.
        assertTrue(DietRules.allowsFood("hamburger bun", vegan))
        assertFalse(DietRules.allowsFood("ham", vegan))
    }

    @Test
    fun `plant-qualified dairy survives vegan and dairy-free`() {
        assertTrue(DietRules.allowsFood("fortified plant milk", vegan))
        assertTrue(DietRules.allowsFood("coconut milk", vegan))
        assertTrue(DietRules.allowsFood("soy yogurt", dairyFree))
        assertTrue(DietRules.allowsFood("peanut butter", vegan))
        assertTrue(DietRules.allowsFood("almond milk", dairyFree))
    }

    @Test
    fun `vegetarian keeps eggs dairy but drops meat fish`() {
        assertTrue(DietRules.allowsFood("eggs", vegetarian))
        assertTrue(DietRules.allowsFood("greek yogurt", vegetarian))
        assertTrue(DietRules.allowsFood("cheese", vegetarian))
        assertFalse(DietRules.allowsFood("beef", vegetarian))
        assertFalse(DietRules.allowsFood("chicken", vegetarian))
        assertFalse(DietRules.allowsFood("salmon", vegetarian))
        assertFalse(DietRules.allowsFood("fish", vegetarian))
    }

    @Test
    fun `pescatarian keeps fish drops meat`() {
        assertTrue(DietRules.allowsFood("salmon", pescatarian))
        assertTrue(DietRules.allowsFood("sardines", pescatarian))
        assertTrue(DietRules.allowsFood("fatty fish", pescatarian))
        assertFalse(DietRules.allowsFood("beef", pescatarian))
        assertFalse(DietRules.allowsFood("poultry", pescatarian))
        assertFalse(DietRules.allowsFood("meat", pescatarian))
        assertFalse(DietRules.allowsFood("liver", pescatarian))
    }

    @Test
    fun `dairy-free drops dairy everywhere but keeps meat fish eggs`() {
        for (food in listOf("milk", "cheese", "yogurt", "butter", "cream", "whey", "casein")) {
            assertFalse("dairy-free must reject '$food'", DietRules.allowsFood(food, dairyFree))
        }
        assertTrue(DietRules.allowsFood("salmon", dairyFree))
        assertTrue(DietRules.allowsFood("eggs", dairyFree))
        assertTrue(DietRules.allowsFood("beef", dairyFree))
    }

    @Test
    fun `gluten-free drops gluten grains but keeps rice and gluten-free oats`() {
        for (food in listOf("wheat", "barley", "rye", "seitan", "bread", "pasta")) {
            assertFalse("gluten-free must reject '$food'", DietRules.allowsFood(food, glutenFree))
        }
        assertTrue(DietRules.allowsFood("rice", glutenFree))
        assertTrue(DietRules.allowsFood("gluten-free oats", glutenFree))
    }

    @Test
    fun `unknown style is permissive`() {
        assertTrue(DietRules.allowsFood("beef", DietProfile(dietStyle = "something-else")))
        assertTrue(DietRules.allowsFood("beef", DietProfile(dietStyle = "")))
    }

    // ---- DietRules: allergy + dislike chips -----------------------------------

    @Test
    fun `allergy chips respected including synonyms`() {
        val soyAllergy = DietProfile(dietStyle = "vegan", allergies = listOf("soy"))
        assertFalse(DietRules.allowsFood("tofu", soyAllergy))
        assertFalse(DietRules.allowsFood("tempeh", soyAllergy))
        assertFalse(DietRules.allowsFood("edamame", soyAllergy))
        assertTrue(DietRules.allowsFood("lentils", soyAllergy))

        val eggAllergy = DietProfile(allergies = listOf("egg"))
        assertFalse(DietRules.allowsFood("eggs", eggAllergy))
        assertFalse(DietRules.allowsFood("mayonnaise", eggAllergy))
    }

    @Test
    fun `nut allergy excludes listed tree nuts`() {
        val nutAllergy = DietProfile(allergies = listOf("nuts"))
        assertFalse(DietRules.allowsFood("almonds", nutAllergy))
        assertFalse(DietRules.allowsFood("walnuts", nutAllergy))
        assertFalse(DietRules.allowsFood("cashews", nutAllergy))
        // Seed alternatives that would clash must be filtered out downstream.
        assertTrue(DietRules.allowsFood("sunflower seeds", nutAllergy))
    }

    @Test
    fun `dislikes respected`() {
        val profile = DietProfile(dislikes = listOf("mushrooms"))
        assertFalse(DietRules.allowsFood("mushrooms", profile))
        assertFalse(DietRules.allowsFood("portobello mushrooms", profile))
        assertTrue(DietRules.allowsFood("tofu", profile))
    }

    @Test
    fun `blank chips are ignored`() {
        val profile = DietProfile(allergies = listOf("", "  "), dislikes = listOf(""))
        assertTrue(DietRules.allowsFood("milk", profile))
    }

    // ---- DietAwareSources: seeded-line filtering ------------------------------

    @Test
    fun `vegan empties protein seed and substitutes alternatives`() {
        val line = DietAwareSources.filterLine(
            "Meat, fish, eggs, dairy, legumes, tofu, seitan", vegan, "protein"
        )
        // legumes/tofu/seitan survive the filter, so the filtered list is used.
        assertFalse(line.contains("Meat"))
        assertFalse(line.contains("fish"))
        assertFalse(line.contains("eggs", ignoreCase = true))
        assertFalse(line.contains("dairy", ignoreCase = true))
        assertTrue(line.contains("legumes"))
        assertTrue(line.contains("tofu"))
    }

    @Test
    fun `fully-emptied seed falls back to curated alternatives`() {
        // Calcium seed: "Dairy, fortified plant milk, tofu (Ca-set), kale, sardines w/ bones"
        // vegan keeps only the generic/plant tokens; assert NO animal words remain.
        val line = DietAwareSources.filterLine(
            "Dairy, fortified plant milk, tofu (Ca-set), kale, sardines w/ bones",
            vegan, "calcium"
        )
        assertFalse(line.contains("Dairy", ignoreCase = true))
        assertFalse(line.contains("sardines", ignoreCase = true))
    }

    @Test
    fun `b12 vegan seed falls back to fortified + supplement alternatives`() {
        val alts = DietAwareSources.alternativesFor("vitamin_b12", vegan)
        assertTrue(alts.isNotEmpty())
        assertTrue(alts.any { it.contains("nutritional yeast") })
        assertTrue(alts.none { it.contains("meat") })
        assertTrue(alts.none { it.contains("fish") })
        assertTrue(alts.none { it.contains("eggs") })
    }

    @Test
    fun `alternatives respect user dislikes`() {
        val noSoy = DietProfile(dietStyle = "vegan", dislikes = listOf("tofu"))
        val alts = DietAwareSources.alternativesFor("protein", noSoy)
        assertTrue(alts.isNotEmpty())
        assertTrue(alts.none { it.contains("tofu") })
        assertTrue(alts.contains("lentils"))
    }

    @Test
    fun `alternatives respect nut allergy`() {
        val noNuts = DietProfile(dietStyle = "vegan", allergies = listOf("nuts"))
        val calcium = DietAwareSources.alternativesFor("calcium", noNuts)
        assertTrue(calcium.none { it.contains("almond") })
        val protein = DietAwareSources.alternativesFor("protein", noNuts)
        assertTrue(protein.none { it.contains("peanut butter") })
    }

    @Test
    fun `vegetarian protein alternatives keep eggs and dairy`() {
        val alts = DietAwareSources.alternativesFor("protein", vegetarian)
        assertTrue(alts.contains("eggs"))
        assertTrue(alts.any { it.contains("yogurt") })
    }

    @Test
    fun `pescatarian omega3 alternatives keep fatty fish`() {
        val alts = DietAwareSources.alternativesFor("omega3_epa_dha", pescatarian)
        assertTrue(alts.contains("salmon"))
    }

    @Test
    fun `omnivore alternatives are not needed`() {
        assertTrue(DietAwareSources.alternativesFor("protein", omnivore).isEmpty())
    }

    @Test
    fun `unknown nutrient with empty seed returns fallback phrase`() {
        val line = DietAwareSources.alternativesLine("mystery_nutrient", vegan)
        assertEquals(DietAwareSources.EMPTY_PHRASE, line)
        assertEquals(DietAwareSources.EMPTY_PHRASE, DietAwareSources.alternativesLine(null, vegan))
    }

    @Test
    fun `empty-string sources never crash`() {
        assertEquals("", DietAwareSources.filterLine("", vegan, "protein"))
        assertEquals("   ", DietAwareSources.filterLine("   ", vegan, "protein"))
        // DietTextFilter passthrough (isBlank short-circuit).
        val filter = DietTextFilter(dietStyle = "vegan")
        assertEquals("", filter.filterList(""))
    }

    @Test
    fun `omnivore sees the original seed untouched`() {
        val seed = "Meat, fish, eggs, dairy, legumes, tofu, seitan"
        assertEquals(
            seed,
            DietAwareSources.filterLine(seed, omnivore, "protein")
        )
    }

    // ---- DietTextFilter delegation (insight text surfaces) ----------------------

    @Test
    fun `DietTextFilter routes through central rules`() {
        val filter = DietTextFilter(dietStyle = "vegan")
        assertFalse(filter.allows("beef"))
        assertFalse(filter.allows("milk"))
        assertTrue(filter.allows("lentils"))
        assertTrue(filter.allows("eggplant"))
    }

    // ---- Seed-table integration spot checks -------------------------------------

    @Test
    fun `seeded protein line for vegan mentions no animal foods`() {
        val filter = DietTextFilter(dietStyle = "vegan")
        val out = filter.filterList("Meat, fish, eggs, dairy, legumes, tofu, seitan", "protein")
        for (banned in listOf("meat", "fish", "egg", "dairy")) {
            assertFalse("leak: $banned in '$out'", Regex("\\b$banned", RegexOption.IGNORE_CASE).containsMatchIn(out))
        }
    }

    @Test
    fun `seeded calcium line for vegan mentions no dairy or sardines`() {
        val filter = DietTextFilter(dietStyle = "vegan")
        val out = filter.filterList(
            "Dairy, fortified plant milk, tofu (Ca-set), kale, sardines w/ bones", "calcium"
        )
        for (banned in listOf("dairy", "sardine")) {
            assertFalse("leak: $banned in '$out'", Regex("\\b$banned", RegexOption.IGNORE_CASE).containsMatchIn(out))
        }
    }

    @Test
    fun `seeded iodine line for vegan falls back to alternatives`() {
        // Seed: "Iodized salt, seaweed, cod, dairy, eggs" — vegan keeps salt
        // and seaweed; cod/dairy/eggs are removed.
        val filter = DietTextFilter(dietStyle = "vegan")
        val out = filter.filterList("Iodized salt, seaweed, cod, dairy, eggs", "iodine")
        for (banned in listOf("cod", "dairy", "egg")) {
            assertFalse("leak: $banned in '$out'", Regex("\\b$banned", RegexOption.IGNORE_CASE).containsMatchIn(out))
        }
        assertTrue(out.contains("Iodized salt") || out.contains("iodized salt"))
    }
}
