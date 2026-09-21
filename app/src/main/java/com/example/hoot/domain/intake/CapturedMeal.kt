package com.example.hoot.domain.intake

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tail-compatible structured meal — the INTERCHANGE CONTRACT between Hoot and
 * the Tail habit tracker (docs/TAIL_REQUEST.md §R2 JSON example, mirroring
 * Tail's internal `FoodData` from `data/meal/MealModels.kt`).
 *
 * Whichever app captures a meal produces this same shape from its LLM call,
 * so neither app needs to redo the analysis:
 *  - title / summary
 *  - estimated calories
 *  - macronutrients (protein / carbs / fat grams)
 *  - ingredientsDetected (searchable tags)
 *  - isVeganVerified (per the user's dietary rules)
 *  - healthNotes, macroRatings (1-3 per macro)
 * Capture-side provenance (voiceTranscript / photo path) is attached by the
 * composer, not by the LLM.
 */
data class CapturedMeal(
    val title: String,
    val summary: String? = null,
    val calories: Int = 0,
    val proteinGrams: Double = 0.0,
    val carbsGrams: Double = 0.0,
    val fatGrams: Double = 0.0,
    val ingredientsDetected: List<String> = emptyList(),
    val isVeganVerified: Boolean = false,
    val healthNotes: String? = null,
    val macroRatings: MacroRatings? = null
) {
    /** True when the LLM produced nothing substantive (no title, no kcal, no foods). */
    val isEmpty: Boolean
        get() = title.isBlank() && calories == 0 && ingredientsDetected.isEmpty()
}

/** Simple 1-3 rating per macro (0 = unset) — identical semantics to Tail's. */
data class MacroRatings(
    val protein: Int = 0,
    val carbs: Int = 0,
    val fat: Int = 0
) {
    val isSet: Boolean get() = protein > 0 || carbs > 0 || fat > 0
}

/**
 * Parses the LLM's meal JSON reply into a [CapturedMeal], tolerating the
 * response shapes seen in the wild (same strategy as Tail's
 * FoodDataJsonParser):
 *
 *  - markdown code fences / preamble around the JSON
 *  - wrapper objects: `{ "food_data": {…} }` (vision schema)
 *  - numeric fields sent as strings ("950 kcal", "28.4")
 *  - `estimated_calories` nested or top-level
 *
 * Returns null only when nothing substantive could be extracted.
 * Pure Kotlin + org.json — unit-testable on the JVM.
 */
object CapturedMealJson {

    fun parse(content: String): CapturedMeal? {
        val candidates = jsonCandidates(content)
        for (candidate in candidates) {
            val obj = runCatching { JSONObject(candidate) }.getOrNull() ?: continue
            // Vision responses wrap food data; text/voice responses don't.
            val fd = obj.optJSONObject("food_data") ?: obj
            val meal = fromFoodData(fd)
            if (meal != null && !meal.isEmpty) return meal
        }
        return null
    }

    /** Builds the model from one food_data-shaped object. Null = no usable fields. */
    internal fun fromFoodData(fd: JSONObject): CapturedMeal? {
        val macros = fd.optJSONObject("macronutrients")
        val ratingsObj = fd.optJSONObject("macro_ratings")
        val ratings = ratingsObj?.let {
            MacroRatings(
                protein = clampRating(it.optInt("protein", 0)),
                carbs = clampRating(it.optInt("carbs", 0)),
                fat = clampRating(it.optInt("fat", 0))
            )
        }?.takeIf { it.isSet }
        val ingredients = fd.optJSONArray("ingredients_detected")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { s -> s.isNotBlank() && s != "null" }
            }.map { it.trim() }
        } ?: emptyList()
        val meal = CapturedMeal(
            title = optText(fd, "title") ?: "",
            summary = optText(fd, "summary"),
            calories = flexibleNumber(fd.opt("estimated_calories")).toInt(),
            proteinGrams = macros?.let { flexibleNumber(it.opt("protein_grams")) } ?: 0.0,
            carbsGrams = macros?.let { flexibleNumber(it.opt("carbs_grams")) } ?: 0.0,
            fatGrams = macros?.let { flexibleNumber(it.opt("fat_grams")) } ?: 0.0,
            ingredientsDetected = ingredients,
            isVeganVerified = fd.optBoolean("is_vegan_verified", false),
            healthNotes = optText(fd, "health_notes"),
            macroRatings = ratings
        )
        return if (meal.title.isBlank() && meal.calories == 0 && ingredients.isEmpty()) null else meal
    }

    // ── Tolerant extraction helpers (Tail FoodDataJsonParser strategy) ──

    /** Ordered candidates: whole fence-stripped text, then the first balanced block. */
    internal fun jsonCandidates(content: String): List<String> {
        val stripped = stripCodeFences(content).trim()
        if (stripped.isEmpty()) return emptyList()
        val out = mutableListOf(stripped)
        val start = stripped.indexOfFirst { it == '{' || it == '[' }
        if (start >= 0) {
            balancedFrom(stripped, start)?.let { if (it != stripped) out += it }
        }
        // An ARRAY of per-food objects merges into one meal (Tail convention).
        return out
    }

    /** Strips ``` fences. */
    internal fun stripCodeFences(text: String): String {
        val t = text.trim()
        if (!t.startsWith("```")) return t
        val after = t.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
        val end = after.lastIndexOf("```")
        return (if (end >= 0) after.substring(0, end) else after).trim()
    }

    /** First balanced {…} / […] block from [start], or null when truncated. */
    internal fun balancedFrom(text: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                    if (depth < 0) return null
                }
            }
        }
        return null
    }

    /** "950", "950 kcal", 950 (int/double), null → double; garbage → 0. */
    internal fun flexibleNumber(v: Any?): Double = when (v) {
        null -> 0.0
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        is String -> Regex("""-?\d+(?:\.\d+)?""").find(v)?.value?.toDoubleOrNull() ?: 0.0
        is JSONArray -> 0.0
        is JSONObject -> 0.0
        else -> 0.0
    }

    private fun optText(obj: JSONObject, key: String): String? =
        obj.optString(key, "").takeIf { it.isNotBlank() && it != "null" }

    private fun clampRating(v: Int): Int = v.coerceIn(0, 3)
}
