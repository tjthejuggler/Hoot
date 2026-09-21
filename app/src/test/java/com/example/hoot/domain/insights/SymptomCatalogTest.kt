package com.example.hoot.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the state-aware effects line ([SymptomCatalog]) and its wiring
 * through [InsightsEngine.analyze] — deficiency insights carry the seed's
 * deficiency symptoms, excess insights carry the excess risks, non-state
 * kinds carry nothing.
 */
class SymptomCatalogTest {

    private fun def(
        id: String = "vitamin_d",
        deficiency: String? = "Fatigue, bone pain, low mood",
        excess: String? = null
    ) = NutrientInsightDef(
        id = id, name = "Vitamin D", unit = "mcg", tier = 1,
        rda = 15.0, ul = 100.0, foodSources = null,
        deficiencySymptoms = deficiency, excessRisks = excess
    )

    // ---- firstClause ----------------------------------------------------

    @Test
    fun `firstClause splits on sentence and semicolon boundaries`() {
        val raw = "Beriberi: fatigue, neuropathy, heart failure; Wernicke (alcohol)"
        assertEquals(
            "Beriberi: fatigue, neuropathy, heart failure",
            SymptomCatalog.firstClause(raw)
        )
    }

    @Test
    fun `firstClause is null-safe for null blank and punctuation-only input`() {
        assertNull(SymptomCatalog.firstClause(null))
        assertNull(SymptomCatalog.firstClause("   "))
        assertNull(SymptomCatalog.firstClause("; . ;"))
    }

    @Test
    fun `firstClause truncates very long text with ellipsis`() {
        val long = "a".repeat(SymptomCatalog.MAX_LEN + 50)
        val out = SymptomCatalog.firstClause(long)!!
        assertTrue(out.length <= SymptomCatalog.MAX_LEN)
        assertTrue(out.endsWith("…"))
    }

    // ---- effectsFor -----------------------------------------------------

    @Test
    fun `deficiency insight maps to deficiency symptoms`() {
        assertEquals(
            "Fatigue, bone pain, low mood",
            SymptomCatalog.effectsFor(InsightKind.DEFICIENCY, def())
        )
    }

    @Test
    fun `excess insight maps to excess risks`() {
        assertEquals(
            "Kidney strain (pre-existing disease), weight gain",
            SymptomCatalog.effectsFor(
                InsightKind.EXCESS,
                def(deficiency = null, excess = "Kidney strain (pre-existing disease), weight gain")
            )
        )
    }

    @Test
    fun `missing seed text and non-state kinds yield null`() {
        assertNull(SymptomCatalog.effectsFor(InsightKind.DEFICIENCY, def(deficiency = null)))
        assertNull(SymptomCatalog.effectsFor(InsightKind.EXCESS, def(excess = null)))
        assertNull(SymptomCatalog.effectsFor(InsightKind.DEFICIENCY, null))
        for (kind in listOf(
            InsightKind.TREND_UP, InsightKind.TREND_DOWN,
            InsightKind.STREAK, InsightKind.GAP, InsightKind.COACH_NOTE
        )) {
            assertNull(SymptomCatalog.effectsFor(kind, def()))
        }
    }

    // ---- end-to-end wiring through InsightsEngine ------------------------

    @Test
    fun `analyze attaches effects to deficiency and excess insights`() {
        val from = "2026-09-01"
        val to = "2026-09-07"
        val days = listOf("2026-09-01", "2026-09-02", "2026-09-03")
        val window = WindowData(
            from = from, to = to,
            intakeByDay = buildMap {
                // vitamin_d: 3 low days (20% of RDA) → DEFICIENCY.
                days.forEach { put("vitamin_d" to it, 3.0) }
                // sodium: 2 over-cap days → EXCESS.
                days.take(2).forEach { put("sodium" to it, 3000.0) }
                days.drop(2).forEach { put("sodium" to it, 100.0) }
            },
            definitions = mapOf(
                "vitamin_d" to NutrientInsightDef(
                    id = "vitamin_d", name = "Vitamin D", unit = "mcg", tier = 1,
                    rda = 15.0, ul = 100.0, foodSources = "Salmon. Fortified milk.",
                    deficiencySymptoms = "Fatigue, bone pain, low mood",
                    excessRisks = "Hypercalcemia"
                ),
                "sodium" to NutrientInsightDef(
                    id = "sodium", name = "Sodium", unit = "mg", tier = 2,
                    rda = 1500.0, ul = 2300.0, foodSources = null,
                    deficiencySymptoms = null,
                    excessRisks = "Raised blood pressure, bloating"
                )
            )
        )

        val insights = InsightsEngine.analyze(window)
        val deficiency = insights.first { it.kind == InsightKind.DEFICIENCY }
        val excess = insights.first { it.kind == InsightKind.EXCESS }

        assertEquals("Fatigue, bone pain, low mood", deficiency.effects)
        assertEquals("Raised blood pressure, bloating", excess.effects)
    }

    @Test
    fun `analyze leaves effects null when seed has no text for the state`() {
        val window = WindowData(
            from = "2026-09-01", to = "2026-09-07",
            intakeByDay = mapOf(
                "vitamin_b6" to "2026-09-01" to 150.0,
                "vitamin_b6" to "2026-09-02" to 150.0,
                "vitamin_b6" to "2026-09-03" to 150.0
            ),
            definitions = mapOf(
                "vitamin_b6" to NutrientInsightDef(
                    id = "vitamin_b6", name = "Vitamin B6", unit = "mg", tier = 1,
                    rda = 1.3, ul = 100.0, foodSources = null,
                    deficiencySymptoms = "Neuropathy, glossitis",
                    excessRisks = null
                )
            )
        )
        val insights = InsightsEngine.analyze(window)
        // Over its UL → EXCESS, but the seed carries no excess risks → null.
        val excess = insights.first { it.kind == InsightKind.EXCESS }
        assertNull(excess.effects)
        assertFalse(insights.any { it.kind == InsightKind.DEFICIENCY })
    }
}
