package com.example.hoot.domain.insights

/**
 * State-aware "what does this mean for me" line for insight cards (feedback:
 * "show common symptoms or effects … based on the user's situation"):
 *  - a DEFICIENCY insight shows the seed's deficiency symptoms,
 *  - an EXCESS insight shows the seed's excess risks.
 *
 * Text is CURATED ONLY — it comes verbatim (truncated) from the seeded
 * `deficiencySymptoms` / `excessRisks` markdown (NutrientSeed →
 * docs/NUTRIENTS.md, NIH ODS basis), never generated or LLM-derived. Pure
 * JVM + unit-testable.
 */
object SymptomCatalog {

    /** Longest rendered effects line before an ellipsis is appended. */
    const val MAX_LEN = 160

    /**
     * First clause of a seed line: split on sentence/semicolon boundaries
     * (seed strings like "Beriberi: fatigue, neuropathy, heart failure;
     * Wernicke (alcohol)" keep only the primary clause), collapse
     * whitespace, cap at [MAX_LEN]. Null/blank-safe.
     */
    fun firstClause(raw: String?): String? {
        val text = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        val clause = text.split(';', '.').first().trim()
        if (clause.isEmpty()) return null
        return if (clause.length <= MAX_LEN) clause
        else clause.take(MAX_LEN - 1).trimEnd() + "…"
    }

    /**
     * The effects line for an insight about [def] given its state ([kind]),
     * or null when the seed carries no text for that state (many nutrients
     * have no meaningful "too little" note — e.g. added sugars).
     */
    fun effectsFor(kind: InsightKind, def: NutrientInsightDef?): String? {
        if (def == null) return null
        val raw = when (kind) {
            InsightKind.DEFICIENCY -> def.deficiencySymptoms
            InsightKind.EXCESS -> def.excessRisks
            else -> return null
        }
        return firstClause(raw)
    }
}
