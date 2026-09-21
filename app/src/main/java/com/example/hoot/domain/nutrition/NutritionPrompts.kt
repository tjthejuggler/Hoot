package com.example.hoot.domain.nutrition

import com.example.hoot.data.remote.LlmClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Prompt construction + response parsing for the nutrition resolution engine.
 * Pure JVM (org.json only) — unit-testable. The LLM is asked for a full
 * per-100 g panel over ALL tracked nutrients (ids supplied by the caller from
 * the seeded `nutrient_definitions` table); responses are parsed tolerantly.
 */
object NutritionPrompts {

    /** Minimal nutrient descriptor embedded in prompts. */
    data class NutrientRef(val id: String, val name: String, val unit: String)

    /**
     * Prompt schema hygiene: derived (computed-view) nutrients have no LLM
     * panel value — asking for them invites fabricated numbers.
     */
    private fun panelNutrients(nutrients: List<NutrientRef>): List<NutrientRef> =
        nutrients.filter { it.id !in NutrientKeys.DERIVED_IDS }

    /** Parsed LLM nutrition panel. */
    data class FoodPanel(
        val displayName: String,
        val values: Map<String, Double>,     // nutrientId → per-100 g canonical amount
        val confidence: Double,              // 0-1
        val typicalServingGrams: Double?,    // per-piece/portion gram weight hint
        val imageSearchTerm: String?
    )

    private const val SYSTEM = """
You are a nutrition database API. Reply with ONLY one valid JSON object —
no prose, no markdown fences. Use USDA FoodData Central-grade values.
"""

    /** Per-100 g panel request for a food (cache miss, step (b) of the pipeline). */
    fun foodPanelUserPrompt(foodName: String, rawNutrients: List<NutrientRef>): String {
        val list = panelNutrients(rawNutrients)
            .joinToString("\n") { "- ${it.id} (${it.name}, canonical unit: ${it.unit})" }
        return """
For the food "$foodName", produce a nutrition panel PER 100 g (edible portion,
cooked/plain unless the name implies otherwise).

Include values for every nutrient below that is meaningfully present (>0),
keyed EXACTLY by the id, in the canonical unit. Omit nutrients that are not
applicable for this food (e.g. cholesterol in plants).

Tracked nutrients:
$list

Reply JSON schema:
{
  "food": "<best display name>",
  "per_amount": 100,
  "per_unit": "g",
  "values": {"<nutrient_id>": <number>, ...},
  "confidence": <0.0-1.0 honesty of the whole panel>,
  "typical_serving_grams": <grams of one typical piece/portion, or null>,
  "image_search_term": "<2-4 word image search phrase>"
}
"""
    }

    /**
     * MULTI-food batch request: one LLM call for up to N distinct foods,
     * same full per-100 g schema per food. The reply is a JSON object keyed
     * EXACTLY by the caller's food keys ("panels": { "<key>": {…panel…} }).
     */
    fun foodPanelBatchUserPrompt(
        foods: List<Pair<String, String>>,   // foodKey → display name
        rawNutrients: List<NutrientRef>
    ): String {
        val list = panelNutrients(rawNutrients)
            .joinToString("\n") { "- ${it.id} (${it.name}, canonical unit: ${it.unit})" }
        val items = foods.joinToString("\n") { (key, name) -> "- key \"$key\" → food \"$name\"" }
        return """
For EACH food below, produce a nutrition panel PER 100 g (edible portion,
cooked/plain unless the name implies otherwise). Full 46-nutrient schema for
every food — do not merge or skip foods.

Foods (use the given key EXACTLY as the JSON field name):
$items

Include values for every nutrient below that is meaningfully present (>0),
keyed EXACTLY by the id, in the canonical unit. Omit nutrients that are not
applicable for a food (e.g. cholesterol in plants).

Tracked nutrients:
$list

Reply with COMPACT JSON — one entry per requested key, no extras, no
whitespace padding, round values to 1 decimal:

{"panels":{"<food_key>":{"food":"<display name>","values":{"<nutrient_id>":<number>,...},"confidence":<0.0-1.0>,"typical_serving_grams":<grams or null>,"image_search_term":"<2-4 words>"}, ...}}
"""
    }

    /**
     * MULTI-food supplement batch: one panel PER SERVING per distinct
     * supplement name, keyed by the caller's normalized names.
     */
    fun supplementBatchUserPrompt(
        supplements: List<Pair<String, String>>,  // normalizedKey → display label
        rawNutrients: List<NutrientRef>
    ): String {
        val list = panelNutrients(rawNutrients)
            .joinToString("\n") { "- ${it.id} (${it.name}, canonical unit: ${it.unit})" }
        val items = supplements.joinToString("\n") { (key, name) -> "- key \"$key\" → supplement \"$name\"" }
        return """
For EACH supplement below, identify it and its nutrient contributions PER
SERVING (the whole logged dose), converted to the canonical units below and
keyed EXACTLY by nutrient id. Only include nutrients actually provided.

Supplements (use the given key EXACTLY as the JSON field name):
$items

Tracked nutrients:
$list

Reply JSON schema (one entry per requested key, no extras):
{
  "panels": {
    "<supplement_key>": {
      "supplement": "<best display name>",
      "values": {"<nutrient_id>": <number per serving>, ...},
      "confidence": <0.0-1.0>,
      "image_search_term": "<2-4 word image search phrase>"
    },
    ...
  }
}
"""
    }

