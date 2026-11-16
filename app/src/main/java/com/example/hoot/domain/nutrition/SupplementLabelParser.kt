package com.example.hoot.domain.nutrition

/**
 * Parses supplement entry text (Tail "Took Pills" labels, native entries).
 *
 * Tolerant patterns handled (docs/ARCHITECTURE.md §5 — supplement labels):
 *   "Magnesium 400 mg"          → name=Magnesium, dose=400 mg
 *   "Vitamin D 2000 IU"         → name=Vitamin D, dose=2000 IU
 *   "Magnesium: 400mg citrate"  → name=Magnesium, dose=400 mg
 *   "Omega-3 (1 g EPA+DHA)"     → name=Omega-3, dose=1 g
 *   "multivitamin"              → name only (dose via LLM later)
 *
 * Pure JVM; LLM assist happens in the resolver when this returns no dose.
 */
object SupplementLabelParser {

    data class ParsedSupplement(
        val name: String,
        val normalizedName: String,
        val doseAmount: Double?,
        val doseUnit: String?,     // "mg" | "mcg" | "g" | "iu" (lowercase)
        val rawText: String,
        /**
         * Canonical nutrient id when the clean name is an UNAMBIGUOUS
         * supplement abbreviation ("D3", "K2", "C", "B12"…). [FoodNormalizer]
         * strips digits ("D3" → "d"), so these short labels previously
         * missed [NutritionResolver.KNOWN_SUPPLEMENT_NUTRIENTS] and their
         * rows sat unresolved until the LLM path — which, unconfigured or
         * rate-capped, burned all 3 attempts and stranded them with empty
         * contributions (vitamin-D-2%-bug, 2026-11-16).
         */
        val nutrientId: String?
    )

    /**
     * Bare-letter supplement abbreviations → canonical nutrient id. Only
     * matched on the EXACT (cleaned) short label, never as substrings, so
     * "Vitamin D" still resolves through the word-based map and food names
     * are unaffected. In a pills-habit context these tokens are unambiguous.
     */
    private val ABBREVIATION_IDS: Map<String, String> = mapOf(
        "d" to "vitamin_d", "d3" to "vitamin_d", "d2" to "vitamin_d",
        "k" to "vitamin_k", "k2" to "vitamin_k", "k1" to "vitamin_k",
        "c" to "vitamin_c",
        "b1" to "vitamin_b1", "b2" to "vitamin_b2", "b3" to "vitamin_b3",
        "b5" to "vitamin_b5", "b6" to "vitamin_b6", "b7" to "vitamin_b7",
        "b9" to "vitamin_b9", "b12" to "vitamin_b12",
        "b" to "vitamin_b12",   // bare "B" in a pill context ≈ B-complex; b12 is the flagship gap
        "zn" to "zinc", "mg" to "magnesium", "ca" to "calcium",
        "fe" to "iron", "se" to "selenium"
    )

    /** Canonical nutrient id for an exact short supplement label, else null. */
    fun nutrientIdFor(cleanName: String): String? =
        ABBREVIATION_IDS[cleanName.trim().lowercase()]

    // "400 mg", "400mg", "0.5 g", "2,000 IU", "400 mcg"
    private val DOSE_REGEX =
        Regex("(\\d+(?:[.,]\\d+)?)\\s*(mg|mcg|µg|ug|g|iu|ui)\\b", RegexOption.IGNORE_CASE)

    // "Name: dose" — colon always separates a label.
    private val COLON_SPLIT = Regex("^([^:]+):\\s*(.+)$")

    // "Name - dose": dash separates ONLY when the right side starts with a
    // digit, so "Vitamin B-12" / "Omega-3" / "B-Complex" stay intact.
    private val DASH_SPLIT = Regex("^(.+?)\\s+[-—–]\\s+(\\d.*)$")

    fun parse(rawText: String): ParsedSupplement? {
        // Fold Greek mu into the micro sign so "25μg" parses like "25µg".
        val text = rawText.replace("\n", " ").replace('μ', 'µ').trim()
        if (text.isEmpty()) return null

        // Prefer a "Name: rest" / "Name - 400 mg" split when present.
        val labeled = COLON_SPLIT.find(text) ?: DASH_SPLIT.find(text)
        var namePart = labeled?.groupValues?.get(1) ?: text
        val dosePart = labeled?.groupValues?.get(2) ?: text

        val doseMatch = DOSE_REGEX.find(dosePart)
            ?: DOSE_REGEX.find(text) // "400 mg Magnesium" order
        var doseAmount: Double? = null
        var doseUnit: String? = null
        if (doseMatch != null) {
            doseAmount = doseMatch.groupValues[1].replace(",", "").toDoubleOrNull()
            doseUnit = canonicalDoseUnit(doseMatch.groupValues[2])
        }

        // Strip the dose phrase from the name part when the dose preceded the name.
        namePart = namePart
            .replace(DOSE_REGEX, " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '—', ':', '(', ')', ',')
        // Parenthetical form "Omega-3 (1 g)" keeps the name clean automatically.
        if (namePart.isBlank()) namePart = text.substringBefore('(').trim()

        val cleanName = namePart.trim()
        if (cleanName.isBlank()) return null
        return ParsedSupplement(
            name = cleanName,
            normalizedName = FoodNormalizer.normalize(cleanName),
            doseAmount = doseAmount,
            doseUnit = doseUnit,
            rawText = rawText.trim(),
            nutrientId = nutrientIdFor(cleanName)
        )
    }

    private fun canonicalDoseUnit(u: String): String = when (u.lowercase()) {
        "ui" -> "iu"                       // French/Spanish UI = IU
        "µg", "ug" -> "mcg"
        else -> u.lowercase()
    }
}
