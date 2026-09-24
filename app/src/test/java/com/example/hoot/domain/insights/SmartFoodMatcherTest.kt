package com.example.hoot.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the SmartFoodMatcher scoring math (feature C): coverage
 * capped at the remaining deficit, tier weighting, excess penalty + red-line
 * hard reject, diversity de-dupe, diet filtering, empty-cache behavior.
 */
class SmartFoodMatcherTest {

    // ---- Fixtures -------------------------------------------------------------

    private fun gap(
        id: String,
        tier: Int = 1,
        target: Double = 400.0,
        today: Double = 100.0,
        name: String = id
    ) = SmartGap(
        nutrientId = id, name = name, tier = tier, unit = "mg",
        target = target, todayIntake = today
    )

    private fun excess(
        id: String,
        limit: Double = 2000.0,
        intake: Double = 0.0,
        redLine: Boolean = true,
        name: String = id
    ) = SmartExcess(
        nutrientId = id, name = name, limit = limit, intake = intake, redLine = redLine
    )

    private fun food(
        id: String,
        name: String = id,
        category: String? = null,
        serving: Double? = 100.0,
        per100: Map<String, Double> = emptyMap()
    ) = SmartCandidateFood(
        foodId = id, displayName = name, category = category,
        servingGrams = serving, per100 = per100
    )

    // ---- Basic scoring ----------------------------------------------------------

    @Test
    fun `coverage is capped at the remaining deficit`() {
        // Deficit = 300; one serving has 500 → capped at 100%.
        val picks = SmartFoodMatcher.match(
            gaps = listOf(gap("magnesium", target = 400.0, today = 100.0)),
            excesses = emptyList(),
            foods = listOf(food("f1", per100 = mapOf("magnesium" to 500.0)))
        )
        assertEquals(1, picks.size)
        assertEquals(1.0, picks[0].hits.first().deficitCovered, 1e-9)
    }

    @Test
    fun `serving size scales the per-100g amount`() {
        // 200 mg/100 g with a 50 g serving = 100 mg of a 300 mg deficit.
        val picks = SmartFoodMatcher.match(
            gaps = listOf(gap("iron", target = 400.0, today = 100.0)),
            excesses = emptyList(),
            foods = listOf(food("f1", serving = 50.0, per100 = mapOf("iron" to 200.0)))
        )
        assertEquals(100.0 / 300.0, picks[0].hits.first().deficitCovered, 1e-9)
        assertEquals(50.0, picks[0].servingGrams, 1e-9)
    }

    @Test
    fun `null serving falls back to 100 g`() {
        val picks = SmartFoodMatcher.match(
            gaps = listOf(gap("zinc", target = 200.0, today = 50.0)),
            excesses = emptyList(),
            foods = listOf(food("f1", serving = null, per100 = mapOf("zinc" to 100.0)))
        )
        assertEquals(100.0, picks[0].servingGrams, 1e-9)
        assertEquals(100.0 / 150.0, picks[0].hits.first().deficitCovered, 1e-9)
    }

    @Test
    fun `multi-nutrient food outranks single-nutrient food`() {
        val multi = food(
            "multi", name = "kale",
            per100 = mapOf(
                "magnesium" to 200.0,   // covers 33% of gap 1
                "vitamin_c" to 250.0    // covers 41% of gap 2
            )
        )
        val single = food("single", name = "rice", per100 = mapOf("magnesium" to 200.0))
        val picks = SmartFoodMatcher.match(
            gaps = listOf(
                gap("magnesium", tier = 1, target = 400.0, today = 100.0),
                gap("vitamin_c", tier = 1, target = 400.0, today = 50.0)
            ),
            excesses = emptyList(),
            foods = listOf(single, multi)
        )
        assertEquals("kale", picks[0].displayName)
        assertEquals(2, picks[0].hits.size)
    }

    @Test
    fun `tier weighting ranks tier1 coverage over equal tier3 coverage`() {
        val t1Food = food("t1", per100 = mapOf("a_t1" to 300.0))
        val t3Food = food("t3", per100 = mapOf("c_t3" to 300.0))
        val picks = SmartFoodMatcher.match(
            gaps = listOf(
                gap("a_t1", tier = 1, target = 400.0, today = 100.0),
                gap("c_t3", tier = 3, target = 400.0, today = 100.0)
            ),
            excesses = emptyList(),
            foods = listOf(t1Food, t3Food)
        )
        assertEquals("t1", picks[0].displayName)
    }

