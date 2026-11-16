package com.example.hoot.data.local

import com.example.hoot.data.local.entity.NutrientSymptomEntity

/**
 * Curated nutrient ↔ symptom associations (deficiency system, 2026-11-16).
 *
 * Textbook, high-confidence mappings distilled from the seeded
 * `deficiencySymptoms` / `excessRisks` strings (NIH ODS basis — see
 * NutrientSeed) plus standard nutrition references. Only associations a
 * clinician would call "classic" are included; the research agent
 * (NutrientResearcher) extends coverage per nutrient on demand.
 *
 * symptomKey is the normalized join key across nutrients (snake_case);
 * displayName is the user-facing chip label. Confidence ∈ (0, 1] modulates
 * the likelihood blend in SymptomMatcher.
 */
object SymptomSeed {

    const val KIND_DEFICIENCY = "DEFICIENCY"
    const val KIND_EXCESS = "EXCESS"

    private fun row(
        nutrientId: String, key: String, display: String,
        confidence: Double, kind: String = KIND_DEFICIENCY
    ) = NutrientSymptomEntity(
        id = "$nutrientId:$kind:$key",
        nutrientId = nutrientId,
        symptomKey = key,
        displayName = display,
        kind = kind,
        confidence = confidence,
        source = "SEED",
        researchedAt = null
    )

    /** (key, display, confidence) triple shorthand. */
    private data class S(val key: String, val display: String, val conf: Double)

    private val DEFICIENCY: Map<String, List<S>> = mapOf(
        "vitamin_d" to listOf(
            S("fatigue", "Fatigue", 0.8),
            S("bone_pain", "Bone / joint pain", 0.9),
            S("muscle_weakness", "Muscle weakness", 0.9),
            S("frequent_illness", "Frequent infections", 0.7),
            S("low_mood", "Low mood", 0.6)
        ),
        "vitamin_b12" to listOf(
            S("fatigue", "Fatigue", 0.9),
            S("numbness_tingling", "Numbness / tingling", 0.9),
            S("brain_fog", "Brain fog / poor memory", 0.7),
            S("pale_skin", "Pale skin", 0.5),
            S("breathlessness", "Breathlessness on effort", 0.5)
        ),
        "iron" to listOf(
            S("fatigue", "Fatigue", 0.95),
            S("pale_skin", "Pale skin", 0.7),
            S("breathlessness", "Breathlessness on effort", 0.7),
            S("hair_loss", "Hair loss", 0.6),
            S("restless_legs", "Restless legs", 0.6),
            S("headache", "Headache", 0.5),
            S("cold_hands", "Cold hands & feet", 0.5)
        ),
        "magnesium" to listOf(
            S("muscle_cramps", "Muscle cramps / twitching", 0.9),
            S("insomnia", "Poor sleep", 0.6),
            S("anxiety", "Anxiety / irritability", 0.6),
            S("fatigue", "Fatigue", 0.6),
            S("headache", "Headache", 0.5),
            S("arrhythmia", "Irregular heartbeat", 0.4)
        ),
        "potassium" to listOf(
            S("muscle_weakness", "Muscle weakness", 0.8),
            S("muscle_cramps", "Muscle cramps / twitching", 0.7),
            S("constipation", "Constipation", 0.5),
            S("arrhythmia", "Irregular heartbeat", 0.5),
            S("fatigue", "Fatigue", 0.5)
        ),
        "calcium" to listOf(
            S("muscle_cramps", "Muscle cramps / twitching", 0.8),
            S("numbness_tingling", "Numbness / tingling", 0.6),
            S("brittle_nails", "Brittle nails", 0.5)
        ),
        "zinc" to listOf(
            S("impaired_taste", "Reduced taste / smell", 0.85),
            S("hair_loss", "Hair loss", 0.7),
            S("slow_healing", "Slow wound healing", 0.7),
            S("frequent_illness", "Frequent infections", 0.6)
        ),
        "vitamin_c" to listOf(
            S("fatigue", "Fatigue", 0.7),
            S("bleeding_gums", "Bleeding gums", 0.8),
            S("slow_healing", "Slow wound healing", 0.7),
            S("joint_pain", "Joint pain", 0.5),
            S("easy_bruising", "Easy bruising", 0.6)
        ),
        "vitamin_b9" to listOf(
            S("fatigue", "Fatigue", 0.8),
            S("brain_fog", "Brain fog / poor memory", 0.5),
            S("pale_skin", "Pale skin", 0.5),
            S("breathlessness", "Breathlessness on effort", 0.5)
        ),
        "vitamin_b6" to listOf(
            S("numbness_tingling", "Numbness / tingling", 0.6),
            S("low_mood", "Low mood", 0.5),
            S("brain_fog", "Brain fog / poor memory", 0.5)
        ),
        "vitamin_a" to listOf(
            S("night_blindness", "Poor night vision", 0.9),
            S("dry_eyes", "Dry eyes", 0.7),
            S("frequent_illness", "Frequent infections", 0.6),
            S("dry_skin", "Dry / rough skin", 0.6)
        ),
        "iodine" to listOf(
            S("fatigue", "Fatigue", 0.7),
            S("cold_intolerance", "Feeling cold often", 0.7),
            S("weight_gain_unexplained", "Unexplained weight gain", 0.5),
            S("brain_fog", "Brain fog / poor memory", 0.5),
            S("hair_loss", "Hair loss", 0.4)
        ),
        "omega3_epa_dha" to listOf(
            S("dry_eyes", "Dry eyes", 0.6),
            S("dry_skin", "Dry / rough skin", 0.6),
            S("brain_fog", "Brain fog / poor memory", 0.5),
            S("joint_pain", "Joint pain", 0.4)
        ),
        "vitamin_b1" to listOf(
            S("fatigue", "Fatigue", 0.8),
            S("numbness_tingling", "Numbness / tingling", 0.7),
            S("poor_appetite", "Poor appetite", 0.5)
        ),
        "vitamin_e" to listOf(
            S("numbness_tingling", "Numbness / tingling", 0.5),
            S("muscle_weakness", "Muscle weakness", 0.5)
        ),
        "protein" to listOf(
            S("fatigue", "Fatigue", 0.6),
            S("hair_loss", "Hair loss", 0.5),
            S("frequent_illness", "Frequent infections", 0.5),
            S("slow_healing", "Slow wound healing", 0.5)
        ),
        "fiber" to listOf(
            S("constipation", "Constipation", 0.8),
            S("bloating", "Bloating / gut discomfort", 0.5)
        ),
        "water" to listOf(
            S("headache", "Headache", 0.7),
            S("fatigue", "Fatigue", 0.6),
            S("dark_urine", "Dark urine", 0.8)
        ),
        "selenium" to listOf(
            S("fatigue", "Fatigue", 0.5),
            S("hair_loss", "Hair loss", 0.5),
            S("frequent_illness", "Frequent infections", 0.5)
        ),
        "vitamin_k" to listOf(
            S("easy_bruising", "Easy bruising", 0.7),
            S("bleeding_gums", "Bleeding gums", 0.5)
        ),
        "vitamin_b2" to listOf(
            S("sore_throat_mouth", "Sore mouth / cracked lips", 0.6),
            S("fatigue", "Fatigue", 0.4)
        ),
        "vitamin_b3" to listOf(
            S("dry_skin", "Dry / rough skin", 0.6),
            S("fatigue", "Fatigue", 0.5),
            S("poor_appetite", "Poor appetite", 0.4)
        )
    )

