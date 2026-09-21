package com.example.hoot.ui.common

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hoot.appGraph
import com.example.hoot.data.local.entity.NutrientDefinitionEntity
import com.example.hoot.data.local.entity.RecommendationEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** State of the actionable nutrient detail sheet (overhaul feedback #3). */
data class NutrientDetailState(
    val nutrientId: String = "",
    val def: NutrientDefinitionEntity = NutrientDefinitionEntity(
        id = "", name = "", group = "", unit = "", rdaValue = null,
        ulValue = null, tier = 3, deficiencySymptoms = null,
        excessRisks = null, foodSources = null
    ),
    val intake: Double = 0.0,
    val target: Double = 0.0,
    /** Cached + LLM suggestions for this nutrient today (open + answered). */
    val suggestions: List<RecommendationEntity> = emptyList(),
    val loadingMore: Boolean = false,
    /** True when no LLM is configured (graceful failure text). */
    val llmUnavailable: Boolean = false,
    val error: String? = null
) {
    val hasTarget: Boolean get() = target > 0
}

/**
 * Drives [NutrientDetailSheet]: live intake for today and the cache-first
 * suggestion feed with "More suggestions" on demand
 * ([com.example.hoot.domain.insights.RecommendationEngine.generateForNutrient]).
 * Suggestions are informational — no manual accept/dismiss marking; adherence
 * will later derive from actual intake.
 */
class NutrientDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app.appGraph
    private val day = todayKey()

    private val _state = MutableStateFlow(NutrientDetailState())
    val state: StateFlow<NutrientDetailState> = _state.asStateFlow()

    private var firstLoadDone = false

    /** Opens the sheet for one nutrient (idempotent; safe to call on every tap). */
    fun open(nutrientId: String) {
        if (_state.value.nutrientId == nutrientId && firstLoadDone) return
        viewModelScope.launch {
            val def = graph.nutrients.definition(nutrientId) ?: return@launch
            val goals = graph.nutrients.goalsAll().associateBy { it.nutrientId }
            val target = effectiveTarget(def, goals[nutrientId]?.targetValue)
            val intake = graph.nutrients.dailyTotals(day)
                .firstOrNull { it.nutrientId == nutrientId }?.total ?: 0.0
            _state.value = NutrientDetailState(
                nutrientId = nutrientId,
                def = def,
                intake = intake,
                target = target,
                suggestions = emptyList(),
                loadingMore = true,
                llmUnavailable = !graph.settings.current().llmConfigured
            )
            firstLoadDone = true
            generateMore(initial = true)
        }
    }

    /**
     * "More suggestions": cache-first, then (LLM configured) one scoped LLM
     * call. Graceful failure — sets [NutrientDetailState.error], never throws.
     */
    fun loadMore() {
        if (_state.value.loadingMore || !_state.value.hasTarget) return
        viewModelScope.launch { generateMore(initial = false) }
    }

    private suspend fun generateMore(initial: Boolean) {
        _state.value = _state.value.copy(loadingMore = true, error = null)
        val target = _state.value.target
        if (target <= 0) {
            _state.value = _state.value.copy(loadingMore = false)
            return
        }
        val stats = runCatching {
            graph.recommendationEngine.generateForNutrient(
                day = day,
                nutrientId = _state.value.nutrientId,
                count = 2,
                allowLlm = !initial
            )
        }.getOrElse {
            _state.value = _state.value.copy(
                loadingMore = false,
                error = "Couldn't fetch suggestions right now — check your connection and try again."
            )
            return
        }
        // Re-read the day's persisted suggestions for this nutrient (open
        // first). Diet gate (diet-fix hardening, 2026-09): rows issued before
        // a diet change (or by a constraint-ignoring LLM) must not surface in
        // the sheet — filter against the CURRENT merged profile.
        val s = graph.settings.current()
        val roomDiet = runCatching { graph.tailConfig.dietaryProfile() }.getOrNull()
        val dietFilter = com.example.hoot.domain.insights.DietTextFilter.merged(
            datastoreStyle = s.dietStyle,
            datastoreAllergies = s.dietAllergies,
            datastoreDislikes = s.dietDislikes,
            roomStyle = roomDiet?.dietStyle,
            roomAllergies = jsonList(roomDiet?.allergiesJson),
            roomDislikes = jsonList(roomDiet?.dislikesJson)
        )
        val recs = graph.nutrients.recommendationsBetween(day, day)
            .filter { it.nutrientId == _state.value.nutrientId }
            .filter {
                dietFilter.allows(it.foodName) && dietFilter.allows(it.reasonText)
            }
            .sortedWith(compareByDescending<RecommendationEntity> { it.accepted == null }.thenBy { it.foodName })
        _state.value = _state.value.copy(
            suggestions = recs,
            loadingMore = false,
            llmUnavailable = !stats.llmUsed && !graph.settings.current().llmConfigured,
            error = when {
                recs.isEmpty() && !graph.settings.current().llmConfigured ->
                    "No cached suggestions for ${_state.value.def.name} yet. " +
                        "Connect an LLM in Settings to generate ideas."
                recs.isEmpty() -> "No more suggestions found — try again later."
                else -> null
            }
        )
    }

    /** `["a","b"]` → [a, b]; tolerant of null/blank/invalid JSON. */
    private fun jsonList(raw: String?): List<String> = runCatching {
        val arr = org.json.JSONArray(raw ?: "[]")
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }.getOrDefault(emptyList())
}
