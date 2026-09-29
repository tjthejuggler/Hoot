package com.example.hoot.domain.nutrition

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure derivation of the "what is this food HIGH in" summary (food-knowledge
 * base, feedback 2026-09-27). Given a per-100 g panel and the nutrient
 * definitions' RDA/UL, computes the ordered list of nutrient ids the food is
 * notably RICH in — the queryable index that lets the user amass a private
 * "food → rich-in" database over time.
 *
 * Pure JVM + deterministic + unit-testable (no Android types).
 */
object FoodKnowledgeIndex {

    /**
     * A food counts as "high in" a nutrient when 100 g covers at least this
     * share of the nutrient's daily target (RDA, goal-less). 0.15 ≈ the
     * FDA "good source" ballpark (10-19% DV) — defensible and stable.
     */
    const val HIGH_RDA_SHARE_PER_100G = 0.15

    /**
     * Fallback when a nutrient has no usable RDA: relative ranking against
     * typical whole-food density (per-100 g values in canonical units).
     * A food is "high in" X when its value reaches this multiple of the
     * reference density.
     */
    const val HIGH_DENSITY_FACTOR = 3.0

    /** Rough per-100 g densities of a typical mixed diet (canonical units). */
    private val TYPICAL_DENSITY: Map<String, Double> = mapOf(
        "protein" to 12.0, "carbohydrates" to 25.0, "total_fat" to 10.0,
        "fiber" to 2.5, "calories" to 150.0,
        "vitamin_c" to 15.0, "vitamin_d" to 1.0, "vitamin_b12" to 0.5,
        "vitamin_b6" to 0.25, "vitamin_b9" to 50.0, "vitamin_a" to 150.0,
        "vitamin_e" to 1.5, "vitamin_k" to 60.0,
        "calcium" to 100.0, "iron" to 1.5, "magnesium" to 30.0,
        "potassium" to 300.0, "zinc" to 1.2, "selenium" to 15.0,
        "iodine" to 20.0, "sodium" to 300.0,
        "omega3_epa_dha" to 0.2, "choline" to 30.0
    )

    /**
     * Derives the high-in list. RDA-known nutrients: 100 g must cover
     * ≥ [HIGH_RDA_SHARE_PER_100G] of the daily target. RDA-less nutrients:
     * the panel value must reach [HIGH_DENSITY_FACTOR]× the typical mixed
     * diet's per-100 g density. Returns ids richest-first (deterministic).
     */
    fun highIn(
        per100: Map<String, Double>,
        dailyTarget: Map<String, Double>
    ): List<String> {
        val out = ArrayList<Pair<String, Double>>()
        for ((nutrientId, value) in per100) {
            if (value <= 0.0) continue
            val target = dailyTarget[nutrientId]
            val qualifies: Boolean
            val rank: Double
            if (target != null && target > 0.0) {
                val ratio = value / target
                qualifies = ratio >= HIGH_RDA_SHARE_PER_100G
                rank = ratio
            } else {
                val typical = TYPICAL_DENSITY[nutrientId] ?: continue
                val factor = value / typical
                qualifies = factor >= HIGH_DENSITY_FACTOR
                rank = factor / HIGH_DENSITY_FACTOR * HIGH_RDA_SHARE_PER_100G
            }
            if (qualifies) out += nutrientId to rank
        }
        // Richest first; id tiebreak keeps determinism.
        return out.sortedWith(compareByDescending<Pair<String, Double>> { it.second }
            .thenBy { it.first }).map { it.first }
    }

    /** Serializes the high-in list to a compact JSON array string. */
    fun toJson(ids: List<String>): String {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        return arr.toString()
    }

    /** Serializes a per-100 g panel to a compact JSON object string. */
    fun valuesToJson(per100: Map<String, Double>): String {
        val obj = JSONObject()
        for ((k, v) in per100) obj.put(k, v)
        return obj.toString()
    }

    /** Parses a stored high-in JSON array; tolerant of null/blank/invalid. */
    fun fromJson(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    /** Parses a stored per-100 g values JSON object; tolerant of garbage. */
    fun valuesFromJson(raw: String): Map<String, Double> {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, Double>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.optDouble(k, Double.NaN)
            if (!v.isNaN() && v > 0) out[k] = v
        }
        return out
    }
}
