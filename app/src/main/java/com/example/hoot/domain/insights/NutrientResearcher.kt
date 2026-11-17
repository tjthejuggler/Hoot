package com.example.hoot.domain.insights

import android.util.Log
import com.example.hoot.data.local.SettingsRepository
import com.example.hoot.data.local.SymptomSeed
import com.example.hoot.data.local.entity.NutrientSymptomEntity
import com.example.hoot.data.remote.LlmClient
import com.example.hoot.data.remote.LlmConfig
import com.example.hoot.data.repository.FoodKnowledgeRepository
import com.example.hoot.data.repository.NutrientDefinitionRepository
import com.example.hoot.data.repository.NutrientSymptomRepository
import com.example.hoot.domain.nutrition.FoodNormalizer
import com.example.hoot.domain.nutrition.NutritionResolver
import org.json.JSONObject

/**
 * Per-nutrient research agent (deficiency system, 2026-11-16): sent from the
 * nutrient page's Research button. ONE LLM call that carries everything
 * Hoot already knows about the nutrient (seed symptoms, researched symptom
 * keys, known food sources) and asks specifically for NEW associations and
 * NEW food sources. Results persist into `nutrient_symptom` (RESEARCH rows)
 * and the standard food pipeline (knowledge base / batch panels).
 *
 * Zero-LLM-safe: no configured LLM → typed no-op outcome.
 */
