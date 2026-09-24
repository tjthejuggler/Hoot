package com.example.hoot.domain.insights

/**
 * "Smart picks for you" (feature C): multi-nutrient food matching.
 *
 * Given (a) the user's LACKING nutrients with deficits, (b) their EXCESS /
 * limit-tracker nutrients, (c) candidate foods with per-100 g nutrient
 * profiles and typical serving sizes, and (d) dietary restrictions — score
 * each food by how many gaps it covers MINUS how much it feeds what the user
 * already has too much of. Deterministic, cache-only, no LLM required.
 *
 * Pure JVM + unit-testable: all inputs are pre-aggregated plain data, exactly
 * like [FocusNowEngine].
 */

/** One lacking nutrient the matcher should cover. */
data class SmartGap(
    val nutrientId: String,
    val name: String,
    /** 1 critical / 2 important / 3 nice-to-have (drives weight). */
    val tier: Int,
    val unit: String,
    /** Daily target (goal > RDA); 0/invalid gaps are dropped by callers. */
    val target: Double,
    /** Intake so far today → remainingDailyDeficit = target − intake. */
    val todayIntake: Double
) {
    val remainingDeficit: Double get() = (target - todayIntake).coerceAtLeast(0.0)
}

/** One excess / limit-tracker nutrient the matcher must not feed. */
data class SmartExcess(
    val nutrientId: String,
    val name: String,
    /** Daily cap (goal limit > UL > RDA-as-cap). */
    val limit: Double,
    /** Intake so far today (or recent daily average, per caller policy). */
    val intake: Double,
    /**
     * Red-line trackers (sodium, added sugar…) are hard-rejected when a
     * serving alone would exceed [SmartFoodMatcher.RED_LINE_SERVING_SHARE]
     * of the limit while already over — softer otherwise.
     */
    val redLine: Boolean = false
) {
    val overBy: Double get() = (intake - limit).coerceAtLeast(0.0)
    val isOver: Boolean get() = intake > limit
}

/** One candidate food with its resolved per-100 g nutrient panel. */
data class SmartCandidateFood(
    val foodId: String,
    val displayName: String,
    val category: String?,
    /** Typical serving in grams (null → 100 g default). */
    val servingGrams: Double?,
    /** Per-100 g amounts in canonical units (already normalized upstream). */
    val per100: Map<String, Double>,
    /** Deterministic emoji/image fallback (display concern kept out of UI). */
    val emojiHint: String? = null
)

/** Dietary restrictions applied as a deterministic pre-filter. */
data class SmartDietFilter(
    val dietStyle: String = "omnivore",
    val allergies: List<String> = emptyList(),
    val dislikes: List<String> = emptyList()
)

/** Per-nutrient hit detail surfaced in the UI (card summary + sheet). */
data class SmartNutrientHit(
    val nutrientId: String,
    val name: String,
    val unit: String,
    /** min(servingAmount, remainingDeficit) / remainingDeficit, 0..1. */
    val deficitCovered: Double,
    /** Amount in one serving, canonical unit. */
    val servingAmount: Double
)

/** A scored food — the section's per-card model. */
data class SmartFoodPick(
    val foodId: String,
    val displayName: String,
    val category: String?,
    val servingGrams: Double,
    /** Hits sorted by deficit covered descending. */
    val hits: List<SmartNutrientHit>,
    /** 0..1+ aggregate score before diversity selection. */
    val score: Double,
    /** Deterministic cautions, e.g. "watch sodium". */
    val cautions: List<String>,
    /** "cache" (resolved locally) or "llm" (generated enhancement). */
    val source: String,
    /** Why-this one-liner (deterministic template, no LLM). */
    val why: String,
    val emojiHint: String? = null
) {
    /** "3 gaps · magnesium 62%" style summary for the card. */
    fun hitsSummary(): String {
        val top = hits.maxByOrNull { it.deficitCovered } ?: return ""
        return "${hits.size} gap${if (hits.size == 1) "" else "s"} · " +
            "${top.name} ${(top.deficitCovered * 100).toInt()}%"
    }
}

