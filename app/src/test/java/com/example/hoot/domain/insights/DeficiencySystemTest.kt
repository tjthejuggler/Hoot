package com.example.hoot.domain.insights

import com.example.hoot.data.local.SymptomSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deficiency likelihood model: windows, chronicity, data confidence. */
class DeficiencyModelTest {

    private fun windows(vararg triples: Triple<Int, Int, Double>, tracked: Int = 30) =
        triples.map { (days, low, avg) -> DeficiencyModel.WindowStat(days, tracked, low, avg) }

    @Test fun `perfect intake is near zero likelihood`() {
        val a = DeficiencyModel.assess(
            DeficiencyModel.Input(
                "iron", "Iron", 1, "mg", 8.0,
                windows(
                    Triple(7, 0, 1.0), Triple(30, 0, 1.0),
                    Triple(90, 0, 1.0), Triple(365, 0, 1.0)
                ),
                todayCoverage = 1.0
            )
        )
        assertTrue(a.likelihood < 0.10)
    }

    @Test fun `chronic low intake across all windows beats single bad week`() {
        val chronic = DeficiencyModel.assess(
            DeficiencyModel.Input("iron", "Iron", 1, "mg", 8.0,
                windows(Triple(7, 25, 0.3), Triple(30, 25, 0.3),
                    Triple(90, 25, 0.3), Triple(365, 25, 0.3)))
        )
        val oneWeek = DeficiencyModel.assess(
            DeficiencyModel.Input("iron", "Iron", 1, "mg", 8.0,
                windows(Triple(7, 25, 0.3), Triple(30, 2, 0.9),
                    Triple(90, 2, 0.9), Triple(365, 2, 0.9)))
        )
        assertTrue(chronic.likelihood > oneWeek.likelihood)
        assertTrue(chronic.likelihood > 0.5)
    }

    @Test fun `sparse data shrinks likelihood`() {
        val sparse = DeficiencyModel.assess(
            DeficiencyModel.Input("iron", "Iron", 1, "mg", 8.0,
                windows(Triple(7, 3, 0.2), tracked = 3))
        )
        val rich = DeficiencyModel.assess(
            DeficiencyModel.Input("iron", "Iron", 1, "mg", 8.0,
                windows(Triple(7, 25, 0.2), tracked = 25))
        )
        assertTrue(rich.likelihood > sparse.likelihood)
    }

    @Test fun `worst window reported`() {
        val a = DeficiencyModel.assess(
            DeficiencyModel.Input("iron", "Iron", 1, "mg", 8.0,
                windows(Triple(7, 0, 1.0), Triple(30, 10, 0.4)))
        )
        assertEquals(30, a.worstWindowDays)
        assertEquals(0.4, a.worstWindowAvgCoverage, 0.01)
    }
}

/** Symptom ranking: multi-cause noisy-OR, ordering, thresholds. */
class SymptomMatcherTest {

    private fun assessment(id: String, name: String, p: Double) =
        DeficiencyModel.Assessment(id, name, 1, "mg", 10.0, p, 7, 0.3)

    private fun assoc(id: String, key: String, display: String = key, conf: Double = 0.8) =
        SymptomMatcher.Association(id, key, display, "DEFICIENCY", conf)

    @Test fun `shared symptom outranks single-cause symptom`() {
        val ranked = SymptomMatcher.rank(
            deficiencies = listOf(
                assessment("iron", "Iron", 0.9),
                assessment("vitamin_b12", "B12", 0.8),
                assessment("magnesium", "Magnesium", 0.85)
            ),
            associations = listOf(
                assoc("iron", "fatigue"), assoc("vitamin_b12", "fatigue"),
                assoc("magnesium", "muscle_cramps")
            )
        )
        assertEquals("fatigue", ranked.first().symptomKey)
        assertTrue(ranked.first().likelihood > ranked[1].likelihood)
        assertEquals(2, ranked.first().contributors.size)
        // Contributors sorted strongest-first.
        assertEquals("iron", ranked.first().contributors.first().nutrientId)
    }

