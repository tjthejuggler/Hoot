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
        val rawText: String
    )

    // "400 mg", "400mg", "0.5 g", "2,000 IU", "400 mcg"
    private val DOSE_REGEX =
        Regex("(\\d+(?:[.,]\\d+)?)\\s*(mg|mcg|µg|ug|g|iu|ui)\\b", RegexOption.IGNORE_CASE)

    // "Name: dose" — colon always separates a label.
    private val COLON_SPLIT = Regex("^([^:]+):\\s*(.+)$")

    // "Name - dose": dash separates ONLY when the right side starts with a
    // digit, so "Vitamin B-12" / "Omega-3" / "B-Complex" stay intact.
    private val DASH_SPLIT = Regex("^(.+?)\\s+[-—–]\\s+(\\d.*)$")

    fun parse(rawText: String): ParsedSupplement? {
        val text = rawText.replace("\n", " ").trim()
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
            rawText = rawText.trim()
        )
    }

    private fun canonicalDoseUnit(u: String): String = when (u.lowercase()) {
        "ui" -> "iu"                       // French/Spanish UI = IU
        "µg", "ug" -> "mcg"
        else -> u.lowercase()
    }
}
