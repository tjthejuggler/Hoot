package com.example.hoot.data.backup

import com.example.hoot.data.local.entity.FoodKnowledgeEntity
import com.example.hoot.data.local.entity.NutrientDefinitionEntity
import com.example.hoot.data.local.entity.NutrientSymptomEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Knowledge-pack export/import (feedback 2026-11-17): a shareable JSON file
 * with everything the user has AMASSED about nutrition — canonical nutrient
 * definitions, researched food panels (the food-knowledge base) and
 * nutrient ↔ symptom associations — and NOTHING personal. No meals, no
 * supplements, no intake ledger, no scores, no goals, no diet profile.
 *
 * The point: seed someone else's Hoot with months of accumulated research
 * in one tap (Import in Settings → Knowledge pack).
 *
 * Pure JVM + org.json so the round-trip is unit-pinned.
 */
object KnowledgePackCodec {

    const val KIND = "hoot-knowledge-pack"
    const val VERSION = 1

    /** Parsed pack ready to apply. */
    data class Pack(
        val nutrients: List<NutrientDefinitionEntity>,
        val symptomAssociations: List<NutrientSymptomEntity>,
        val foods: List<FoodKnowledgeEntity>
    ) {
        val isEmpty: Boolean get() = nutrients.isEmpty() && symptomAssociations.isEmpty() && foods.isEmpty()
        fun summary(): String {
            val parts = buildList {
                if (nutrients.isNotEmpty()) add("${nutrients.size} nutrients")
                if (foods.isNotEmpty()) add("${foods.size} foods")
                if (symptomAssociations.isNotEmpty()) add("${symptomAssociations.size} symptom links")
            }
            return parts.joinToString(", ").ifEmpty { "nothing" }
        }
    }

    // ---- Export -------------------------------------------------------------

    fun exportJson(
        nutrients: List<NutrientDefinitionEntity>,
        symptomAssociations: List<NutrientSymptomEntity>,
        foods: List<FoodKnowledgeEntity>
    ): String = JSONObject()
        .put("kind", KIND)
        .put("version", VERSION)
        .put("exportedAt", java.time.Instant.now().toString())
        .put("nutrients", JSONArray(nutrients.map { defToJson(it) }))
        .put("symptomAssociations", JSONArray(symptomAssociations.map { symToJson(it) }))
        .put("foods", JSONArray(foods.map { foodToJson(it) }))
        .toString(2)

    private fun defToJson(d: NutrientDefinitionEntity) = JSONObject()
        .put("id", d.id).put("name", d.name).put("group", d.group).put("unit", d.unit)
        .put("rdaValue", d.rdaValue ?: JSONObject.NULL)
        .put("ulValue", d.ulValue ?: JSONObject.NULL)
        .put("tier", d.tier)
        .put("deficiencySymptoms", d.deficiencySymptoms ?: JSONObject.NULL)
        .put("excessRisks", d.excessRisks ?: JSONObject.NULL)
        .put("foodSources", d.foodSources ?: JSONObject.NULL)

    private fun symToJson(s: NutrientSymptomEntity) = JSONObject()
        .put("nutrientId", s.nutrientId)
        .put("symptomKey", s.symptomKey)
        .put("displayName", s.displayName)
        .put("kind", s.kind)
        .put("confidence", s.confidence)
        .put("source", s.source)
        .put("researchedAt", s.researchedAt ?: JSONObject.NULL)

    private fun foodToJson(f: FoodKnowledgeEntity) = JSONObject()
        .put("normalizedName", f.normalizedName)
        .put("displayName", f.displayName)
        .put("valuesJson", f.valuesJson)
        .put("highInJson", f.highInJson)
        .put("confidence", f.confidence)
        .put("resolutionMethod", f.resolutionMethod)
        .put("sourceUrlsJson", f.sourceUrlsJson)
        .put("researchedForJson", f.researchedForJson)
        .put("manualLinksJson", f.manualLinksJson)
        .put("firstResearchedAt", f.firstResearchedAt)
        .put("lastResearchedAt", f.lastResearchedAt)
        .put("researchCount", f.researchCount)
        // NOTE: `id` is deliberately NOT exported — ids are per-install;
        // import assigns fresh UUIDs (see FoodKnowledgeRepository.importPack).

