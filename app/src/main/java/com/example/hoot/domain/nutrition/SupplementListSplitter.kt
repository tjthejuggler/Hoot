package com.example.hoot.domain.nutrition

/**
 * Splits one Tail "Took Pills" text entry (or the native AddSupplement free
 * text) into INDIVIDUAL supplement items before ingestion — the pre-fix
 * pipeline stored only the first item of a multi-item list.
 *
 * Handled shapes (pure JVM, deterministic):
 *   "iron\nvitamin D\nfish oil"           → [iron, vitamin D, fish oil]  (newlines)
 *   "iron, vitamin C, zinc"               → 3 items                      (commas)
 *   "iron; calcium; magnesium"            → 3 items                      (semicolons)
 *   "iron and vitamin D" / "zinc & iron" / "zinc + iron"                 (and / & / +)
 *   "- iron\n* zinc\n• calcium"           → 3 items                      (bullets)
 *   "1. iron\n2) zinc"                    → 2 items                      (numbered)
 *   "Magnesium 400 mg, Vitamin D 2000 IU" → doses stay attached per item
 *
 * Dose-safe guarantees:
 *   "Vitamin B-12", "Omega-3"  → hyphens are never separators (no hyphen rule).
 *   "1,000 IU", "1,5 mg"       → commas inside digit groups are parked on a
 *                                sentinel while splitting and restored verbatim.
 * A single-item input yields a single-item list; blank input yields nothing.
 */
object SupplementListSplitter {

    /** Commas inside digit groups ("1,000", "1,5") — parked while splitting. */
    private val DIGIT_COMMA = Regex("(?<=\\d),(?=\\d)")

    /** Leading list markers: "- ", "* ", "• ", "· ", "– ", "1. ", "2) ", "3] ". */
    private val LIST_PREFIX = Regex("^\\s*(?:[-*•·–—]\\s+|\\d{1,2}[.)\\]]\\s+)")

    /** Hard separators: newline, semicolon, comma. */
    private val HARD_SEPARATOR = Regex("[\\n;,]")

    /**
     * " and " (word-boundary safe: "Band"/"stand" never split), "&" and "+"
     * with optional surrounding whitespace. Normalized to a comma so the hard
     * split handles them uniformly.
     */
    private val AND_SEPARATOR = Regex(
        "\\s+\\band\\b\\s*|\\s*&\\s*|\\s*\\+\\s*",
        RegexOption.IGNORE_CASE
    )

    /** Never occurs in user text; holds a digit-group comma during the split. */
    private const val SENTINEL = "\u0001"

    fun split(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val protectedText = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(DIGIT_COMMA, SENTINEL)
            .replace(AND_SEPARATOR, ",")
        return protectedText
            .split(HARD_SEPARATOR)
            .map(::cleanItem)
            .filter { it.isNotBlank() }
    }

    /** Strips list markers + edge punctuation and restores protected commas. */
    private fun cleanItem(pieceRaw: String): String =
        pieceRaw
            .replace(LIST_PREFIX, "")
            .trim()
            .trimEnd(',', ';', '.')
            .trim()
            .replace(SENTINEL, ",")
}
