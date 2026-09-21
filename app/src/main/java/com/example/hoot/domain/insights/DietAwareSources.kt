package com.example.hoot.domain.insights

/**
 * Central diet-aware food-source resolution — the SINGLE source of truth for
 * "which foods may be suggested to this user" (diet-fix, 2026-09).
 *
 * Every surface that renders food suggestions routes through here:
 *  - [InsightsEngine] GAP / DEFICIENCY "Good sources" text (seeded
 *    `nutrient_definitions.food_sources`),
 *  - [RecommendationEngine] cache + LLM candidate filtering,
 *  - [SmartFoodMatcher]/[SmartFoodProvider] smart picks,
 *  - [CoachNote] prompt restriction wording.
 *
 * Design:
 *  1. Per-restriction EXCLUSION keyword sets ([DietRules]) with word-boundary
 *     matching (so "egg" never excludes "eggplant") and plant-phrase
 *     overrides (so "fortified plant milk" / "peanut butter" / "coconut milk"
 *     survive vegan + dairy-free filters).
 *  2. EMPTY-AFTER-FILTER: when a nutrient's seeded sources are entirely
 *     excluded (or only generic tokens remain, e.g. "supplements for vegans"),
 *     curated per-nutrient × diet-style ALTERNATIVES are substituted — the
 *     alternatives are themselves passed through the user's allergies and
 *     dislikes before display.
 *  3. Pure JVM — unit-testable without Android.
 */

/** Normalized dietary profile carrier (Settings → dietary profile). */
data class DietProfile(
    val dietStyle: String = "omnivore",
    val allergies: List<String> = emptyList(),
    val dislikes: List<String> = emptyList()
)

/** Exclusion keyword sets + deterministic food/diet compatibility checks. */
object DietRules {

    // ---- Exclusion vocabularies (word-boundary matched) ---------------------

    val MEAT_TERMS: Set<String> = setOf(
        "beef", "pork", "chicken", "turkey", "lamb", "veal", "bacon", "ham",
        "sausage", "meat", "steak", "liver", "gelatin", "poultry", "duck",
        "venison", "mutton", "salami", "pepperoni", "prosciutto", "chorizo",
        "jerky", "lard", "broth"
    )
    val FISH_TERMS: Set<String> = setOf(
        "fish", "salmon", "tuna", "sardine", "mackerel", "cod", "anchovy",
        "trout", "herring", "halibut", "shrimp", "prawn", "crab", "lobster",
        "clam", "mussel", "oyster", "scallop", "shellfish", "seafood",
        "squid", "octopus", "eel", "caviar", "roe"
    )
    val EGG_TERMS: Set<String> = setOf(
        "egg", "omelet", "omelette", "mayonnaise", "mayo"
    )
    val DAIRY_TERMS: Set<String> = setOf(
        "milk", "cheese", "yogurt", "yoghurt", "butter", "cream", "whey",
        "casein", "dairy", "ghee", "kefir", "custard"
    )
    /** Vegan = no animal products at all (incl. honey). */
    val ANIMAL_TERMS: Set<String> = MEAT_TERMS + FISH_TERMS + EGG_TERMS + DAIRY_TERMS +
        setOf("honey")
    val HIGH_CARB_TERMS: Set<String> = setOf(
        "bread", "pasta", "rice", "sugar", "candy", "soda", "juice", "potato",
        "cereal", "oat", "wheat"
    )
    val GLUTEN_TERMS: Set<String> = setOf(
        "wheat", "barley", "rye", "spelt", "seitan", "bulgur", "semolina",
        "couscous", "malt", "flour", "bread", "pasta", "cracker", "biscuit",
        "cookie", "cake", "beer"
    )
    val HALAL_TERMS: Set<String> = setOf(
        "pork", "bacon", "ham", "lard", "alcohol", "wine", "beer", "rum",
        "vodka", "whisky", "whiskey", "gin"
    )
    val KOSHER_TERMS: Set<String> = setOf(
        "pork", "bacon", "ham", "lard", "shrimp", "prawn", "crab", "lobster",
        "clam", "mussel", "oyster", "scallop", "shellfish", "crayfish"
    )

