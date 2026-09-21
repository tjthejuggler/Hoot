package com.example.hoot.domain.nutrition

/**
 * Splits free-text ingredient strings ("2 eggs, 1 cup rice, spinach",
 * newline-separated Tail entries, "150g lentils") into structured
 * [ParsedIngredient]s. Pure JVM + deterministic — the LLM is only used later,
 * for nutrition data, never for this split (ARCHITECTURE.md §5 step 1).
 */
object IngredientParser {

    /** One normalized ingredient row before persistence. */
    data class ParsedIngredient(
        val rawText: String,
        val foodKey: String,      // normalized lookup key, e.g. "rice"
        val displayName: String,  // pretty, e.g. "Rice"
        val quantity: Double?,    // null = unspecified → portion default later
        val unit: String?         // canonical unit key or null
    )

    /**
     * Parses a whole ingredient string. Splits on `,`, `;`, `+`, newlines —
     * but never inside parenthesised segments. "and" splits only when not
     * part of a food name (heuristic: surrounding spaces and not
     * "and <unit> a half" style fraction phrases).
     */
    fun parse(text: String): List<ParsedIngredient> {
        val cleaned = text.replace("\r", "").trim()
        if (cleaned.isEmpty()) return emptyList()
        return splitSegments(cleaned)
            .mapNotNull(::parseSegment)
            .filter { it.foodKey.isNotBlank() }
    }

    /** Splits the raw text into candidate ingredient segments. */
    internal fun splitSegments(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var parenDepth = 0
        for (c in text) {
            when {
                c == '(' -> { parenDepth++; sb.append(c) }
                c == ')' -> { parenDepth = (parenDepth - 1).coerceAtLeast(0); sb.append(c) }
                parenDepth == 0 && (c == ',' || c == ';' || c == '+' || c == '\n') -> {
                    out += sb.toString(); sb.clear()
                }
                else -> sb.append(c)
            }
        }
        out += sb.toString()
        return out.map { it.trim() }.filter { it.isNotBlank() }
    }

    /**
     * Extracts (quantity, unit, foodName) from one segment.
     * Patterns, in priority order:
     *   "150 g rice" / "150g rice" / "1.5 cups rice" / "2 eggs" /
     *   "½ cup oats" / "1/2 cup oats" / "rice 150 g" / "spinach"
     */
    internal fun parseSegment(segmentRaw: String): ParsedIngredient? {
        var s = segmentRaw.trim()
        if (s.isEmpty()) return null

        // Drop a leading bullet/emoji decor from Tail texts.
        s = s.replace(Regex("^[\\p{So}\\p{C}\\-•*\\s]+"), "").trim()
        if (s.isEmpty()) return null

        // Split attached units early: "150g" → ["150", "g"], "1.5cups" → ["1.5","cups"].
        val tokens = s.split(Regex("\\s+"))
            .flatMap { ATTACHED_UNIT.split(it).filter { p -> p.isNotBlank() } }
        var qty: Double? = null
        var unit: String? = null
        var nameTokens = tokens

        // Pattern A: Q [U] NAME  ("2 eggs", "1.5 cups rice", "150 g lentils",
        // "1 cup rice", "2 1/2 cups flour" → quantity scan of up to 3 tokens)
        var i = 0
        var consumed = 0
        while (i < tokens.size && consumed < 3) {
            val tok = tokens[i]
            val asQty = Units.parseQuantity(tok)
            if (asQty == null) break
            if (qty != null && unit == null) {
                // second number might be "1/2" of a mixed fraction: 1 1/2
                qty = qty!! + asQty
                consumed++
                i++
                continue
            }
            if (qty == null) {
                qty = asQty
                consumed++
                i++
                continue
            }
            break
        }
        // Unit directly after quantity?
        if (qty != null && i < tokens.size) {
            val norm = Units.normalizeUnit(tokens[i])
            if (norm != null) {
                unit = norm
                i++
            }
        }
        if (qty != null) {
            nameTokens = tokens.drop(i)
            // Bare count ("2 eggs") defaults to the count unit; grams come
            // from the per-item hint at aggregation time.
            if (unit == null) unit = "piece"
        }

        // Pattern B: NAME Q U  ("rice 150 g", "eggs x2")
        if (qty == null && tokens.size >= 2) {
            val last = tokens.last()
            val lastQty = Units.parseQuantity(last)
            if (lastQty != null) {
                val prev = tokens[tokens.size - 2]
                val prevUnit = Units.normalizeUnit(prev)
                qty = lastQty
                if (prevUnit != null) {
                    unit = prevUnit
                    nameTokens = tokens.dropLast(2)
                } else {
                    nameTokens = tokens.dropLast(1)
                }
            } else if (last.startsWith("x") || last.startsWith("×")) {
                lastQty2(last)?.let { x2 ->
                    qty = x2
                    nameTokens = tokens.dropLast(1)
                }
            }
        }

        val foodName = nameTokens.joinToString(" ")
            .replace(Regex("\\([^)]*\\)"), " ")   // parenthetical notes
            .replace(Regex("\\s+"), " ")
            .trim()
        if (foodName.isBlank()) return null
        // A pure unit/number segment ("g", "2") is not a food.
        if (Units.normalizeUnit(foodName) != null) return null

        val key = FoodNormalizer.normalize(foodName)
        return ParsedIngredient(
            rawText = segmentRaw.trim(),
            foodKey = key,
            displayName = FoodNormalizer.displayName(foodName),
            quantity = qty,
            unit = unit
        )
    }

    private fun lastQty2(tok: String): Double? = Units.parseQuantity(tok.substring(1).trim())

    /** Splits quantity+unit glued together ("150g", "1.5cups", "2x"). */
    private val ATTACHED_UNIT = Regex("(?<=[\\d¼-¾])(?=[a-zA-Z])")

    /**
     * Combined measure model used by aggregation: resolves an ingredient's
     * gram estimate. Order: exact mass/volume unit → count-unit × per-item
     * hint → portion-default hint → null.
     */
    fun gramsEstimate(
        quantity: Double?,
        unit: String?,
        perItemGrams: Double?,
        portionDefaultGrams: Double?
    ): Double? {
        val q = quantity ?: return portionDefaultGrams
        val u = unit
        Units.toGrams(q, u)?.let { return it }
        if (Units.isCountUnit(u)) {
            val perItem = perItemGrams ?: return portionDefaultGrams
            return q * perItem
        }
        return portionDefaultGrams
    }
}
