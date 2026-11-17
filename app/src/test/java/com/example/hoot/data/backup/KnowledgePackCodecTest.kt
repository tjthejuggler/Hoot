package com.example.hoot.data.backup

import com.example.hoot.data.local.entity.FoodKnowledgeEntity
import com.example.hoot.data.local.entity.NutrientDefinitionEntity
import com.example.hoot.data.local.entity.NutrientSymptomEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip pins for the knowledge-pack codec (feedback 2026-11-17):
 * export → parse must reproduce the knowledge exactly, reject foreign
 * payloads, and never carry personal data fields (by construction — the
 * codec only serializes the three knowledge stores).
 */
class KnowledgePackCodecTest {

    private fun def(id: String = "iron") = NutrientDefinitionEntity(
        id = id, name = "Iron", group = "mineral", unit = "mg",
        rdaValue = 8.0, ulValue = 45.0, tier = 1,
        deficiencySymptoms = "Fatigue", excessRisks = null, foodSources = "red meat"
    )

    private fun sym(nutrientId: String = "iron", key: String = "fatigue") = NutrientSymptomEntity(
        id = "$nutrientId:DEFICIENCY:$key",
        nutrientId = nutrientId,
        symptomKey = key,
        displayName = "Fatigue",
        kind = "DEFICIENCY",
        confidence = 0.9,
        source = "SEED",
        researchedAt = null
    )

    private fun food(name: String = "lentil") = FoodKnowledgeEntity(
        id = "uuid-1",
        normalizedName = name,
        displayName = name.replaceFirstChar { it.uppercase() },
        valuesJson = """{"iron":6.6,"protein":9.0}""",
        highInJson = """["iron"]""",
        confidence = 0.8,
        resolutionMethod = "web_usda",
        sourceUrlsJson = """["https://fdc.nal.usda.gov"]""",
        researchedForJson = """["iron"]""",
        firstResearchedAt = 100L,
        lastResearchedAt = 200L,
        researchCount = 3
    )

    @Test
    fun `round trip preserves definitions symptoms and foods`() {
        val json = KnowledgePackCodec.exportJson(
            nutrients = listOf(def()),
            symptomAssociations = listOf(sym()),
            foods = listOf(food())
        )
        val pack = KnowledgePackCodec.parse(json)
        assertNotNull(pack)
        assertEquals(listOf(def()), pack!!.nutrients)
        // researchedAt is exported; sym() used null → stays null.
        assertEquals(1, pack.symptomAssociations.size)
        assertEquals("fatigue", pack.symptomAssociations.first().symptomKey)
        // id is per-install (regenerated on parse); everything else round-trips.
        val parsedFood = pack.foods.single()
        assertEquals(food().copy(id = parsedFood.id), parsedFood)
        assertFalse(pack.isEmpty)
        assertTrue(pack.summary().contains("1 nutrients"))
        assertTrue(pack.summary().contains("1 foods"))
        assertTrue(pack.summary().contains("1 symptom links"))
    }

    @Test
    fun `foreign json is rejected`() {
        assertNull(KnowledgePackCodec.parse("""{"app":"hoot","meals":[]}"""))
        assertNull(KnowledgePackCodec.parse("not json at all"))
        assertNull(KnowledgePackCodec.parse("""{"kind":"something-else"}"""))
    }

    @Test
    fun `symptom ids are deterministic for idempotent import`() {
        val json = KnowledgePackCodec.exportJson(emptyList(), listOf(sym()), emptyList())
        val row = KnowledgePackCodec.parse(json)!!.symptomAssociations.single()
        assertEquals("iron:DEFICIENCY:fatigue", row.id)
    }

    @Test
    fun `blank food names are dropped on parse`() {
        val bad = """{"kind":"hoot-knowledge-pack","version":1,
            "nutrients":[],"symptomAssociations":[],
            "foods":[{"normalizedName":"  ","displayName":"X"}]}""".trimIndent()
        val pack = KnowledgePackCodec.parse(bad)
        assertNotNull(pack)
        assertTrue(pack!!.foods.isEmpty())
    }
}
