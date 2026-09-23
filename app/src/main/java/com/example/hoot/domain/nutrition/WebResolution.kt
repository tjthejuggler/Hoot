package com.example.hoot.domain.nutrition

import android.util.Log
import com.example.hoot.data.local.AppSettings
import com.example.hoot.data.remote.LlmClient
import com.example.hoot.data.remote.LlmConfig
import com.example.hoot.data.remote.McpWebTools

/**
 * Web tier of the resolution pipeline (extracted from [NutritionResolver],
 * refactor 2026-09-22 P4): budgeted MCP web-search-prime → web-reader →
 * LLM extraction, with per-process session reuse and authoritative-host
 * ranking. Emits a merged panel plus the cited URLs for provenance.
 */
internal class WebResolution(
    private val llm: LlmClient
) {
    /** Per-process MCP session reuse (reconnects only when settings change). */
    private var webTools: McpWebTools? = null
    private var webToolsKey: String? = null
    private val webToolsMutex = Any()

    /** (c) web-search-prime → web-reader → LLM extraction, honoring budget. */
    suspend fun resolveViaWeb(
        key: String,
        displayName: String,
        defs: List<NutritionPrompts.NutrientRef>,
        cfg: LlmConfig,
        s: AppSettings
    ): WebOutcome? {
        val tools = getWebTools(s) ?: return null
        val query = "$key cooked nutrition per 100g USDA"
        val results = tools.searchWeb(query)
        if (results.isEmpty()) return null
        // Prefer authoritative hosts; keep per-URL dedup (ARCHITECTURE.md §10).
        val ranked = results.sortedByDescending { r -> NutritionResolver.AUTHORITATIVE.firstOrNull { r.url.contains(it, ignoreCase = true) } != null }
        var combinedValues: MutableMap<String, Double>? = null
        var bestConfidence = 0.0
        var serving: Double? = null
        var image: String? = null
        val usedUrls = mutableListOf<String>()
        for (r in ranked.take(2)) {
            if (tools.remainingBudget <= 0) break
            val pageText = tools.readUrl(r.url)
            if (pageText.isBlank()) continue
            val panel = runCatching {
                val json = llm.completeJson(
                    cfg, RESOLUTION_SYSTEM_PROMPT.trimIndent(),
                    NutritionPrompts.webExtractUserPrompt(displayName, r.url, pageText, defs),
                    temperature = 0.1f,
                    disableThinking = s.disableThinking
                )
                NutritionPrompts.parsePanel(json, defs.associate { it.id to it.unit }.keys)
            }.getOrNull() ?: continue
            if (combinedValues == null) combinedValues = HashMap()
            for ((k, v) in panel.values) combinedValues!!.putIfAbsent(k, v)
            bestConfidence = maxOf(bestConfidence, panel.confidence)
            serving = serving ?: panel.typicalServingGrams
            image = image ?: panel.imageSearchTerm
            usedUrls += r.url
            if (bestConfidence >= NutritionResolver.CONFIDENCE_THRESHOLD && combinedValues!!.size >= 8) break
        }
        val values = combinedValues ?: return null
        if (values.isEmpty()) return null
        return WebOutcome(
            panel = NutritionPrompts.FoodPanel(displayName, values, bestConfidence.coerceAtLeast(0.6), serving, image),
            sources = usedUrls
        )
    }

    private suspend fun getWebTools(s: AppSettings): McpWebTools? {
        synchronized(webToolsMutex) {
            val key = "${s.mcpJson.hashCode()}:${s.mcpBudget}"
            if (webTools == null || webToolsKey != key) {
                webTools = McpWebTools(s.mcpBudget)
                webToolsKey = key
            }
        }
        return webTools?.also { runCatching { it.connect(s.mcpJson) } }
            ?.takeIf { it.remainingBudget >= 0 }
    }

    internal data class WebOutcome(
        val panel: NutritionPrompts.FoodPanel,
        val sources: List<String>
    )

    companion object {
        private const val TAG = "HootResolver"
    }
}