    /**
     * Parses a batch reply into (foodKey → panel). Tolerates the documented
     * `{"panels": {key: panel}}` wrapper, a bare `{key: panel}` object, and a
     * JSON array of panels carrying their key as `"key"`/`"food_key"`.
     * Panels that fail to parse individually are simply absent from the map —
     * the caller falls back to single-food calls for exactly those keys.
     */
    fun parsePanelBatch(json: String, knownKeys: Set<String>, knownIds: Set<String>): Map<String, FoodPanel> {
        val out = LinkedHashMap<String, FoodPanel>()
        try {
            val extracted = LlmClient.extractJson(json)
            val panelsObj: JSONObject? = when {
                extracted.trimStart().startsWith("[") -> null
                else -> JSONObject(extracted)
            }
            if (panelsObj != null) {
                val obj = panelsObj.optJSONObject("panels")
                    ?: panelsObj.optJSONObject("foods")
                    ?: panelsObj
                for (key in obj.keys()) {
                    if (knownKeys.isNotEmpty() && key !in knownKeys) continue
                    val panelJson = obj.optJSONObject(key) ?: continue
                    parsePanel(panelJson.toString(), knownIds)?.let { out[key] = it }
                }
            } else {
                // Array fallback: [{"key"/"food_key": "...", ...panel fields}]
                val arr = JSONArray(extracted)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val key = o.optString("key", o.optString("food_key", o.optString("supplement_key", "")))
                    if (key.isBlank() || (knownKeys.isNotEmpty() && key !in knownKeys)) continue
                    parsePanel(o.toString(), knownIds)?.let { out[key] = it }
                }
            }
        } catch (_: Exception) {
            // Total garbage reply → empty map: every key falls back individually.
        }
        return out
    }

    /** Extraction request from fetched web page text (web fallback, step (c)). */
    fun webExtractUserPrompt(
        foodName: String,
        pageUrl: String,
        pageText: String,
        rawNutrients: List<NutrientRef>
    ): String {
        val list = panelNutrients(rawNutrients)
            .joinToString("\n") { "- ${it.id} (${it.name}, canonical unit: ${it.unit})" }
        val clipped = pageText.take(9_000)
        return """
Below is text fetched from <$pageUrl> for the food "$foodName".
Extract a nutrition panel PER 100 g, keyed EXACTLY by these nutrient ids and
canonical units (omit inapplicable nutrients):

$list

Reply JSON schema:
{
  "food": "$foodName",
  "values": {"<nutrient_id>": <number>, ...},
  "confidence": <0.0-1.0 — how directly the text states these numbers>,
  "typical_serving_grams": <grams per typical piece/portion or null>,
  "image_search_term": "<2-4 word image search phrase>"
}

PAGE TEXT:
$clipped
"""
    }

    /** Supplement label → nutrient contributions per serving. */
    fun supplementUserPrompt(labelText: String, rawNutrients: List<NutrientRef>): String {
        val list = panelNutrients(rawNutrients)
            .joinToString("\n") { "- ${it.id} (${it.name}, canonical unit: ${it.unit})" }
        return """
The user logged this supplement: "$labelText".

Identify the supplement and its nutrient contributions PER SERVING (the whole
logged dose). Convert amounts to the canonical units below, keyed EXACTLY by
nutrient id. Only include nutrients actually provided. If the dose is stated
in the label (e.g. "Magnesium 400 mg"), attribute it (elemental amount when
the compound implies it).

Tracked nutrients:
$list

Reply JSON schema:
{
  "supplement": "<best display name>",
  "values": {"<nutrient_id>": <number per serving>, ...},
  "confidence": <0.0-1.0>,
  "image_search_term": "<2-4 word image search phrase>"
}
"""
    }

    /**
     * Parses a food/supplement panel JSON tolerantly (snake/camel keys,
     * `nutrients` alias for `values`). Returns null when unparseable or when
     * no known nutrient amounts survive filtering.
     */
    fun parsePanel(json: String, knownIds: Set<String>): FoodPanel? {
        return try {
            val o = JSONObject(LlmClient.extractJson(json))
            val valuesObj = o.optJSONObject("values") ?: o.optJSONObject("nutrients") ?: JSONObject()
            val values = HashMap<String, Double>()
            // Alias fold at the parse boundary (fiber/iodine bug): LLMs key
            // fiber as "fibre"/"dietary_fiber" and iodine as "iodide" despite
            // the exact-id instruction — map every key to its canonical id
            // HERE so downstream persist/aggregate/display see one spelling.
            for (k in valuesObj.keys()) {
                val id = NutrientKeys.canonicalId(k) ?: continue
                if (knownIds.isNotEmpty() && id !in knownIds) continue
                val v = valuesObj.optDouble(k, Double.NaN)
                if (!v.isNaN() && v.isFinite() && v > 0) values[id] = v
            }
            if (values.isEmpty()) return null
            val display = o.optString("food", o.optString("supplement", "")).ifBlank { null }
            FoodPanel(
                displayName = display ?: "Food",
                values = values,
                confidence = o.optDouble("confidence", 0.5).coerceIn(0.0, 1.0),
                typicalServingGrams = optPositive(o, "typical_serving_grams")
                    ?: optPositive(o, "typical_serving"),
                imageSearchTerm = o.optString("image_search_term", "").ifBlank { null }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun optPositive(o: JSONObject, key: String): Double? {
        val v = o.optDouble(key, Double.NaN)
        return if (!v.isNaN() && v.isFinite() && v > 0) v else null
    }
}