/**
 * The scoring engine. Instantiate per call-site (stateless); configuration
 * knobs are `var`s so tests can tighten/loosen thresholds.
 */
object SmartFoodMatcher {

    // ---- Configuration (tuned defaults; unit tests pin the math) ----------

    /** Tier weight: a critical-gap point is worth 3× a nice-to-have one. */
    const val TIER1_WEIGHT = 1.0
    const val TIER2_WEIGHT = 0.6
    const val TIER3_WEIGHT = 0.3

    /** Excess penalty multiplier (fed nutrients weigh more than covered ones). */
    const val EXCESS_PENALTY = 1.5

    /**
     * Hard-reject threshold: a red-line tracker (sodium…) whose serving
     * alone exceeds this share of its limit WHILE already over caps the
     * food out entirely.
     */
    const val RED_LINE_SERVING_SHARE = 0.15

    /** Non-red-line excess: serving share beyond which a caution is attached. */
    const val CAUTION_SERVING_SHARE = 0.10

    /** Reject foods with fewer meaningful hits than this (single-nutrient picks are fine — see minScore). */
    const val MIN_HITS = 1

    /** Minimum weighted score to keep a pick. */
    const val MIN_SCORE = 0.15

    /**
     * Noise floor for GAP hits (systemic "0%"-card fix, 2026-09-23): a
     * nutrient only counts as a hit when ONE SERVING covers at least this
     * share of the REMAINING daily deficit. Below the floor the serving
     * amount rounds to "0 g / 0%" in the UI while the card still cites the
     * nutrient as a reason — the "nori for potassium/fiber (0%)" and
     * "coconut oil for choline (0%)" absurdities. The excess side has had
     * its noise guard ([CAUTION_SERVING_SHARE]) since the section shipped;
     * this is the gap-side counterpart. 3% of a full day's deficit is a
     * defensible minimum claim; trace nutrients in token amounts (nori's
     * 21 mg potassium vs a 2600 mg deficit = 0.8%) no longer qualify.
     */
    const val MIN_HIT_COVERAGE = 0.03

    /**
     * Diversity: two picks whose PRIMARY hit sets overlap more than this
     * (Jaccard over hit nutrient ids) are considered duplicates.
     */
    const val DIVERSITY_OVERLAP = 0.7

    /** Default serving when the food has no known typical size. */
    const val DEFAULT_SERVING_GRAMS = 100.0

    // Diet term sets were unified into [DietRules] (single source of truth,
    // diet-fix 2026-09) — see [dietAllows] below.

    /**
     * Scores + ranks [foods] against [gaps] while avoiding [excesses].
     * Returns at most [max] picks with diversity de-dup. Empty when the
     * cache is cold — the caller shows the "learns your foods" empty state.
     */
    /**
     * @param minScore quality floor — the "show more" flow lowers it
     *   progressively (feedback 2026-09-21) to surface longer lists.
     */
    fun match(
        gaps: List<SmartGap>,
        excesses: List<SmartExcess>,
        foods: List<SmartCandidateFood>,
        diet: SmartDietFilter = SmartDietFilter(),
        max: Int = 12,
        minScore: Double = MIN_SCORE
    ): List<SmartFoodPick> {
        val usableGaps = gaps.filter { it.remainingDeficit > 0 }
        if (usableGaps.isEmpty() || foods.isEmpty()) return emptyList()

        val gapById = usableGaps.associateBy { it.nutrientId }
        val scored = foods.asSequence()
            .filter { dietAllows(it, diet) }
            .mapNotNull { food -> scoreFood(food, gapById, excesses) }
            .filter { it.hits.size >= MIN_HITS && it.score >= minScore }
            .sortedByDescending { it.score }
            .toList()

        return diverseTop(scored, max)
    }

