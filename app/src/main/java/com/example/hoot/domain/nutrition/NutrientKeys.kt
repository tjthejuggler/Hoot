package com.example.hoot.domain.nutrition

/**
 * SINGLE SOURCE OF TRUTH for nutrient-id ↔ panel-key mapping.
 *
 * Bug this fixes: `NutritionPrompts.parsePanel` filtered LLM panel values with
 * EXACT id matching, so panels that keyed fiber as "fibre"/"dietary_fiber" or
 * iodine as "iodide" silently lost those nutrients — they never reached
 * `food_nutrient_profile.valuesJson`, the ledger, History or the 7-day
 * averages (fiber/iodine stuck at 0 while everything else was fine).
 *
 * Every seeded nutrient id (`data/local/NutrientSeed.kt`) MUST appear as a key
 * here, and its own id is always an accepted alias; every alias must belong to
 * exactly ONE canonical id. Both invariants are enforced by
 * `NutrientKeysMappingTest`, so a new seed row can no longer silently drop.
 *
 * Keys are matched after normalization ([normalize]): lowercase, whitespace /
 * hyphen / dot / slash → '_', camelCase split at uppercase letters
 * ("dietaryFiber" → "dietary_fiber").
 */
object NutrientKeys {

    /** Canonical id → accepted panel keys (normalized). Always includes the id itself. */
    val ALIASES: Map<String, Set<String>> = mapOf(
        // ---- Macronutrients --------------------------------------------------
        "protein" to setOf("protein"),
        "carbohydrates" to setOf(
            "carbohydrates", "carbs", "carbohydrate", "total_carbohydrate", "carbohydrates_total"
        ),
        "fiber" to setOf(
            "fiber", "fibre", "dietary_fiber", "dietary_fibre", "total_fiber", "fiber_total"
        ),
        "added_sugar" to setOf("added_sugar", "added_sugars", "sugars_added"),
        "total_fat" to setOf("total_fat", "fat", "fat_total", "total_lipid"),
        "saturated_fat" to setOf("saturated_fat", "sat_fat", "saturated", "fat_saturated"),
        "monounsaturated_fat" to setOf(
            "monounsaturated_fat", "mufa", "fat_monounsaturated"
        ),
        "polyunsaturated_fat" to setOf(
            "polyunsaturated_fat", "pufa", "fat_polyunsaturated"
        ),
        "omega3_epa_dha" to setOf(
            "omega3_epa_dha", "omega_3_epa_dha", "omega_3", "omega3", "epa_dha", "dha_epa"
        ),
        "omega3_ala" to setOf("omega3_ala", "omega_3_ala", "ala", "linolenic_acid"),
        "omega6_la" to setOf("omega6_la", "omega_6_la", "omega_6", "omega6", "linoleic_acid"),
        "trans_fat" to setOf("trans_fat", "trans", "fat_trans"),
        "cholesterol" to setOf("cholesterol"),
        "water" to setOf("water"),
        "calories" to setOf("calories", "calorie", "kcal", "energy", "energy_kcal"),

        // ---- Vitamins ----------------------------------------------------------
        "vitamin_a" to setOf("vitamin_a", "vitamin_a_rae", "retinol"),
        "vitamin_b1" to setOf("vitamin_b1", "thiamin", "thiamine", "b1"),
        "vitamin_b2" to setOf("vitamin_b2", "riboflavin", "b2"),
        "vitamin_b3" to setOf("vitamin_b3", "niacin", "b3"),
        "vitamin_b5" to setOf("vitamin_b5", "pantothenic_acid", "pantothenate", "b5"),
        "vitamin_b6" to setOf("vitamin_b6", "pyridoxine", "b6"),
        "vitamin_b7" to setOf("vitamin_b7", "biotin", "b7"),
        "vitamin_b9" to setOf("vitamin_b9", "folate", "folate_dfe", "folic_acid", "b9"),
        "vitamin_b12" to setOf("vitamin_b12", "cobalamin", "methylcobalamin", "b12"),
        "vitamin_c" to setOf("vitamin_c", "ascorbic_acid", "ascorbate"),
        "vitamin_d" to setOf("vitamin_d", "vitamin_d3", "cholecalciferol", "d3"),
        "vitamin_e" to setOf("vitamin_e", "tocopherol", "alpha_tocopherol"),
        "vitamin_k" to setOf("vitamin_k", "vitamin_k1", "vitamin_k2", "phylloquinone"),

        // ---- Minerals ----------------------------------------------------------
        "calcium" to setOf("calcium", "ca"),
        "phosphorus" to setOf("phosphorus", "phosphorous"),
        "magnesium" to setOf("magnesium", "mag"),
        "sodium" to setOf("sodium", "na"),
        "potassium" to setOf("potassium"),
        "chloride" to setOf("chloride", "cl"),
        "iron" to setOf("iron", "fe"),
        "zinc" to setOf("zinc", "zn"),
        "copper" to setOf("copper", "cu"),
        "manganese" to setOf("manganese", "mn"),
        "fluoride" to setOf("fluoride"),
        "selenium" to setOf("selenium", "se"),
        "iodine" to setOf("iodine", "iodide"),
        "chromium" to setOf("chromium", "cr"),
        "molybdenum" to setOf("molybdenum", "mo"),

        // ---- Other tracked compounds -------------------------------------------
        "lutein_zeaxanthin" to setOf("lutein_zeaxanthin", "lutein", "zeaxanthin"),
        "choline" to setOf("choline"),
        "electrolyte_ratio" to setOf(
            "electrolyte_ratio", "na_k_ratio", "sodium_potassium_ratio"
        )
    )

    /**
     * Derived (computed-view) nutrients: never included in LLM prompts and
     * never expected in a panel — a panel value for these would be junk.
     */
    val DERIVED_IDS: Set<String> = setOf("electrolyte_ratio")

    /** Inverted lookup: normalized panel key → canonical nutrient id. */
    private val BY_KEY: Map<String, String> = buildMap {
        for ((id, keys) in ALIASES) for (k in keys) put(k, id)
    }

    /** Panel key → canonical nutrient id; null for keys no nutrient claims. */
    fun canonicalId(rawKey: String): String? = BY_KEY[normalize(rawKey)]

    /** Normalization: snake_case folding of the raw panel key. */
    fun normalize(rawKey: String): String {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) return ""
        val sb = StringBuilder(trimmed.length + 8)
        for (ch in trimmed) {
            when {
                ch.isWhitespace() || ch == '-' || ch == '.' || ch == '/' -> sb.append('_')
                ch.isUpperCase() -> {
                    if (sb.isNotEmpty() && sb.last() != '_') sb.append('_')
                    sb.append(ch.lowercaseChar())
                }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