    /**
     * Plant-qualified dairy words ("almond milk", "fortified plant milk",
     * "peanut butter", "coconut yogurt", "cocoa butter"…) are plant-based —
     * neutralized before exclusion matching so vegan/dairy-free users still
     * see them. Applied ONLY to style matching; allergy/dislike checks run on
     * the raw text (a soy allergy must exclude "soy milk").
     */
    private val PLANT_QUALIFIED_DAIRY = Regex(
        "(?:almond|soy|soya|oat|oats|coconut|rice|cashew|hemp|peanut|macadamia|" +
            "plant|nut|cocoa|shea)[\\s-]?(milk|yogurt|yoghurt|cream|butter|cheese|drink)s?"
    )

    /**
     * Allergy-chip synonym expansion: a chip like "soy" must also exclude
     * tofu/tempeh/edamame; "nuts" must exclude the common tree nuts. Ordered —
     * the FIRST group whose trigger is contained in the chip wins (so the
     "peanut" chip does not drag in tree-nut synonyms).
     */
    private val ALLERGY_GROUPS: List<Pair<String, List<String>>> = listOf(
        "peanut" to listOf("peanut", "groundnut", "arachis"),
        "shellfish" to listOf(
            "shrimp", "prawn", "crab", "lobster", "clam", "mussel", "oyster",
            "scallop", "crayfish", "shellfish"
        ),
        "soy" to listOf("soy", "soya", "soybean", "tofu", "tempeh", "edamame", "miso"),
        "egg" to listOf("egg", "omelet", "omelette", "mayonnaise", "mayo"),
        "milk" to DAIRY_TERMS.toList(),
        "dairy" to DAIRY_TERMS.toList(),
        "gluten" to GLUTEN_TERMS.toList(),
        "wheat" to GLUTEN_TERMS.toList(),
        "fish" to FISH_TERMS.toList(),
        "sesame" to listOf("sesame", "tahini"),
        "nut" to listOf(
            // "peanut" included so a generic "nuts" chip also kills nut
            // butters (peanut butter); coconut is deliberately NOT listed.
            "almond", "walnut", "cashew", "pistachio", "pecan", "hazelnut",
            "macadamia", "brazil nut", "pine nut", "peanut", "nut"
        )
    )

    /** True when [text] (a food name or fragment) survives every restriction. */
    fun allowsFood(text: String, profile: DietProfile): Boolean =
        allowsFood(text, profile.dietStyle, profile.allergies, profile.dislikes)

    /** Flat-parameter variant for call sites that keep the fields separate. */
    fun allowsFood(
        text: String,
        dietStyle: String,
        allergies: Collection<String>,
        dislikes: Collection<String>
    ): Boolean {
        val raw = text.trim().lowercase()
        if (raw.isEmpty()) return true
        if (dislikes.any { it.isNotBlank() && matchesChip(raw, it) }) return false
        if (allergies.any { it.isNotBlank() && matchesAllergyChip(raw, it) }) return false
        return styleAllows(raw, dietStyle)
    }

    // ---- Style / chip checks (public: RecommendationEngine and
    //      SmartFoodMatcher delegate their per-food gating here) ----------------

    /** Diet-STYLE check only (no allergies/dislikes) on raw food text. */
    fun styleAllows(raw: String, dietStyle: String): Boolean {
        val style = dietStyle.trim().lowercase()
        if (style.isEmpty() || style == "omnivore" || style == "none") return true
        val glutenFreeClaim = raw.contains("gluten-free") || raw.contains("gluten free")
        val terms: Set<String> = when (style) {
            "vegan" -> ANIMAL_TERMS
            "vegetarian" -> MEAT_TERMS + FISH_TERMS
            "pescatarian" -> MEAT_TERMS
            "keto" -> HIGH_CARB_TERMS
            "dairy-free", "lactose-free" -> DAIRY_TERMS
            "gluten-free" -> if (glutenFreeClaim) emptySet() else GLUTEN_TERMS
            "halal" -> HALAL_TERMS
            "kosher" -> KOSHER_TERMS
            else -> return true
        }
        if (terms.isEmpty()) return true
        // Neutralize plant-qualified dairy ("coconut milk" → plant-based) so
        // milk/cream/butter/cheese keywords do not fire on plant foods.
        val normalized = PLANT_QUALIFIED_DAIRY.replace(raw, "plant-based")
        return terms.none { wordIn(normalized, it) }
    }