    // ---- Naming / variant grouping (feedback 2026-09-21) ---------------------
    //
    // Tail-derived food names carry LLM segmentation artifacts ("Plus Seaweed
    // Sheets.", "Vegan Burger And Seaweed (700 Kcal)", "Plus Kombucha. Not
    // Vegan (Nutella Contains Milk).") and sea-vegetable variants arrive as
    // separate foods ("Nori" / "Seaweed" / "Seaweed Sheets") that all hit the
    // same gaps — the diversity pass dedupes identical hit-sets but its
    // relax-fallback re-adds them when the pool runs thin.

    /** Cleans an LLM/Tail display name: parentheticals, "plus" prefix, notes. */
    fun cleanFoodName(raw: String): String {
        var s = raw.replace(Regex("\\s*\\([^)]*\\)"), " ")  // "(700 Kcal)" notes
        s = s.replace(Regex("^[Pp]lus\\s+"), "")            // ingredient-list "Plus …"
        s = s.replace(Regex("\\s+"), " ").trim()
        s = s.substringBefore('.')                          // "Kombucha. Not Vegan …"
        s = s.trim().trimEnd(',', '!', '?').trim()
        return s.ifBlank { raw.trim() }
    }

    /** Keyword groups whose members are variants of ONE food for suggestions. */
    private val VARIANT_GROUPS: List<Set<String>> = listOf(
        setOf("seaweed", "nori", "kombu", "wakame", "kelp", "laver", "algae", "hijiki", "arame", "dulse")
    )

    /**
     * Qualifier words that do NOT differentiate two foods for suggestion
     * purposes (redundancy fix 2026-09-21): "Fortified Almond Milk",
     * "Almond Milk" and "Plus Almond Milk." are ONE recommendation, not
     * three. Processing/preparation/claim words are stripped before the
     * name-signature comparison.
     */
    private val NAME_MODIFIERS: Set<String> = setOf(
        "fortified", "unsweetened", "sweetened", "organic", "raw", "fresh",
        "plain", "canned", "cooked", "steamed", "boiled", "baked", "roasted",
        "dried", "frozen", "sliced", "chopped", "ground", "baby", "whole",
        "low", "fat", "nonfat", "reduced", "large", "small", "medium", "plus",
        // Claim/phrase words seen in Tail meal segmentation (device log
        // 2026-09-21: "Served With Sugar Free Almond Milk" must equal
        // "Almond Milk"). A bare "Sugar" row keeps its identity: stripping
        // every token yields a null signature → treated as unique.
        "served", "with", "sugar", "free", "style", "flavored", "flavoured",
        "made", "blend", "mix", "light", "premium", "classic", "original"
    )