    // ---- Import -------------------------------------------------------------

    /** Returns null when [text] is not a Hoot knowledge pack (wrong kind/unparseable). */
    fun parse(text: String): Pack? = runCatching {
        val o = JSONObject(text)
        if (o.optString("kind") != KIND) return@runCatching null
        Pack(
            nutrients = o.optJSONArray("nutrients").mapObj { defFromJson(it) },
            symptomAssociations = o.optJSONArray("symptomAssociations").mapObj { symFromJson(it) },
            foods = o.optJSONArray("foods").mapObj { foodFromJson(it) }
        )
    }.getOrNull()

    private fun defFromJson(o: JSONObject) = NutrientDefinitionEntity(
        id = o.optString("id"),
        name = o.optString("name"),
        group = o.optString("group"),
        unit = o.optString("unit"),
        rdaValue = o.optNullableDouble("rdaValue"),
        ulValue = o.optNullableDouble("ulValue"),
        tier = o.optInt("tier", 3),
        deficiencySymptoms = o.optNullableString("deficiencySymptoms"),
        excessRisks = o.optNullableString("excessRisks"),
        foodSources = o.optNullableString("foodSources")
    )

    private fun symFromJson(o: JSONObject) = NutrientSymptomEntity(
        // Deterministic id — same convention as the seed/researcher rows, so
        // importing the same pack twice converges (upsert on the same key).
        id = "${o.optString("nutrientId")}:${o.optString("kind")}:${o.optString("symptomKey")}",
        nutrientId = o.optString("nutrientId"),
        symptomKey = o.optString("symptomKey"),
        displayName = o.optString("displayName"),
        kind = o.optString("kind"),
        confidence = o.optDouble("confidence", 0.5).coerceIn(0.0, 1.0),
        source = o.optString("source").ifBlank { "SEED" },
        researchedAt = o.optNullableLong("researchedAt")
    )

    private fun foodFromJson(o: JSONObject): FoodKnowledgeEntity? {
        val name = o.optString("normalizedName")
        if (name.isBlank()) return null
        val now = System.currentTimeMillis()
        return FoodKnowledgeEntity(
            id = o.optString("id").ifBlank { java.util.UUID.nameUUIDFromBytes(name.toByteArray()).toString() },
            normalizedName = name,
            displayName = o.optString("displayName").ifBlank { name },
            valuesJson = o.optString("valuesJson").ifBlank { "{}" },
            highInJson = o.optString("highInJson").ifBlank { "[]" },
            confidence = o.optDouble("confidence", 0.5),
            resolutionMethod = o.optString("resolutionMethod").ifBlank { "import" },
            sourceUrlsJson = o.optString("sourceUrlsJson").ifBlank { "[]" },
            researchedForJson = o.optString("researchedForJson").ifBlank { "[]" },
            manualLinksJson = o.optString("manualLinksJson").ifBlank { "[]" },
            firstResearchedAt = o.optNullableLong("firstResearchedAt") ?: now,
            lastResearchedAt = o.optNullableLong("lastResearchedAt") ?: now,
            researchCount = o.optInt("researchCount", 1)
        )
    }

    // ---- JSON helpers ---------------------------------------------------------

    private fun <T> JSONArray?.mapObj(transform: (JSONObject) -> T?): List<T> {
        if (this == null) return emptyList()
        val out = ArrayList<T>(length())
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            transform(o)?.let { out += it }
        }
        return out
    }

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (isNull(key)) null else optDouble(key)

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (isNull(key)) null else optLong(key)
}