    // ---- Excess penalty / red-line ----------------------------------------------

    @Test
    fun `food heavy in an over red-line tracker is hard rejected`() {
        // Sodium: limit 2000, already at 2200, serving has 400 (> 15% of limit) → reject.
        val salty = food("salty", name = "canned soup", per100 = mapOf("magnesium" to 100.0, "sodium" to 400.0))
        val picks = SmartFoodMatcher.match(
            gaps = listOf(gap("magnesium")),
            excesses = listOf(excess("sodium", limit = 2000.0, intake = 2200.0, redLine = true)),
            foods = listOf(salty)
        )
        assertTrue(picks.isEmpty())
    }

    @Test
    fun `small excess contribution only adds a penalty and a caution`() {
        // Sodium serving share = 100/2000 = 5% → below caution threshold, tiny penalty.
        val pick = SmartFoodMatcher.match(
            gaps = listOf(gap("magnesium", target = 400.0, today = 100.0)),
            excesses = listOf(excess("sodium", limit = 2000.0, intake = 2200.0, redLine = true)),
            foods = listOf(food("fine", per100 = mapOf("magnesium" to 300.0, "sodium" to 100.0)))
        )
        assertEquals(1, pick.size)
        assertTrue(pick[0].cautions.isEmpty())
        // 100% of the magnesium gap − 5% × 1.5 penalty → still strong.
        assertTrue(pick[0].score > 0.8)
    }

    @Test
    fun `penalty pushes a mediocre food below a clean one`() {
        val clean = food("clean", name = "almonds", per100 = mapOf("magnesium" to 250.0))
        val dirty = food("dirty", name = "salted nuts", per100 = mapOf("magnesium" to 250.0, "sodium" to 600.0))
        val picks = SmartFoodMatcher.match(
            gaps = listOf(gap("magnesium", target = 400.0, today = 100.0)),
            excesses = listOf(excess("sodium", limit = 2000.0, intake = 0.0, redLine = true)),
            foods = listOf(dirty, clean)
        )
        assertEquals("almonds", picks[0].displayName)
        // Dirty pick keeps a caution (600/2000 = 30% ≥ 10%).
        assertEquals(listOf("watch sodium"), picks[1].cautions)
    }

    @Test
    fun `non red-line excess above 120 percent target also penalizes`() {
        // "calcium" at 130% of its cap consistently → treated as excess:
        // a heavy calcium serving nets ≤ 0 → dropped; a light one stays + caution.
        val picks = SmartFoodMatcher.match(
            gaps = listOf(gap("magnesium", target = 400.0, today = 100.0)),
            excesses = listOf(excess("calcium", limit = 1000.0, intake = 1300.0, redLine = false)),
            foods = listOf(
                food("cheese", per100 = mapOf("magnesium" to 100.0, "calcium" to 500.0)),
                food("milk", per100 = mapOf("magnesium" to 100.0, "calcium" to 100.0))
            )
        )
        assertEquals(listOf("milk"), picks.map { it.displayName })
        assertEquals(listOf("watch calcium"), picks[0].cautions)
    }

    // ---- Diversity ----------------------------------------------------------------

    @Test
    fun `near-identical hit sets are de-duplicated but distinct foods backfill`() {
        // Three DIFFERENT leafy greens covering the SAME two gaps (Jaccard
        // 1.0), plus one distinct food → one green wins the dedupe, eggs
        // fills slot 2, and the backfill reuses one more green (distinct
        // core) rather than returning 2 picks. Same-CORE form duplicates
        // ("Carrot Juice" vs "Carrots") are the ones that can NEVER backfill
        // — pinned by `one core ingredient appears at most once across forms`.
        val per100 = mapOf("magnesium" to 200.0, "vitamin_c" to 200.0)
        val picks = SmartFoodMatcher.match(
            gaps = listOf(
                gap("magnesium", target = 400.0, today = 100.0),
                gap("vitamin_c", target = 400.0, today = 100.0)
            ),
            excesses = emptyList(),
            foods = listOf(
                food("kale", per100 = per100),
                food("spinach", per100 = per100),
                food("chard", per100 = per100),
                food("eggs", per100 = mapOf("magnesium" to 150.0))
            ),
            max = 3
        )
        assertEquals(3, picks.size)
        assertEquals("kale", picks[0].displayName)
        assertEquals("eggs", picks[1].displayName)
        val greens = picks.count { it.displayName in listOf("kale", "spinach", "chard") }
        assertEquals(2, greens)
    }