    /**
     * Content identity of a food name for redundancy checks: cleaned,
     * lowercased, qualifier-stripped, singularized, order-normalized token
     * signature. "Fortified Almond Milk" yields "almond milk" — the same key
     * as "Almond Milk". Null when nothing meaningful remains (garbage rows);
     * nulls are treated as unique and never collapse.
     */
    fun nameSignature(displayName: String): String? {
        // Alphanumeric split: digits are content ("vitamin b12", "omega-3",
        // synthetic test names) — dropping them collapsed distinct foods.
        val tokens = cleanFoodName(displayName).lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() && it.any { ch -> ch.isLetter() } && it !in NAME_MODIFIERS }
            .map { singularize(it) }
            .filter { it !in NAME_MODIFIERS && it.length >= 2 }
            .distinct()
            .sorted()
        return tokens.joinToString(" ").ifBlank { null }
    }

    /** Tolerant singular form: berries→berry, eggs→egg, oats→oat. */
    private fun singularize(word: String): String = when {
        word.endsWith("ies") && word.length > 4 -> word.dropLast(3) + "y"
        word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") &&
            word.length > 3 -> word.dropLast(1)
        else -> word
    }

    // ---- Food-name plausibility gate (quality rework 2026-09-22) -------------
    //
    // The candidate pool is every resolved row in the DB, and LLM meal
    // segmentation keeps producing NON-FOOD rows ("Dark Beverage",
    // "A Mixed Meal Of Cucumber With Hummus Plus Granola…"). Those must never
    // surface as recommendations regardless of their nutrient panel.

    /** Vessel/umbrella nouns that name no concrete food. */
    private val GENERIC_HEAD_NOUNS: Set<String> = setOf(
        "beverage", "drink", "meal", "dish", "snack", "food", "item",
        "portion", "serving", "plate", "platter", "bowl", "leftovers",
        "combo", "entree",
        // Segmentation-artifact heads (feedback 2026-09-23 round 2):
        // "Plus Seaweed Sheets" is a meal fragment, not a food; same for
        // packaging/vessel shapes. ("bar" stays out — granola bar is food.)
        "sheet", "spread", "pack", "bag", "box", "bottle", "can", "jar",
        "carton", "wrapper", "leftover"
    )

    /**
     * DISH nouns — composed foods a shopper cannot act on precisely
     * (round 3, feedback 2026-09-23: bare "Sandwich" / "Chicken Sandwich").
     * A recommendation must name an ingredient; these name an assembly.
     * ANY word in the name being one of these rejects it ("Tofu Burger"),
     * because a dish word makes the whole phrase a composed food. Real
     * ingredients ("bread", "tofu", "turkey breast") are unaffected.
     */
    private val DISH_NOUNS: Set<String> = setOf(
        "sandwich", "burger", "wrap", "taco", "burrito", "quesadilla",
        "panini", "hoagie", "sub", "soup", "stew", "curry", "casserole",
        "lasagna", "pizza", "quiche", "omelette", "omelet", "frittata",
        "smoothie", "shake", "parfait", "salad", "sushi", "kebab",
        "potpie", "nugget", "patty"
    )

    /**
     * Meal-occasion words — ANY word in the name being one of these marks a
     * dish/meal title ("Vegan Brunch Spread", "Sunday Dinner leftovers"),
     * never a single purchasable food. Real food names don't carry them.
     */
    private val MEAL_OCCASION_WORDS: Set<String> = setOf(
        "breakfast", "brunch", "lunch", "dinner", "supper", "feast",
        "buffet", "picnic", "barbecue", "barbeque", "bbq", "dessert"
    )

    /** Segmentation/compound-title words — a real SINGLE food name has none. */
    private val TITLE_CONNECTORS: Set<String> = setOf(
        "served", "with", "topped", "alongside", "plus", "and", "along",
        "side", "mixed", "assorted"
    )

    /**
     * True when [raw] looks like a single concrete food a nutrition app can
     * responsibly recommend: ≤ 4 words, no compound-title connectors, no
     * meal-occasion word, and a non-generic head noun ("Dark Beverage" and
     * "Vegan Brunch Spread" fail; "Dark chocolate" passes).
     *
     * Combination rejection (feedback 2026-09-24): recommendations must be
     * SINGLE ingredients — "Ginger & Carrots" is two foods, not one
     * purchasable item. List separators (`&`, `+`, `,`, `;`) in the cleaned
     * name reject it. (Real food names never carry them; "and" was already
     * covered by [TITLE_CONNECTORS].)
     */
    fun isPlausibleFoodName(raw: String): Boolean {
        val cleaned = cleanFoodName(raw)
        val words = cleaned.split(Regex("[^A-Za-z0-9]+"))
            .filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 4) return false
        if (Regex("[&+,;]").containsMatchIn(cleaned)) return false
        val lower = words.map { it.lowercase() }
        if (lower.any { it in TITLE_CONNECTORS }) return false
        if (lower.any { it in MEAL_OCCASION_WORDS }) return false
        // Dish check (round 3): ANY dish noun — singularized so "Sandwiches"
        // and "Burrito Bowl"-style qualifiers die too — makes the phrase a
        // composed food, whatever position it sits in.
        if (lower.any { matchesDishNoun(it) }) return false
        return singularize(lower.last()) !in GENERIC_HEAD_NOUNS
    }

    /**
     * Dish-noun match tolerant of the pluralizer's blind spot: "sandwiches"
     * → "sandwiche" (single-s drop), so the check also tries the
     * trailing-"e"-stripped form before giving up.
     */
    private fun matchesDishNoun(word: String): Boolean {
        val singular = singularize(word)
        if (singular in DISH_NOUNS) return true
        return singular.endsWith("e") && singular.dropLast(1) in DISH_NOUNS
    }

    /**
     * Canonical identity of a suggestion — the ONE dedupe key every surface
     * shares (repetition fix round 2, 2026-09-23: "Seaweed", "Plus Seaweed
     * Sheets" and "Nori" rendered as three separate carousel cards because
     * raw-string equality saw three different names). Combines artifact
     * cleaning, variant-family collapse ("seaweed|nori|kelp|wakame" → one
     * family key) and qualifier-stripped name signatures ("Fortified Almond
     * Milk" ≡ "Almond Milk"). Null when nothing meaningful remains; callers
     * treat nulls as unique.
     */
    fun suggestionIdentity(displayName: String): String? {
        val cleaned = cleanFoodName(displayName)
        variantKey(cleaned)?.let { return "family:$it" }
        return nameSignature(cleaned)
    }

    /** "Nori" / "Seaweed Sheets" / "Wakame" → the same group key; null = unique. */
    fun variantKey(displayName: String): String? {
        val words = displayName.lowercase().split(Regex("[^a-z]+")).filter { it.isNotBlank() }
        return VARIANT_GROUPS.firstOrNull { g -> words.any { it in g } }
            ?.sorted()
            ?.joinToString("|")
    }

    /**
     * Form/processing words that change how a food is SOLD, not what it IS
     * (feedback 2026-09-24 "carrots in some form or another"): "Carrots",
     * "Grated Carrot" and "Carrot Juice" are ONE recommendation. The core-
     * ingredient key is the singularized last word, stepping over one
     * trailing form word; "Olive Oil" → "olive" and "Canola Oil" → "canola"
     * stay distinct because the form word is skipped, not the head.
     */
    private val FORM_WORDS: Set<String> = setOf(
        "juice", "oil", "milk", "powder", "butter", "chip", "chips",
        "flake", "flakes", "extract", "concentrate", "pulp", "puree",
        "syrup", "sauce", "paste", "stick", "sticks", "strip", "strips",
        "cube", "cubes", "slice", "slices", "tea", "drink", "water",
        "capsule", "tablet", "supplement"
    )

    /**
     * Core-ingredient identity for selection diversity ("some form of X" —
     * user feedback 2026-09-24): the singularized last meaningful word,
     * stepping over ONE trailing form word. "Carrot Juice" and "Grated
     * Carrot" share the key "carrot"; "Olive Oil" and "Canola Oil" yield
     * "olive"/"canola". Null when nothing meaningful remains (nulls are
     * treated as unique, mirroring [nameSignature]).
     */
    fun coreIngredientKey(displayName: String): String? {
        val words = cleanFoodName(displayName).lowercase()
            .split(Regex("[^a-z0-9]+"))   // digits are content (nameSignature rule)
            .filter { it.isNotBlank() && it !in NAME_MODIFIERS }
            .map { singularize(it) }
            .filter { it !in NAME_MODIFIERS }
        if (words.isEmpty()) return null
        val last = words.last()
        return if (words.size > 1 && last in FORM_WORDS) words[words.size - 2] else last
    }

    // ---- Scoring ------------------------------------------------------------

    /** Weighted coverage sum − weighted excess penalty; null = no hits at all. */
    private fun scoreFood(
        food: SmartCandidateFood,
        gaps: Map<String, SmartGap>,
        excesses: List<SmartExcess>
    ): SmartFoodPick? {
        val serving = (food.servingGrams?.takeIf { it > 0 } ?: DEFAULT_SERVING_GRAMS)
        val grams = serving / 100.0

        // ---- Gap coverage --------------------------------------------------
        val hits = ArrayList<SmartNutrientHit>()
        var positive = 0.0
        for (gap in gaps.values) {
            val per100 = food.per100[gap.nutrientId] ?: continue
            if (per100 <= 0) continue
            val servingAmount = per100 * grams
            val covered = (servingAmount / gap.remainingDeficit).coerceIn(0.0, 1.0)
            // Noise floor: a serving must move the needle on the remaining
            // deficit for the nutrient to be CITED as a reason (see
            // [MIN_HIT_COVERAGE]). Without this, any per100 > 0 qualified
            // and trace amounts surfaced as "… 0%" hit rows.
            if (covered < MIN_HIT_COVERAGE) continue
            hits += SmartNutrientHit(
                nutrientId = gap.nutrientId, name = gap.name, unit = gap.unit,
                deficitCovered = covered, servingAmount = servingAmount
            )
            positive += covered * tierWeight(gap.tier)
        }
        if (hits.isEmpty()) return null
        hits.sortByDescending { it.deficitCovered }

        // ---- Excess penalty / red-line reject ------------------------------
        var penalty = 0.0
        val cautions = ArrayList<String>()
        for (ex in excesses) {
            val per100 = food.per100[ex.nutrientId] ?: continue
            if (per100 <= 0) continue
            val servingAmount = per100 * grams
            val share = if (ex.limit > 0) servingAmount / ex.limit else 0.0
            if (ex.redLine && ex.isOver && share > RED_LINE_SERVING_SHARE) {
                return null   // hard reject: red-line tracker already over + big serving share
            }
            // Only meaningful contributions count (noise guard).
            if (share >= CAUTION_SERVING_SHARE) {
                penalty += share * EXCESS_PENALTY
                cautions += "watch ${ex.name}"
            } else if (ex.isOver && share > 0) {
                penalty += share * EXCESS_PENALTY
            }
        }

        val score = (positive - penalty).coerceAtLeast(0.0)
        if (score <= 0.0) return null

        return SmartFoodPick(
            foodId = food.foodId,
            displayName = food.displayName,
            category = food.category,
            servingGrams = serving,
            hits = hits,
            score = score,
            cautions = cautions.distinct(),
            source = "cache",
            why = whyLine(food.displayName, hits, serving),
            emojiHint = food.emojiHint
        )
    }

    /** Deterministic "why" template — no LLM involved. */
    private fun whyLine(name: String, hits: List<SmartNutrientHit>, serving: Double): String {
        val top = hits.take(2)
        val joiner = top.joinToString(" and ") {
            "${it.name} (${(it.deficitCovered * 100).toInt()}% of today's gap)"
        }
        val gapWord = if (hits.size == 1) "gap" else "gaps"
        return "$name covers $joiner in one ${serving.toInt()} g serving — " +
            "hits ${hits.size} of your $gapWord."
    }

    private fun tierWeight(tier: Int): Double = when (tier) {
        1 -> TIER1_WEIGHT
        2 -> TIER2_WEIGHT
        else -> TIER3_WEIGHT
    }

    // ---- Diversity -----------------------------------------------------------

    /**
     * Greedy top-[max] with identity + hit-set dedupe. Two collapses run
     * BEFORE selection (redundancy fix 2026-09-21, "3× almond milk"):
     *  - name signatures ("Fortified Almond Milk" ≡ "Almond Milk" ≡
     *    "Plus Almond Milk.") — one representative per signature;
     *  - variant families ("Nori"/"Seaweed"/"Wakame") — one representative
     *    per family.
     * Collapsed siblings can therefore never re-enter via the
     * homogeneous-pool backfill, which was the old leak: the final filter
     * ran AFTER backfill, so skipped duplicates were re-added verbatim.
     * Hit-set Jaccard dedupe ([DIVERSITY_OVERLAP]) still applies on top for
     * differently-named foods covering the same gaps.
     */
    private fun diverseTop(scored: List<SmartFoodPick>, max: Int): List<SmartFoodPick> {
        if (scored.isEmpty() || max <= 0) return emptyList()
        // OOM fix (2026-09-21): the "see all" path calls match(..., Int.MAX_VALUE)
        // and this pre-size used to allocate an Int.MAX_VALUE-sized backing
        // array (~8.6 GB → instant OutOfMemoryError), which crashed every
        // refresh and left Home stuck on the cold-cache placeholder. Capacity
        // can never exceed the actual candidate count.
        val picked = ArrayList<SmartFoodPick>(minOf(max, scored.size))
        val skipped = ArrayList<SmartFoodPick>()

        // Pre-selection identity collapse (score-desc input keeps the best).
        val seenSignatures = HashSet<String>()
        val seenVariantFamilies = HashSet<String>()
        val collapseCores = HashSet<String>()
        val candidates = ArrayList<SmartFoodPick>(scored.size)
        for (cand in scored) {
            val sig = nameSignature(cand.displayName)
            if (sig != null && !seenSignatures.add(sig)) continue
            val fam = variantKey(cand.displayName)
            if (fam != null && !seenVariantFamilies.add(fam)) continue
            // Core-ingredient collapse (user feedback 2026-09-24): "Carrots",
            // "Grated Carrot" and "Carrot Juice" share ONE core key — the
            // user wants carrots at most once, in any form. Applied BEFORE
            // selection so form-variants cannot win different slots. Genuinely
            // different foods (kale/spinach/chard) keep distinct cores and are
            // unaffected.
            val core = coreIngredientKey(cand.displayName)
            if (core != null && !collapseCores.add(core)) continue
            candidates += cand
        }

        val pickedCores = HashSet<String>()
        for (cand in candidates) {
            if (picked.size >= max) break
            val candIds = cand.hits.map { it.nutrientId }.toSet()
            val dup = picked.any { p ->
                val pIds = p.hits.map { it.nutrientId }.toSet()
                jaccard(candIds, pIds) > DIVERSITY_OVERLAP
            }
            if (dup) skipped += cand
            else {
                picked += cand
                coreIngredientKey(cand.displayName)?.let(pickedCores::add)
            }
        }
        // Pool too homogeneous → relax hit-set diversity rather than return
        // fewer. The backfill still honors the core guarantee — a core may
        // only (re-)enter when NO picked food has it, so "carrots in some
        // form" can never exceed one card regardless of pool depth. Distinct
        // cores (the Jaccard-skipped leafy greens) backfill as before.
        var si = 0
        while (picked.size < max && si < skipped.size) {
            val cand = skipped[si++]
            val core = coreIngredientKey(cand.displayName)
            if (core != null && core in pickedCores) continue
            picked += cand
            core?.let(pickedCores::add)
        }
        return picked
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val inter = a.intersect(b).size
        val union = a.union(b).size
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    // ---- Diet filter (delegates to the central [DietRules] heuristics) -------

    fun dietAllows(food: SmartCandidateFood, diet: SmartDietFilter): Boolean {
        if (!DietRules.allowsFood(
                food.displayName, diet.dietStyle, diet.allergies, diet.dislikes
            )
        ) return false
        val c = (food.category ?: "").lowercase()
        return c.isEmpty() ||
            DietRules.allowsFood(c, diet.dietStyle, diet.allergies, emptyList())
    }
}
