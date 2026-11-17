package com.example.hoot.domain.nutrition

import org.json.JSONObject

/**
 * Parses a Tail supplement option DESCRIPTION — the per-serving nutrient
 * composition the user maintains in Tail's "separate nutrient section"
 * (e.g. "Iodine 150mcg" or "Calcium 500mg, Magnesium 200mg, Zinc 5mg,
 * D3 20mcg, K2 200mcg, Copper 1mg, Boron 0.6mg") — into canonical-unit
 * nutrient contributions.
 *
 * Bug this fixes (iodine-drops feedback, 2026-11-17): Tail exposes those
 * descriptions via `/v2/habits/{id}/labels` (feature `option_descriptions`),
 * but Hoot never read them. A bare "Iodine drops" label carries no dose, so
 * the free label parse failed, the LLM fallback burned its attempts, and the
 * row sat with empty `nutrientContributions` — Today/Insights showed 0 iodine
 * (and 0 for EVERY nutrient supplied only through that section) although the
 * data existed in Tail all along.
 *
 * Pure JVM; canonical ids come from [NutrientKeys], unit conversion from
 * [Units.canonicalNutrientAmount] (mg↔mcg↔g ladder, IU rules for vitamins
 * A/D/E, µg/μg/ui folding).
 */
object SupplementCompositionParser {

    /**
     * One composition item: raw nutrient name + amount + unit as written.
     * The unit is folded (µg/μg/ug → mcg, ui → iu) but NOT yet canonical.
     */
    data class ParsedNutrient(val name: String, val amount: Double, val unit: String)

    /** Composition split: commas, semicolons, newlines (Tail catalog style). */
    private val PART_SPLIT = Regex("[,;\\n]")

    /**
     * Commas between digits ("4,5") — decimal commas, normalized to dots
     * BEFORE the item split so they are never cut in half. Same rule as
     * Tail's own PillLogParser.
     */
    private val DIGIT_COMMA = Regex("(?<=\\d),(?=\\d)")


    /**
     * `"<name words> <amount> <unit>"` — the name is one or more
     * letter-leading tokens ("Iodine", "Vitamin B12", "Magnesium Glycinate",
     * "D3", "B12"); the amount is a decimal (dot or comma); the unit is one
     * of the mass/IU tokens, word-bounded.
     */
    private val NUTRIENT_TOKEN = Regex(
        """((?:[A-Za-z][A-Za-z0-9\-]*\s+)+?)(\d+(?:[.,]\d+)?)\s*(µg|μg|mcg|ug|mg|g|iu|ui)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Splits a description into raw nutrient items. Unparseable parts are
     * skipped silently (a description like "Ashwagandha" yields nothing —
     * the pill then counts servings only, never an error).
     */
    fun parseItems(description: String): List<ParsedNutrient> {
        if (description.isBlank()) return emptyList()
        val out = mutableListOf<ParsedNutrient>()
        // Normalize decimal commas FIRST ("Zinc 4,5mg") so the item split
        // never cuts a number in half.
        val protected = description.replace(DIGIT_COMMA, ".")
        for (part in protected.split(PART_SPLIT)) {
            val m = NUTRIENT_TOKEN.find(part) ?: continue
            val amount = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: continue
            out += ParsedNutrient(
                name = m.groupValues[1].trim(),
                amount = amount,
                unit = foldUnit(m.groupValues[3])
            )
        }
        return out
    }

    /**
     * Canonical nutrient id for a composition name, tolerating the spellings
     * Tail descriptions use:
     *  - direct alias hit ("Iodine", "D3", "B12", "DHA", "EPA", "Omega-3");
     *  - a missing "vitamin" prefix ("C 1g" → vitamin_c);
     *  - short pill-context abbreviations ("Zn", "Mg", "Se"…);
     *  - brand suffixes via LONGEST-PREFIX match ("Magnesium Glycinate 150mg"
     *    → magnesium; "Omega 3 Sunday Natural" → omega-3).
     */
    fun nutrientIdFor(name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return null
        NutrientKeys.canonicalId(n)?.let { return it }
        NutrientKeys.canonicalId("vitamin $n")?.let { return it }
        SupplementLabelParser.nutrientIdFor(n)?.let { return it }
        val words = n.split(Regex("\\s+"))
        if (words.size >= 2) {
            for (len in words.size - 1 downTo 1) {
                NutrientKeys.canonicalId(words.take(len).joinToString(" "))
                    ?.let { return it }
            }
        }
        return null
    }

    /**
     * Description → canonical-unit contributions map (nutrient id → amount
     * in the nutrient's canonical unit). Multiple items naming the same
     * nutrient SUM ("DHA 350mg, EPA 180mg" → omega3_epa_dha 530 mg).
     * Untracked names (Boron, Creatine…) and unconvertible units drop out.
     * Empty map = nothing usable (caller leaves the row unresolved).
     */
    fun contributions(description: String, unitsById: Map<String, String>): Map<String, Double> {
        if (description.isBlank() || unitsById.isEmpty()) return emptyMap()
        val acc = LinkedHashMap<String, Double>()
        for (item in parseItems(description)) {
            val id = nutrientIdFor(item.name) ?: continue
            if (id in NutrientKeys.DERIVED_IDS) continue
            val canonicalUnit = unitsById[id] ?: continue
            val canonical = Units.canonicalNutrientAmount(id, item.amount, item.unit, canonicalUnit)
                ?: continue
            acc[id] = (acc[id] ?: 0.0) + canonical
        }
        return acc
    }

    /**
     * Description → contributions JSON (the same `{id: amount}` object shape
     * [SupplementResolver.saveContributions] writes and the aggregator
     * reads), or null when nothing parsed — callers then keep the row in the
     * normal resolver queue.
     */
    fun contributionsJson(description: String, unitsById: Map<String, String>): String? {
        val map = contributions(description, unitsById)
        if (map.isEmpty()) return null
        val json = JSONObject()
        for ((id, amount) in map) json.put(id, amount)
        return json.toString()
    }

    /** µg/μg/ug → mcg; ui → iu (French/Spanish UI); else lowercase. */
    private fun foldUnit(u: String): String = when (u.lowercase()) {
        "µg", "μg", "ug" -> "mcg"
        "ui" -> "iu"
        else -> u.lowercase()
    }
}
