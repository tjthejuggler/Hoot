package com.example.hoot.domain.intake

/**
 * Prompt structure for the Intake capture pipeline — patterned EXACTLY on
 * Tail's `VisionProcessingService.processMealText` system prompt (the same
 * prompt that produces `ingredients_detected` + macros there), so both apps
 * elicit the same structured meal from the same utterance.
 *
 * Pure Kotlin — unit-testable on the JVM.
 */
object CapturePrompts {

    /**
     * System prompt for text / voice / photo captures. Mirrors Tail's
     * processMealText prompt: BEST-GUESS portions, single combined meal JSON,
     * searchable lowercase tags, 1-2 sentence notes. [hasPhoto] appends the
     * "photo attached — what you SEE + what the user SAID" instruction.
     * [dietaryRules] injects the user's dietary profile (Tail's
     * USER DIETARY RULES block).
     */
    fun mealSystemPrompt(hasPhoto: Boolean, dietaryRules: String?): String = buildString {
        append("You are a nutritional analysis assistant. The user briefly described ")
        append("a meal they ate, in their own words. Extract structured nutrition data ")
        append("with your BEST-GUESS estimates for portion sizes. Honour any dietary ")
        append("rules the user mentions.\n")
        val rules = dietaryRules?.trim().orEmpty()
        if (rules.isNotEmpty()) {
            append("\nUSER DIETARY RULES (apply strictly):\n")
            append(rules)
            append("\n")
        }
        if (hasPhoto) {
            append("A photo of the meal is attached: combine what you SEE in the photo ")
            append("with what the user SAID — the description takes priority for ")
            append("quantities and ingredients they name explicitly.\n")
        }
        append("\n")
        append("The description may name SEVERAL foods eaten together (e.g. \"vegan ")
        append("burger and fries and salad\"). Treat them as ONE meal and return ONE ")
        append("SINGLE JSON object for the whole meal: a combined title, SUMMED ")
        append("calories and macros, and every food listed in ingredients_detected. ")
        append("NEVER return an array, multiple JSON objects, or one object per food.\n")
        append("Keep summary and health_notes to 1-2 short sentences.\n")
        append("\n")
        append("Respond ONLY with raw JSON (no markdown fences, no conversational text):\n")
        append("{\n")
        append("  \"title\": \"Short meal name\",\n")
        append("  \"summary\": \"1-2 sentence description\",\n")
        append("  \"is_vegan_verified\": boolean,\n")
        append("  \"estimated_calories\": number,\n")
        append("  \"macronutrients\": { \"protein_grams\": number, \"carbs_grams\": number, \"fat_grams\": number },\n")
        append("  \"ingredients_detected\": [\"ingredient tag\", ...],\n")
        append("  \"health_notes\": \"String or null\",\n")
        append("  \"macro_ratings\": { \"protein\": 1-3, \"carbs\": 1-3, \"fat\": 1-3 }\n")
        append("}\n\n")
        append("macro_ratings: 1 = low, 2 = moderate, 3 = high, relative to the meal's size.\n")
        append("ingredients_detected: individual searchable TAGS (lowercase, singular where natural).")
    }

    /** User turn text — Tail wraps the transcript the same way. */
    fun mealUserText(description: String): String = "Meal description: \"$description\""

    /** Dietary-profile row → the rules block injected above. Null = none. */
    fun dietaryRulesLine(style: String?, allergies: Collection<String>): String? {
        val parts = mutableListOf<String>()
        val s = style?.trim().orEmpty()
        if (s.isNotEmpty() && s != "omnivore") parts.add("Diet style: $s")
        if (allergies.isNotEmpty()) parts.add("Allergies (strictly exclude): ${allergies.joinToString(", ")}")
        return parts.joinToString("; ").ifBlank { null }
    }
}
