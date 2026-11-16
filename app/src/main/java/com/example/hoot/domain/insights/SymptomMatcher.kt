package com.example.hoot.domain.insights

/**
 * Symptom ranking (deficiency system, 2026-11-16). Pure JVM.
 *
 * Combines [DeficiencyModel.Assessment] probabilities with nutrient ↔
 * symptom associations. A symptom's likelihood grows with:
 *   - each contributing nutrient's deficiency likelihood (diminishing —
 *     noisy-OR: 1 - Π(1 - p·conf)),
 *   - the strength (confidence) of each association.
 *
 * "Very likely" multi-nutrient symptoms (fatigue from iron+B12+D) rank
 * above single-nutrient rarities, exactly as requested. Pure inputs →
 * deterministic output → unit-testable.
 */
object SymptomMatcher {

    /** One nutrient ↔ symptom association row (DB entity flattened). */
    data class Association(
        val nutrientId: String,
        val symptomKey: String,
        val displayName: String,
        val kind: String,               // DEFICIENCY | EXCESS
        val confidence: Double          // 0..1
    )

    data class RankedSymptom(
        val symptomKey: String,
        val displayName: String,
        /** 0..1 likelihood the user currently experiences this. */
        val likelihood: Double,
        /** Contributors sorted by deficiency likelihood, strongest first. */
        val contributors: List<Contributor>
    )

    data class Contributor(
        val nutrientId: String,
        val nutrientName: String,
        val deficiencyLikelihood: Double,
        val associationConfidence: Double,
        val unit: String,
        val target: Double
    )

    /** Noisy-OR combination of independent causes. */
    private fun noisyOr(acc: Double, p: Double): Double = 1.0 - (1.0 - acc) * (1.0 - p)

    fun rank(
        deficiencies: List<DeficiencyModel.Assessment>,
        associations: List<Association>,
        kind: String = "DEFICIENCY",
        max: Int = 12
    ): List<RankedSymptom> {
        val byId = deficiencies.associateBy { it.nutrientId }
        val bySymptom = LinkedHashMap<String, MutableList<Contributor>>()
        val displays = HashMap<String, String>()
        for (a in associations) {
            if (a.kind != kind) continue
            val def = byId[a.nutrientId] ?: continue
            if (def.likelihood <= 0.02) continue           // noise floor
            bySymptom.getOrPut(a.symptomKey) { mutableListOf() } += Contributor(
                nutrientId = def.nutrientId,
                nutrientName = def.name,
                deficiencyLikelihood = def.likelihood,
                associationConfidence = a.confidence,
                unit = def.unit,
                target = def.target
            )
            displays.putIfAbsent(a.symptomKey, a.displayName)
        }
        return bySymptom.map { (key, contributorsRaw) ->
            val contributors = contributorsRaw.sortedByDescending { it.deficiencyLikelihood }
            // Per-cause probability: deficiency likelihood × association
            // strength, capped so a 0.5/0.5 pair can never claim 100%.
            var acc = 0.02                              // base population rate
            for (c in contributors) {
                val cause = (c.deficiencyLikelihood * c.associationConfidence).coerceIn(0.0, 0.9)
                acc = noisyOr(acc, cause)
            }
            RankedSymptom(
                symptomKey = key,
                displayName = displays[key] ?: key,
                // 0.97 humility ceiling: multi-cause noisy-OR saturates fast;
                // a lifestyle app never claims 99 % medical certainty.
                likelihood = acc.coerceIn(0.0, 0.97),
                contributors = contributors
            )
        }
            .filter { it.likelihood >= 0.08 || it.contributors.size >= 2 }
            .sortedWith(
                compareByDescending<RankedSymptom> { it.likelihood }
                    .thenByDescending { it.contributors.size }
            )
            .take(max)
    }
}