    /** Dislike chip check: any whitespace token of the chip hits (word-boundary). */
    fun matchesDislike(raw: String, chip: String): Boolean =
        matchesChip(raw, chip)

    /** Allergy chip check: chip tokens OR the chip's synonym group. */
    fun matchesAllergy(raw: String, chip: String): Boolean =
        matchesAllergyChip(raw, chip)

    // ---- Internals -----------------------------------------------------------

    private fun matchesChip(raw: String, chip: String): Boolean =
        chip.trim().lowercase().split(Regex("\\s+")).any { wordIn(raw, it) }

    private fun matchesAllergyChip(raw: String, chip: String): Boolean {
        val c = chip.trim().lowercase()
        if (c.isEmpty()) return false
        if (matchesChip(raw, c)) return true
        val group = ALLERGY_GROUPS.firstOrNull { (trigger, _) -> c.contains(trigger) }
            ?.second ?: return false
        return group.any { wordIn(raw, it) }
    }

    /** Word-boundary match with tolerant plural endings ("egg" → "eggs"). */
    private fun wordIn(text: String, term: String): Boolean =
        Regex("\\b${Regex.escape(term)}(?:s|es)?\\b").containsMatchIn(text)
}

/**
 * Filters seeded `food_sources` strings against a [DietProfile] and provides
 * curated diet-appropriate alternatives when the seed is emptied by the diet.
 */
object DietAwareSources {

    /** Shown when neither filtered sources nor curated alternatives exist. */
    const val EMPTY_PHRASE: String =
        "No default sources match your diet — see the suggestions below"

    /**
     * Seed tokens that name no concrete food ("fortified foods",
     * "supplements for vegans"…) — dropped BEFORE the empty-check so nutrients
     * whose only surviving mention is generic still get real alternatives.
     */
    private val GENERIC_TOKEN = Regex(
        "^(?:fortified\\s+)?(?:foods?|drinks?|supplements?)\\b.*$"
    )

    /**
     * Splits a seed sentence into food tokens: on commas, semicolons, slashes
     * and " and ". The " w/ " shorthand is expanded first so "sardines w/
     * bones" stays ONE token instead of fragmenting into "w" + "bones".
     */
    fun tokenize(sources: String): List<String> =
        sources.replace(" w/ ", " with ")
            .split(",", ";", "/", " and ")
            .map { it.trim().trimEnd('.', ';') }
            .filter { it.isNotEmpty() }