    @Test
    fun `one core ingredient appears at most once across forms`() {
        // The exact device report (feedback 2026-09-24): 4 of 7 picks were
        // carrots in some form. Core-ingredient collapse must keep exactly
        // one, even when panels differ slightly.
        val picks = SmartFoodMatcher.match(
            gaps = listOf(
                gap("vitamin_a", tier = 1, target = 900.0, today = 100.0),
                gap("vitamin_k", tier = 2, target = 120.0, today = 10.0)
            ),
            excesses = emptyList(),
            foods = listOf(
                food("carrot", name = "Carrots", serving = 100.0,
                    per100 = mapOf("vitamin_a" to 835.0, "vitamin_k" to 13.0)),
                food("carrot-juice", name = "Carrot Juice", serving = 240.0,
                    per100 = mapOf("vitamin_a" to 835.0, "vitamin_k" to 13.0)),
                food("grated-carrot", name = "Grated Carrot", serving = 80.0,
                    per100 = mapOf("vitamin_a" to 800.0)),
                food("sweet-potato", name = "Sweet Potato", serving = 150.0,
                    per100 = mapOf("vitamin_a" to 709.0)),
                food("spinach2", name = "Spinach", serving = 80.0,
                    per100 = mapOf("vitamin_k" to 483.0))
            ),
            max = 6
        )
        val carrotPicks = picks.count {
            SmartFoodMatcher.coreIngredientKey(it.displayName) == "carrot"
        }
        assertEquals(1, carrotPicks)
    }

    @Test
    fun `form words keep distinct cores distinct`() {
        // "Olive Oil" vs "Canola Oil": the shared form word is skipped, not
        // collapsed — two different oils are two legitimate picks.
        assertEquals("olive", SmartFoodMatcher.coreIngredientKey("Olive Oil"))
        assertEquals("canola", SmartFoodMatcher.coreIngredientKey("Canola Oil"))
        assertEquals("carrot", SmartFoodMatcher.coreIngredientKey("Grated Carrot"))
        assertEquals("carrot", SmartFoodMatcher.coreIngredientKey("Carrot Juice"))
    }

    @Test
    fun `diversity relaxes when the pool cannot fill max slots`() {
        val per100 = mapOf("magnesium" to 200.0)
        val picks = SmartFoodMatcher.match(
            gaps = listOf(gap("magnesium", target = 400.0, today = 100.0)),
            excesses = emptyList(),
            foods = listOf(
                food("kale", per100 = per100),
                food("spinach", per100 = per100),
                food("chard", per100 = per100)
            ),
            max = 3
        )
        // Same hit set → only 1 survives the dedupe, but the fallback fills
        // remaining slots rather than returning fewer than possible.
        assertEquals(3, picks.size)
    }

    @Test
    fun `default cap is 12 picks matching SmartFoodProvider MAX_PICKS`() {
        // const val is inlined at the call site — no Android deps touched.
        assertEquals(12, SmartFoodProvider.MAX_PICKS)
    }

    @Test
    fun `default match returns up to 12 distinct picks without explicit max`() {
        // 12 distinct foods each covering a different gap → all 12 survive
        // the diversity dedupe and the (default) cap of 12.
        val foods = (1..12).map { i ->
            food("food$i", per100 = mapOf("nutrient$i" to 200.0))
        }
        val gaps = (1..12).map { i -> gap("nutrient$i", target = 400.0, today = 100.0) }
        val picks = SmartFoodMatcher.match(
            gaps = gaps,
            excesses = emptyList(),
            foods = foods
        )
        assertEquals(12, picks.size)
    }

    // ---- Diet filtering -------------------------------------------------------------

    @Test
    fun `vegan filter excludes animal foods`() {
        val picks = SmartFoodMatcher.match(
            gaps = listOf(gap("protein", target = 100.0, today = 20.0)),
            excesses = emptyList(),
            foods = listOf(
                food("steak", per100 = mapOf("protein" to 60.0)),
                food("lentils", per100 = mapOf("protein" to 25.0))
            ),
            diet = SmartDietFilter(dietStyle = "vegan")
        )
        assertEquals(listOf("lentils"), picks.map { it.displayName })
    }