class NutrientResearcher(
    private val definitions: NutrientDefinitionRepository,
    private val symptoms: NutrientSymptomRepository,
    private val knowledge: FoodKnowledgeRepository,
    private val resolver: NutritionResolver,
    private val settings: SettingsRepository,
    private val llm: LlmClient
) {
    /** What one research pass found (named, for the "just added" popup). */
    data class Outcome(
        val newDeficiencySymptoms: List<String> = emptyList(),
        val newExcessSymptoms: List<String> = emptyList(),
        val newFoods: List<String> = emptyList(),
        val llmUnavailable: Boolean = false,
        val message: String? = null
    ) {
        val isEmpty: Boolean
            get() = newDeficiencySymptoms.isEmpty() && newExcessSymptoms.isEmpty() && newFoods.isEmpty()
    }

    /** Research scope the user picks on the nutrient page. */
    enum class Scope { SYMPTOMS_AND_FOODS, SYMPTOMS, FOODS }

    suspend fun research(nutrientId: String, scope: Scope = Scope.SYMPTOMS_AND_FOODS): Outcome {
        val def = definitions.definition(nutrientId)
            ?: return Outcome(message = "Unknown nutrient")
        val s = runCatching { settings.current() }.getOrNull()
            ?: return Outcome(message = "Settings unavailable")
        val cfg = LlmConfig(s.baseUrl, s.apiKey, s.model)
        if (!cfg.configured) return Outcome(llmUnavailable = true)

        val known = symptoms.forNutrient(nutrientId)
        val knownDefKeys = known.filter { it.kind == SymptomSeed.KIND_DEFICIENCY }.map { it.symptomKey }
        val knownExcKeys = known.filter { it.kind == SymptomSeed.KIND_EXCESS }.map { it.symptomKey }
        val knownFoods = (def.foodSources ?: "").split(',', ';', '\n')
            .map { FoodNormalizer.normalize(it) }
            .filter { it.isNotBlank() }.toSet()

        val json = runCatching {
            llm.chat(
                cfg, SYSTEM_PROMPT, userPrompt(def.name, def.id, def.unit,
                    def.rdaValue, def.ulValue,
                    def.deficiencySymptoms ?: "", def.excessRisks ?: "",
                    knownDefKeys + knownExcKeys, knownFoods, scope),
                temperature = 0.3f,
                maxTokens = 900,
                disableThinking = true
            )
        }.onFailure { Log.w(TAG, "nutrient research failed for $nutrientId: ${it.message}") }
            .getOrNull() ?: return Outcome(message = "Research call failed — try again.")

        val parsed = runCatching { parse(LlmClient.extractJson(json)) }
            .getOrElse { Parsed() }
        if (parsed.isEmpty()) return Outcome(message = "Nothing new found — Hoot may already know it all.")

        // 1) Canonicalize the CATALOG first (grouping feedback, 2026-11-16):
        //    research keys like "dry_itchy_skin" must land in the existing
        //    "dry_skin" group, not beside it. Also folds any prior drift.
        runCatching { symptoms.canonicalizeKeys() }
        val knownNow = symptoms.forNutrient(nutrientId)

        // 1) Persist NEW symptom associations (deduped against existing keys).
        val existingKeys = knownNow.map { it.kind to it.symptomKey }.toSet()
        val now = System.currentTimeMillis()
        val rows = ArrayList<NutrientSymptomEntity>()
        val addedDeficiency = ArrayList<String>()
        val addedExcess = ArrayList<String>()
        for ((key, display, conf) in parsed.deficiency) {
            if (SymptomSeed.KIND_DEFICIENCY to key in existingKeys) continue
            rows += entity(nutrientId, key, display, conf, SymptomSeed.KIND_DEFICIENCY, now)
            addedDeficiency += display
        }
        for ((key, display, conf) in parsed.excess) {
            if (SymptomSeed.KIND_EXCESS to key in existingKeys) continue
            rows += entity(nutrientId, key, display, conf, SymptomSeed.KIND_EXCESS, now)
            addedExcess += display
        }
        if (rows.isNotEmpty()) symptoms.applyResearch(
            rows + knownNow.filter { it.source == "RESEARCH" }
        )

        // 2) Persist NEW foods through the standard pipeline (batch panels).
        val addedFoods = ArrayList<String>()
        val newFoodNames = parsed.foods.filter { FoodNormalizer.normalize(it) !in knownFoods }
        if (newFoodNames.isNotEmpty()) {
            val pairs = newFoodNames.map { FoodNormalizer.normalize(it) to FoodNormalizer.displayName(it) }
                .distinctBy { it.first }
            val panels = runCatching { resolver.resolveFoodsBatch(pairs, null) }.getOrDefault(emptyMap())
            for ((key, panel) in panels) {
                val display = pairs.firstOrNull { it.first == key }?.second ?: key
                if (runCatching { resolver.persistFoodPanel(key, display, panel) }.getOrDefault(false)) {
                    runCatching { knowledge.tagResearchedFor(key, listOf(nutrientId)) }
                    addedFoods += display
                }
            }
        }
        return Outcome(
            newDeficiencySymptoms = addedDeficiency,
            newExcessSymptoms = addedExcess,
            newFoods = addedFoods
        )
    }

    private fun entity(
        nutrientId: String, key: String, display: String,
        conf: Double, kind: String, now: Long
    ) = NutrientSymptomEntity(
        id = "$nutrientId:$kind:$key",
        nutrientId = nutrientId,
        symptomKey = key,
        displayName = display,
        kind = kind,
        confidence = conf.coerceIn(0.2, 0.95),
        source = "RESEARCH",
        researchedAt = now
    )

    private data class Sym(val key: String, val display: String, val conf: Double)
    private data class Parsed(
        val deficiency: List<Sym> = emptyList(),
        val excess: List<Sym> = emptyList(),
        val foods: List<String> = emptyList()
    ) {
        fun isEmpty() = deficiency.isEmpty() && excess.isEmpty() && foods.isEmpty()
    }

    private fun parse(json: String): Parsed = try {
        val o = JSONObject(json)
        fun symptomsOf(key: String): List<Sym> {
            val arr = o.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                val k = e.optString("key").trim().lowercase().replace(' ', '_')
                    .replace(Regex("[^a-z0-9_]"), "")
                if (k.isBlank()) return@mapNotNull null
                Sym(k, e.optString("display").ifBlank { k },
                    e.optDouble("confidence", 0.5))
            }
        }
        Parsed(
            deficiency = symptomsOf("deficiency_symptoms"),
            excess = symptomsOf("excess_symptoms"),
            foods = (0 until (o.optJSONArray("foods")?.length() ?: 0)).mapNotNull { i ->
                o.optJSONArray("foods")?.optString(i)?.trim()?.takeIf { it.isNotBlank() }
            }
        )
    } catch (_: Exception) {
        Parsed()
    }

    private fun userPrompt(
        name: String, id: String, unit: String, rda: Double?, ul: Double?,
        seedDeficiency: String, seedExcess: String,
        knownKeys: Collection<String>, knownFoods: Set<String>,
        scope: Scope
    ): String {
        val knownList = knownKeys.joinToString(", ").ifBlank { "(none yet)" }
        val foodList = knownFoods.joinToString(", ").ifBlank { "(none yet)" }
        val wantSymptoms = scope != Scope.FOODS
        val wantFoods = scope != Scope.SYMPTOMS
        return """
Research the nutrient $name (id "$id", unit $unit, RDA ${rda ?: "?"} $unit, UL ${ul ?: "none"}).

Already known (do NOT repeat these):
- Known symptom keys: $knownList
- Seed notes — deficiency: ${seedDeficiary(seedDeficiency)} | excess: ${seedDeficiary(seedExcess)}
- Known food sources: $foodList

Find what is MISSING${if (wantSymptoms && wantFoods) "" else if (wantSymptoms) " (SYMPTOMS ONLY this pass)" else " (FOODS ONLY this pass)"}. Return COMPACT JSON only:
{"deficiency_symptoms":${if (wantSymptoms) """[{"key":"snake_case","display":"Short label","confidence":0.0-1.0}]""" else "[]"},
 "excess_symptoms":${if (wantSymptoms) "[same shape]" else "[]"},
 "foods":${if (wantFoods) """["Food name", ...]""" else "[]"}}

Rules: max 6 deficiency + 4 excess symptoms (only well-established, textbook
associations; confidence reflects strength of evidence); max 6 foods that are
genuinely rich in $name and ABSENT from the known list; common food names.

CRITICAL SYMPTOM-KEY RULES:
1. Each key must name EXACTLY ONE symptom. NEVER combine, broaden, or
   generalize two different symptoms into one key.
2. If a new symptom is LITERALLY the same thing as a known key (pure
   rephrasing, e.g. "dry skin" for known key "dry_skin"), reuse the EXACT
   known key string — do not invent a new one.
3. If a symptom is merely SIMILAR to, related to, or often co-occurs with a
   known one but is a DIFFERENT symptom (e.g. dry/rough skin vs pale skin:
   texture vs color; bone pain vs joint pain: different tissues), it MUST
   get its own SEPARATE key. Do NOT lump it into the similar known key.
4. When in doubt whether two descriptions are the same symptom, treat them
   as DIFFERENT symptoms and use separate keys.
""".trimIndent()
    }

    private fun seedDeficiary(raw: String): String = raw.replace("\n", " ").take(140).ifBlank { "(none)" }

    private companion object {
        private const val TAG = "HootResearch"
        private const val SYSTEM_PROMPT = """You are a nutrition-science research assistant.
You return ONLY compact, valid JSON with well-established, textbook nutrient
knowledge (NIH/WHO-level evidence). Never invent exotic associations; when
unsure, omit the entry.

You are STRICTLY FORBIDDEN from merging or broadening symptoms: every
symptom key you emit must describe exactly ONE distinct symptom. Distinct
symptoms (dry/rough skin vs pale skin, bone pain vs joint pain, fatigue vs
low mood) must NEVER be folded into one shared key — a key may only be
reused for a literally identical symptom phrased differently."""
    }
}