    @Test fun `stronger deficiency drives symptom higher`() {
        val strong = SymptomMatcher.rank(
            listOf(assessment("iron", "Iron", 0.9)), listOf(assoc("iron", "fatigue"))
        ).first().likelihood
        val weak = SymptomMatcher.rank(
            listOf(assessment("iron", "Iron", 0.3)), listOf(assoc("iron", "fatigue"))
        ).first().likelihood
        assertTrue(strong > weak)
    }

    @Test fun `near-zero deficiencies drop out as noise`() {
        val ranked = SymptomMatcher.rank(
            deficiencies = listOf(assessment("iron", "Iron", 0.01)),
            associations = listOf(assoc("iron", "fatigue"))
        )
        assertTrue(ranked.isEmpty())
    }

    @Test fun `excess associations need the EXCESS kind`() {
        val ranked = SymptomMatcher.rank(
            deficiencies = listOf(assessment("magnesium", "Magnesium", 0.9)),
            associations = listOf(assoc("magnesium", "diarrhea")),
            kind = SymptomSeed.KIND_EXCESS
        )
        assertTrue(ranked.isEmpty())
    }

    @Test fun `likelihood never claims certainty`() {
        val ranked = SymptomMatcher.rank(
            deficiencies = (1..6).map { assessment("n$it", "N$it", 0.95) },
            associations = (1..6).map { assoc("n$it", "fatigue") }
        )
        assertTrue(ranked.first().likelihood < 0.99)
    }
}

/** The curated seed: ids valid, keys normalized, confidences bounded. */
class SymptomSeedTest {

    @Test fun `every seed id is a known canonical nutrient`() {
        val known = setOf(
            "protein", "carbohydrates", "fiber", "added_sugar", "total_fat",
            "saturated_fat", "monounsaturated_fat", "polyunsaturated_fat",
            "omega3_epa_dha", "omega3_ala", "omega6_la", "trans_fat",
            "cholesterol", "water", "calories", "vitamin_a", "vitamin_b1",
            "vitamin_b2", "vitamin_b3", "vitamin_b5", "vitamin_b6",
            "vitamin_b7", "vitamin_b9", "vitamin_b12", "vitamin_c",
            "vitamin_d", "vitamin_e", "vitamin_k", "calcium", "phosphorus",
            "magnesium", "sodium", "potassium", "chloride", "iron", "zinc",
            "copper", "manganese", "fluoride", "selenium", "iodine",
            "chromium", "molybdenum", "lutein_zeaxanthin", "choline",
            "electrolyte_ratio"
        )
        for (row in SymptomSeed.seed()) {
            assertTrue("unknown nutrient ${row.nutrientId}", row.nutrientId in known)
        }
    }

    @Test fun `keys are normalized snake_case`() {
        for (row in SymptomSeed.seed()) {
            assertTrue(row.symptomKey.matches(Regex("[a-z0-9_]+")))
            assertTrue(row.displayName.isNotBlank())
            assertTrue(row.confidence in 0.1..1.0)
            assertTrue(row.id == "${row.nutrientId}:${row.kind}:${row.symptomKey}")
        }
    }

    @Test fun `fatigue is multi-cause as intended`() {
        val fatigue = SymptomSeed.seed().filter { it.symptomKey == "fatigue" && it.kind == SymptomSeed.KIND_DEFICIENCY }
        assertTrue(fatigue.size >= 5)   // iron, B12, D, folate, vitamin C, …
        assertNotNull(fatigue.firstOrNull { it.nutrientId == "iron" })
    }

    @Test fun `seed is non-trivial`() {
        assertTrue(SymptomSeed.seed().size >= 80)
        assertTrue(SymptomSeed.seed().map { it.nutrientId }.distinct().size >= 20)
    }
}
