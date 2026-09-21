package com.example.hoot.domain.insights

import com.example.hoot.data.remote.LlmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Short natural-language "Coach note" summarizing a window (phase 4).
 * One LLM call, strict JSON reply; every failure degrades gracefully to the
 * deterministic template note — the UI never sees an error from this path.
 */
object CoachNote {

    data class Result(
        val note: String,
        val fromLlm: Boolean
    )

    /** System prompt: Hoot's tone rules for the coach. */
    fun systemPrompt(): String =
        "You are Hoot, a concise nutrition coach. Reply with ONLY a JSON object " +
            "{\"note\": \"...\"}. The note is 1-2 short sentences (max 320 chars), " +
            "encouraging, specific, references the user's biggest gap and one win. " +
            "No medical advice, no supplementsdosage instructions, no emoji."

    /**
     * Diet-aware restriction clause for the prompt and the template fallback;
     * null when the user set no restrictions (omnivore/blank, nothing avoided).
     */
    fun dietContext(
        style: String?,
        allergies: Collection<String>,
        dislikes: Collection<String>
    ): String? {
        val parts = buildList {
            style?.trim()?.takeIf { it.isNotEmpty() && !it.equals("omnivore", true) }
                ?.let { add("diet: $it") }
            val al = allergies.filter { it.isNotBlank() }
            if (al.isNotEmpty()) add("allergies: ${al.joinToString()}")
            val dl = dislikes.filter { it.isNotBlank() }
            if (dl.isNotEmpty()) add("dislikes: ${dl.joinToString()}")
        }
        if (parts.isEmpty()) return null
        return "User restrictions (${parts.joinToString("; ")}). These are STRICT HARD " +
            "CONSTRAINTS: every food mention MUST respect them — e.g. for vegan, " +
            "STRICTLY EXCLUDE all meat, fish, seafood, eggs, dairy and honey. " +
            "A note referencing foods that violate them is useless; never do it."
    }

    /** Builds the user prompt from window facts (deterministic, testable). */
    fun userPrompt(
        insights: List<Insight>,
        avgScore: Double?,
        adherencePct: Int?,
        dietContext: String? = null
    ): String {
        val facts = StringBuilder()
        avgScore?.let { facts.append("Average daily score: %.0f/100. ".format(it)) }
        adherencePct?.let { facts.append("Followed $it% of recommendations. ") }
        val top = InsightsEngine.topSummary(insights, max = 5)
        if (top.isEmpty()) facts.append("No significant gaps detected this window.")
        else top.joinTo(facts, "; ") {
            when (it.kind) {
                InsightKind.DEFICIENCY -> "low ${it.nutrientId ?: it.title}"
                InsightKind.EXCESS -> "high ${it.nutrientId ?: it.title}"
                else -> it.title.lowercase()
            }
        }
        return "Summarize this nutrition window for the user. Facts: $facts" +
            (dietContext?.let { " $it" } ?: "")
    }

    /** Parses the LLM reply into the note text; null when unparseable/blank. */
    fun parseNote(json: String): String? = runCatching {
        val obj = org.json.JSONObject(LlmClient.extractJson(json))
        obj.optString("note").takeIf { it.isNotBlank() }?.trim()?.take(400)
    }.getOrNull()

    /**
     * Generates the note: 1 LLM call (when configured), template fallback on
     * any failure or when the LLM is not configured.
     */
    suspend fun generate(
        llm: LlmClient?,
        cfg: com.example.hoot.data.remote.LlmConfig?,
        disableThinking: Boolean,
        insights: List<Insight>,
        avgScore: Double?,
        adherencePct: Int?,
        dietContext: String? = null
    ): Result {
        val fallback = Result(template(insights, avgScore, adherencePct, dietContext), fromLlm = false)
        val client = llm ?: return fallback
        val config = cfg?.takeIf { it.configured } ?: return fallback
        return withContext(Dispatchers.IO) {
            runCatching {
                val raw = client.completeJson(
                    cfg = config,
                    system = systemPrompt(),
                    user = userPrompt(insights, avgScore, adherencePct, dietContext),
                    temperature = 0.4f,
                    maxTokens = 300,
                    disableThinking = disableThinking
                )
                parseNote(raw)?.let { Result(it, fromLlm = true) } ?: fallback
            }.getOrElse { fallback }
        }
    }

    /** Deterministic template note — the no-LLM / LLM-failed path (diet-aware). */
    fun template(
        insights: List<Insight>,
        avgScore: Double?,
        adherencePct: Int?,
        dietContext: String? = null
    ): String {
        val top = InsightsEngine.topSummary(insights, max = 2)
        val scorePart = avgScore?.let { "Average score %.0f/100. ".format(it) } ?: ""
        val adhPart = adherencePct?.let { "You followed $it% of your recommendations. " } ?: ""
        val gapPart = when {
            top.isEmpty() -> "No major gaps this window — keep eating the rainbow."
            else -> "Focus: " + top.joinToString(" and ") { it.title.lowercase() } + "."
        }
        val dietPart = dietContext?.let { " Stay within it: $it" } ?: ""
        return (scorePart + adhPart + gapPart + dietPart).trim()
    }
}