    private val EXCESS: Map<String, List<S>> = mapOf(
        "vitamin_d" to listOf(S("nausea_thirst", "Nausea / excessive thirst", 0.7)),
        "magnesium" to listOf(S("diarrhea", "Diarrhea", 0.8)),
        "sodium" to listOf(
            S("hypertension", "Elevated blood pressure", 0.8),
            S("headache", "Headache", 0.4)
        ),
        "added_sugar" to listOf(
            S("fatigue", "Energy crashes / fatigue", 0.5),
            S("dental_issues", "Dental problems", 0.6)
        ),
        "iron" to listOf(S("gi_distress", "GI upset / nausea", 0.6)),
        "zinc" to listOf(S("impaired_taste", "Metallic / reduced taste", 0.5)),
        "vitamin_c" to listOf(S("gi_distress", "GI upset / nausea", 0.5)),
        "vitamin_b6" to listOf(S("numbness_tingling", "Numbness / tingling", 0.7)),
        "vitamin_a" to listOf(S("headache", "Headache", 0.6)),
        "selenium" to listOf(S("hair_loss", "Hair loss", 0.5)),
        "calcium" to listOf(S("constipation", "Constipation", 0.5)),
        "omega3_epa_dha" to listOf(S("easy_bruising", "Easy bruising / bleeding", 0.5))
    )

    fun seed(): List<NutrientSymptomEntity> =
        DEFICIENCY.flatMap { (id, list) -> list.map { row(id, it.key, it.display, it.conf) } } +
            EXCESS.flatMap { (id, list) ->
                list.map { row(id, it.key, it.display, it.conf, KIND_EXCESS) }
            }

    /** All distinct user-facing symptom keys with display names (UI legend). */
    fun allKeys(): Map<String, String> =
        (DEFICIENCY + EXCESS).values.flatten().associate { it.key to it.display }
}