    /**
     * Diet-filtered food-source line for display. Returns the ORIGINAL string
     * when nothing clashes; the filtered list otherwise; curated alternatives
     * (for [nutrientId]) when the diet removes every seeded source.
     */
    fun filterLine(sources: String, profile: DietProfile, nutrientId: String? = null): String {
        if (sources.isBlank()) return sources
        val parts = tokenize(sources)
        if (parts.isEmpty()) return sources
        val kept = parts.filter { DietRules.allowsFood(it, profile) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (kept.size == parts.size) return sources          // nothing dropped
        val concrete = kept.filterNot { GENERIC_TOKEN.matches(it.lowercase()) }
        if (concrete.isNotEmpty()) return concrete.joinToString(", ")
        return alternativesLine(nutrientId, profile)
    }

    /** Curated diet-appropriate alternatives joined for display. */
    fun alternativesLine(nutrientId: String?, profile: DietProfile): String {
        if (nutrientId.isNullOrBlank()) return EMPTY_PHRASE
        val alts = alternativesFor(nutrientId, profile)
        return if (alts.isEmpty()) EMPTY_PHRASE else alts.joinToString(", ")
    }

    // ---- Render-boundary sanitizers (diet-fix hardening, 2026-09) ----------
    //
    // Generation-time filtering ([InsightsEngine.sourcesFor]) is necessary but
    // NOT sufficient: text generated BEFORE a diet change (or persisted in
    // `recommendation_log` by an older build / a constraint-ignoring LLM) can
    // still reach the composables. These sanitizers run on the way OUT to the
    // UI so stale text cannot leak either. Unlike [filterLine] they never
    // substitute curated alternatives — they only remove.

    /** Sentence split: keeps the terminating punctuation on each sentence. */
    private val SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+")

    /**
     * A food-list marker inside a sentence, e.g. "Good sources:" — anchoring
     * on the colon keeps ordinary prose ("a good source of protein") out of
     * tail filtering.
     */
    private val LIST_MARKER = Regex("(?i)(?:good\\s+)?sources?:\\s*")

    /**
     * Render-boundary gate for PROSE insight text: sentences containing a
     * food-list marker ("… Good sources: X, Y") have their tail diet-filtered
     * (violating tokens removed; generic non-food tokens dropped; the tail
     * disappears entirely when nothing survives). All other sentences pass
     * through untouched — pure food LISTS must go through [sanitizeListLine].
     */
    fun sanitizeInsightText(text: String, profile: DietProfile): String {
        if (text.isBlank()) return text
        return SENTENCE_SPLIT.split(text).joinToString(" ") { sentence ->
            val marker = LIST_MARKER.find(sentence) ?: return@joinToString sentence
            val prefix = sentence.substring(0, marker.range.first).trim()
            val tail = sentence.substring(marker.range.last + 1)
            val kept = tokenize(tail)
                .filter { DietRules.allowsFood(it, profile) }
                .filterNot { GENERIC_TOKEN.matches(it.lowercase()) }
            if (kept.isEmpty()) prefix
            else "$prefix ${marker.value.trim()} ${kept.joinToString(", ")}".trim()
        }.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Render-boundary gate for PURE food lists (GAP insight messages, stale
     * persisted suggestion lines): diet-filter the tokens; when nothing
     * survives, [EMPTY_PHRASE]. No alternative substitution happens here —
     * the generation-time pipeline already did that.
     */
    fun sanitizeListLine(sources: String, profile: DietProfile): String {
        if (sources.isBlank()) return sources
        val parts = tokenize(sources)
        if (parts.isEmpty()) return sources
        val kept = parts.filter { DietRules.allowsFood(it, profile) }
            .filterNot { GENERIC_TOKEN.matches(it.lowercase()) }
        return when {
            kept.size == parts.size -> sources
            kept.isNotEmpty() -> kept.joinToString(", ")
            else -> EMPTY_PHRASE
        }
    }

    /**
     * Single render-boundary entry point: prose with a "Good sources:"-style
     * marker is sentence-filtered ([sanitizeInsightText]); everything else is
     * treated as a plain food list ([sanitizeListLine]). GAP insight messages
     * (pure lists) never contain a colon, DEFICIENCY messages always do —
     * the heuristic routes each shape to the right sanitizer.
     */
    fun sanitizeForDisplay(text: String, profile: DietProfile): String =
        if (LIST_MARKER.containsMatchIn(text)) sanitizeInsightText(text, profile)
        else sanitizeListLine(text, profile)

    /**
     * Curated per-nutrient × diet-style alternatives for the nutrients where
     * a restricted diet plausibly empties the omnivore seed (protein, calcium,
     * iron, zinc, iodine, B12, omega-3 EPA/DHA, vitamin D). Every alternative
     * is re-checked against the user's allergies/dislikes before display, so
     * e.g. a soy-allergic vegan never sees tofu. Diets outside
     * vegan/vegetarian/pescatarian keep wide food pools — filtering alone
     * suffices there.
     */
    fun alternativesFor(nutrientId: String, profile: DietProfile): List<String> {
        val style = profile.dietStyle.trim().lowercase()
        if (style !in setOf("vegan", "vegetarian", "pescatarian")) return emptyList()
        val base = ALTERNATIVES[nutrientId]?.get(style) ?: return emptyList()
        return base.filter { DietRules.allowsFood(it, profile) }.distinct()
    }

    // ---- Curated alternatives table ------------------------------------------

    private val ALTERNATIVES: Map<String, Map<String, List<String>>> = mapOf(
        "protein" to mapOf(
            "vegan" to listOf(
                "lentils", "chickpeas", "tofu", "tempeh", "edamame", "seitan",
                "black beans", "quinoa", "hemp seeds", "peanut butter",
                "green peas", "nutritional yeast"
            ),
            "vegetarian" to listOf(
                "eggs", "greek yogurt", "cottage cheese", "milk", "lentils",
                "chickpeas", "tofu", "tempeh", "quinoa", "hemp seeds"
            ),
            "pescatarian" to listOf(
                "salmon", "tuna", "sardines", "cod", "lentils", "chickpeas",
                "tofu", "tempeh", "quinoa", "hemp seeds"
            )
        ),
        "calcium" to mapOf(
            "vegan" to listOf(
                "fortified plant milk", "calcium-set tofu", "kale", "bok choy",
                "almonds", "chia seeds", "tahini", "fortified orange juice",
                "edamame", "broccoli"
            ),
            "vegetarian" to listOf(
                "milk", "yogurt", "cheese", "fortified plant milk",
                "calcium-set tofu", "kale", "bok choy", "almonds"
            ),
            "pescatarian" to listOf(
                "sardines with bones", "salmon with bones",
                "fortified plant milk", "calcium-set tofu", "kale", "bok choy"
            )
        ),
        "iron" to mapOf(
            "vegan" to listOf(
                "lentils", "spinach", "tofu", "pumpkin seeds", "chickpeas",
                "kidney beans", "quinoa", "fortified breakfast cereals"
            ),
            "vegetarian" to listOf(
                "eggs", "lentils", "spinach", "tofu", "pumpkin seeds",
                "chickpeas", "fortified breakfast cereals"
            ),
            "pescatarian" to listOf(
                "clams", "oysters", "sardines", "tuna", "lentils", "spinach",
                "tofu", "pumpkin seeds"
            )
        ),
        "zinc" to mapOf(
            "vegan" to listOf(
                "chickpeas", "pumpkin seeds", "cashews", "lentils", "quinoa",
                "hemp seeds", "oats", "tofu", "fortified breakfast cereals"
            ),
            "vegetarian" to listOf(
                "cheese", "yogurt", "eggs", "chickpeas", "pumpkin seeds",
                "cashews", "lentils"
            ),
            "pescatarian" to listOf(
                "oysters", "crab", "lobster", "chickpeas", "pumpkin seeds",
                "cashews", "lentils"
            )
        ),
        "iodine" to mapOf(
            "vegan" to listOf(
                "iodized salt", "nori", "wakame", "fortified plant milk",
                "fortified bread"
            ),
            "vegetarian" to listOf(
                "milk", "eggs", "iodized salt", "nori", "fortified plant milk"
            ),
            "pescatarian" to listOf(
                "cod", "shrimp", "canned tuna", "iodized salt", "nori"
            )
        ),
        "vitamin_b12" to mapOf(
            "vegan" to listOf(
                "nutritional yeast (fortified)", "fortified plant milk",
                "fortified breakfast cereals", "b12 supplement"
            ),
            "vegetarian" to listOf(
                "eggs", "milk", "yogurt", "cheese",
                "nutritional yeast (fortified)", "fortified plant milk"
            ),
            "pescatarian" to listOf(
                "salmon", "tuna", "sardines", "trout", "clams", "eggs", "milk"
            )
        ),
        "omega3_epa_dha" to mapOf(
            "vegan" to listOf(
                "ground flaxseed", "chia seeds", "walnuts", "hemp seeds",
                "algae oil (epa/dha)"
            ),
            "vegetarian" to listOf(
                "omega-3 fortified eggs", "ground flaxseed", "chia seeds",
                "walnuts", "algae oil (epa/dha)"
            ),
            "pescatarian" to listOf(
                "salmon", "mackerel", "sardines", "trout", "herring"
            )
        ),
        "vitamin_d" to mapOf(
            "vegan" to listOf(
                "uv-exposed mushrooms", "fortified plant milk",
                "fortified orange juice", "fortified breakfast cereals",
                "vitamin d supplement"
            ),
            "vegetarian" to listOf(
                "egg yolks", "fortified milk", "yogurt",
                "uv-exposed mushrooms", "fortified breakfast cereals"
            ),
            "pescatarian" to listOf(
                "salmon", "sardines", "tuna", "mackerel", "herring", "egg yolks"
            )
        )
    )
}