    @Test
    fun `allergy and dislike filters apply`() {
        val picks = SmartFoodMatcher.match(
            gaps = listOf(gap("protein", target = 100.0, today = 20.0)),
            excesses = emptyList(),
            foods = listOf(
                food("peanut butter", per100 = mapOf("protein" to 30.0)),
                food("tofu", per100 = mapOf("protein" to 12.0)),
                food("tempeh", per100 = mapOf("protein" to 19.0))
            ),
            diet = SmartDietFilter(
                dietStyle = "vegan",
                allergies = listOf("peanuts"),
                dislikes = listOf("tofu")
            )
        )
        assertEquals(listOf("tempeh"), picks.map { it.displayName })
    }

    @Test
    fun `keto filter excludes high-carb staples`() {
        val picks = SmartFoodMatcher.match(
            gaps = listOf(gap("magnesium", target = 400.0, today = 100.0)),
            excesses = emptyList(),
            foods = listOf(
                food("bread", per100 = mapOf("magnesium" to 100.0)),
                food("oats", per100 = mapOf("magnesium" to 90.0))
            ),
            diet = SmartDietFilter(dietStyle = "keto")
        )
        assertTrue(picks.isEmpty())
    }

    // ---- Empty / edge behavior -------------------------------------------------------

    @Test
    fun `empty cache yields no picks`() {
        val picks = SmartFoodMatcher.match(
            gaps = listOf(gap("magnesium")),
            excesses = emptyList(),
            foods = emptyList()
        )
        assertTrue(picks.isEmpty())
    }

    @Test
    fun `no gaps yields no picks`() {
        val picks = SmartFoodMatcher.match(
            gaps = emptyList(),
            excesses = emptyList(),
            foods = listOf(food("f1", per100 = mapOf("magnesium" to 100.0)))
        )
        assertTrue(picks.isEmpty())
    }

    @Test
    fun `fully satisfied gap yields no picks`() {
        val picks = SmartFoodMatcher.match(
            gaps = listOf(gap("magnesium", target = 400.0, today = 400.0)),
            excesses = emptyList(),
            foods = listOf(food("f1", per100 = mapOf("magnesium" to 100.0)))
        )
        assertTrue(picks.isEmpty())
    }

    @Test
    fun `food without any gap nutrient is dropped`() {
        val picks = SmartFoodMatcher.match(
            gaps = listOf(gap("magnesium")),
            excesses = emptyList(),
            foods = listOf(food("candy", per100 = mapOf("sugar" to 80.0)))
        )
        assertTrue(picks.isEmpty())
    }

    @Test
    fun `hits summary names the top covered nutrient`() {
        val pick = SmartFoodMatcher.match(
            gaps = listOf(
                gap("magnesium", target = 400.0, today = 100.0, name = "Magnesium"),
                gap("iron", target = 400.0, today = 250.0, name = "Iron")
            ),
            excesses = emptyList(),
            foods = listOf(food("kale", per100 = mapOf("magnesium" to 300.0, "iron" to 300.0)))
        )[0]
        assertTrue(pick.hitsSummary().startsWith("2 gaps"))
        assertTrue(pick.hitsSummary().contains("Magnesium 100%"))
    }

    @Test
    fun `why line is deterministic and mentions both hits`() {
        val pick = SmartFoodMatcher.match(
            gaps = listOf(
                gap("magnesium", target = 400.0, today = 100.0, name = "Magnesium"),
                gap("iron", target = 400.0, today = 250.0, name = "Iron")
            ),
            excesses = emptyList(),
            // Iron: 75 g per serving = 50% of the 150-unit deficit (uncapped).
            foods = listOf(food("kale", per100 = mapOf("magnesium" to 300.0, "iron" to 75.0)))
        )[0]
        assertTrue(
            pick.why.contains("kale covers Magnesium (100% of today's gap) and Iron (50% of today's gap)")
        )
        assertTrue(pick.why.contains("hits 2 of your gaps"))
    }

    @Test
    fun `source defaults to cache`() {
        val picks = SmartFoodMatcher.match(
            gaps = listOf(gap("magnesium")),
            excesses = emptyList(),
            foods = listOf(food("f1", per100 = mapOf("magnesium" to 100.0)))
        )
        assertEquals("cache", picks[0].source)
    }
}
