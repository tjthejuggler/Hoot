package com.example.hoot.domain.nutrition

/**
 * Food-name normalization — plural/synonym folding + lookup-key generation.
 * Pure JVM, unit-testable. The `normalizedName` produced here is the join key
 * for [com.example.hoot.data.local.entity.FoodEntity] and
 * [com.example.hoot.data.local.entity.LookupCacheEntity].
 */
object FoodNormalizer {

    /** Irregular singulars; regulars fall back to suffix rules. */
    private val IRREGULAR = mapOf(
        "leaves" to "leaf", "loaves" to "loaf", "berries" to "berry",
        "tomatoes" to "tomato", "potatoes" to "potato", "heroes" to "hero",
        "mice" to "mouse", "geese" to "goose", "fish" to "fish",
        "shrimp" to "shrimp", "salmon" to "salmon", "rice" to "rice",
        "oats" to "oat", "baked beans" to "baked bean", "greens" to "green",
        "chickpeas" to "chickpea", "lentils" to "lentil", "beans" to "bean",
        "peas" to "pea", "nuts" to "nut", "seeds" to "seed", "eggs" to "egg",
        "oats " to "oat"
    )

    /** Multi-word synonyms folded to the canonical Hoot key. */
    private val SYNONYMS: Map<String, String> = mapOf(
        "aubergine" to "eggplant", "capsicum" to "bell pepper",
        "scallion" to "green onion", "spring onion" to "green onion",
        "garbanzo" to "chickpea", "garbanzo bean" to "chickpea",
        "soybean" to "soy", "soya" to "soy",
        "courgette" to "zucchini", "cilantro" to "coriander",
        "beetroot" to "beet", "rockmelon" to "cantaloupe",
        "prawn" to "shrimp", "cremini" to "cremini mushroom",
        "chick pea" to "chickpea", "sweetcorn" to "corn",
        "maize" to "corn", "ahi tuna" to "tuna", "cacao" to "cocoa"
    )

    /** Descriptor words that don't change nutrition lookups (dropped from the key). */
    private val DESCRIPTORS = setOf(
        "fresh", "frozen", "raw", "cooked", "boiled", "steamed", "roasted",
        "grilled", "baked", "fried", "chopped", "sliced", "diced", "minced",
        "organic", "large", "small", "medium", "whole", "low-fat", "nonfat",
        "full-fat", "ripe", "peeled", "canned", "dried", "unsalted", "salted"
    )

    /**
     * Produces the canonical lookup key: lowercase, descriptor-stripped,
     * singularized, whitespace-collapsed. "2 cups fresh Tomatoes" → "tomato".
     */
    fun normalize(name: String): String {
        var t = name.trim().lowercase()
        // Trim leading quantity/unit tokens (defensive — parser usually removed them).
        t = t.replace(Regex("^[\\d.,/¼-¾\\s]+"), "")
        t = UNIT_STRIP_REGEX.replace(t, " ")
        t = t.replace(Regex("[^a-z\\s-]"), " ")
        // Drop descriptors iteratively (head-first; keep the trailing head noun).
        val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
        while (words.size > 1 && words.first() in DESCRIPTORS) words.removeAt(0)
        val joined = words.joinToString(" ").trim()
        val folded = SYNONYMS[joined] ?: joined
        val singular = singularize(folded)
        // Second synonym pass: plural forms map after singularization
        // ("garbanzo beans" → "garbanzo bean" → "chickpea").
        return (SYNONYMS[singular] ?: singular).trim()
    }

    /** Pretty display name from a user string (no plural folding). */
    fun displayName(name: String): String =
        name.trim().split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { w ->
                if (w.length <= 2 && w == w.uppercase()) w
                else w.replaceFirstChar { c -> c.uppercaseChar() }
            }

    /** Plural → singular (rules + irregular table); idempotent. */
    fun singularize(word: String): String {
        if (word.isBlank()) return word
        IRREGULAR[word]?.let { return it }
        val phrase = word.split(" ")
        if (phrase.size > 1) {
            // Singularize only the head (last) word: "baked beans" handled by table.
            val head = phrase.last()
            val folded = singularizeWord(head)
            if (folded != head) return (phrase.dropLast(1) + folded).joinToString(" ")
            return word
        }
        return singularizeWord(word)
    }

    private fun singularizeWord(w: String): String = when {
        w.length <= 3 -> w
        w.endsWith("ies") && w.length > 4 -> w.dropLast(3) + "y"
        w.endsWith("sses") -> w.dropLast(2)
        w.endsWith("shes") || w.endsWith("ches") || w.endsWith("xes") -> w.dropLast(2)
        w.endsWith("oes") -> w.dropLast(2)
        w.endsWith("ses") && !w.endsWith("ases") -> w.dropLast(2)
        w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is") -> w.dropLast(1)
        else -> w
    }

    private val UNIT_STRIP_REGEX =
        Regex("\\b(g|kg|mg|oz|lb|lbs|ml|l|floz|cup|cups|tbsp|tsp|piece|pieces|slice|slices|clove|cloves|handful|handfuls|pinch|pinches|serving|servings)\\b\\.?", RegexOption.IGNORE_CASE)
}
