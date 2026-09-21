package com.example.hoot.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hoot.appGraph
import com.example.hoot.data.local.AppSettings
import com.example.hoot.data.local.DEFAULT_MCP_JSON
import com.example.hoot.data.local.entity.DietaryProfileEntity
import com.example.hoot.data.local.entity.NutrientGoalEntity
import com.example.hoot.data.remote.LlmConfig
import com.example.hoot.data.remote.McpClient
import com.example.hoot.data.remote.McpConfig
import com.example.hoot.data.tail.TailSyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Settings state holder (Inuit's MainViewModel settings surface, adapted to
 * Hoot's AppGraph): DataStore-backed snapshot, LLM/MCP "Test" reachability
 * checks (listModels / MCP initialize+listTools), dietary + user-profile
 * persistence (mirrored into the dietary_profile Room row the recommender
 * reads), per-nutrient goal editing, cache stats/actions and JSON export.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app.appGraph

    // ---- Observables -------------------------------------------------------

    /** Persisted settings snapshot (re-seeds local edit state in the UI). */
    val settings: StateFlow<AppSettings> = graph.settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Singleton tail_app_config row (habit mapping + last-sync cursors). */
    val tailConfig = graph.tailConfig.observeTailConfig()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Live Tail sync progress (status row + Sync now feedback). */
    val syncState: StateFlow<TailSyncState> = graph.tailSync.syncState

    /** Live resolution-queue state (Re-resolve button feedback). */
    val processorState = graph.nutritionProcessor.state

    /** Reactive lookup-cache row count. */
    val cacheCount: StateFlow<Int> = graph.lookups.observeCacheCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _cacheOldest = MutableStateFlow<Long?>(null)
    val cacheOldest: StateFlow<Long?> = _cacheOldest.asStateFlow()

    init {
        refreshCacheStats()
    }

    fun refreshCacheStats() {
        viewModelScope.launch {
            _cacheOldest.value = runCatching { graph.nutrients.cacheStats().second }.getOrNull()
        }
    }

    // ---- Test results (Inuit pattern: Running → Ok/Fail) --------------------

    sealed interface TestResult {
        data object Running : TestResult
        data class Ok(val message: String) : TestResult
        data class Fail(val message: String) : TestResult
    }

    private val _llmTest = MutableStateFlow<TestResult?>(null)
    val llmTest: StateFlow<TestResult?> = _llmTest.asStateFlow()

    private val _mcpTest = MutableStateFlow<TestResult?>(null)
    val mcpTest: StateFlow<TestResult?> = _mcpTest.asStateFlow()

    // ---- LLM ----------------------------------------------------------------

    fun saveLlm(baseUrl: String, apiKey: String, model: String, temperature: Float) {
        viewModelScope.launch { graph.settings.saveLlm(baseUrl, apiKey, model, temperature) }
    }

    /** Autosaved switch (Inuit binds disableThinking directly to the store). */
    fun setDisableThinking(disable: Boolean) {
        viewModelScope.launch { graph.settings.setDisableThinking(disable) }
    }

    /** Inuit-identical reachability check: GET /models, warn on model mismatch. */
    fun testLlm(baseUrl: String, apiKey: String, model: String) {
        viewModelScope.launch {
            _llmTest.value = TestResult.Running
            _llmTest.value = try {
                val cfg = LlmConfig(baseUrl.trim(), apiKey.trim(), model.trim())
                if (!cfg.configured) {
                    TestResult.Fail("Base URL and model are required")
                } else {
                    val models = graph.llmClient.listModels(cfg)
                    val found = models.any { it == model.trim() }
                    when {
                        models.isEmpty() -> TestResult.Ok("Endpoint reachable (no model list)")
                        found || model.isBlank() -> TestResult.Ok("OK — ${models.size} models visible")
                        else -> TestResult.Fail("Endpoint works but '$model' not in list (it may still work)")
                    }
                }
            } catch (e: Exception) {
                TestResult.Fail(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ---- MCP ----------------------------------------------------------------

    /**
     * Validates then persists the MCP JSON + tool budget. Invalid JSON is
     * reported through [onInvalid] and NOT saved.
     */
    fun saveMcp(json: String, budget: Int, onInvalid: (String) -> Unit) {
        val parsed = McpConfig.parse(json)
        if (parsed.error != null) {
            onInvalid(parsed.error)
            return
        }
        viewModelScope.launch {
            graph.settings.saveMcpJson(json)
            graph.settings.setMcpBudget(budget)
        }
    }

    /** Restore the seeded z.ai web-search + web-reader configuration. */
    fun resetMcpJson() {
        viewModelScope.launch { graph.settings.saveMcpJson(DEFAULT_MCP_JSON) }
    }

    /** Inuit-identical MCP probe: initialize + tools/list per HTTP server. */
    fun testMcp(json: String) {
        viewModelScope.launch {
            _mcpTest.value = TestResult.Running
            _mcpTest.value = try {
                val parsed = McpConfig.parse(json)
                when {
                    parsed.error != null -> TestResult.Fail("Invalid JSON: ${parsed.error}")
                    parsed.servers.isEmpty() -> TestResult.Fail(
                        "No HTTP servers found" +
                            (if (parsed.skipped.isNotEmpty()) " (skipped stdio: ${parsed.skipped.joinToString()})" else "")
                    )
                    else -> {
                        val sb = StringBuilder()
                        for (server in parsed.servers) {
                            try {
                                val client = McpClient(server)
                                client.initialize()
                                val tools = runCatching { client.listTools() }.getOrDefault(emptyList())
                                sb.append("OK ${server.name} (${tools.size} tools); ")
                            } catch (e: Exception) {
                                sb.append("FAILED ${server.name}: ${e.message ?: e.javaClass.simpleName}; ")
                            }
                        }
                        val msg = sb.toString().trim().trimEnd(';', ' ')
                        if (msg.contains("FAILED")) TestResult.Fail(msg) else TestResult.Ok(msg)
                    }
                }
            } catch (e: Exception) {
                TestResult.Fail(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ---- Tail + sync preferences --------------------------------------------

    fun syncNow() {
        viewModelScope.launch { runCatching { graph.tailSync.sync() } }
    }

    fun saveSync(syncEnabled: Boolean, syncIntervalMinutes: Int, cacheTtlDays: Int, webSearchFallback: Boolean) {
        viewModelScope.launch {
            graph.settings.saveSync(syncEnabled, syncIntervalMinutes, cacheTtlDays, webSearchFallback)
        }
    }

    // ---- Dietary profile + user stats ---------------------------------------

    /**
     * Persists diet choices in DataStore AND mirrors them into the
     * dietary_profile Room row — the recommender's allergy/dislike filters
     * read that row ([com.example.hoot.domain.insights.RecommendationEngine]).
     */
    fun saveDiet(style: String, allergies: Set<String>, dislikes: Set<String>) {
        val effectiveStyle = style.trim().ifBlank { "omnivore" }
        viewModelScope.launch {
            graph.settings.saveDiet(effectiveStyle, allergies, dislikes)
            runCatching {
                // Preserve excludeFromScoring (managed in Tail setup): building
                // the singleton row from scratch would silently reset it to false.
                val keepExcluded = graph.tailConfig.dietaryProfile()?.excludeFromScoring ?: false
                graph.tailConfig.saveDietaryProfile(
                    DietaryProfileEntity(
                        dietStyle = effectiveStyle,
                        allergiesJson = JSONArray(allergies.sorted()).toString(),
                        dislikesJson = JSONArray(dislikes.map { it.lowercase().trim() }.sorted()).toString(),
                        excludeFromScoring = keepExcluded
                    )
                )
            }
            // Diet feeds the recommender's filters — regenerate today's recs
            // so suggestions never contradict the just-saved profile.
            graph.recommendForToday()
        }
    }

    /**
     * Body metrics for future RDA adjustments. NOTE (TODO hook): the score
     * engine still uses the static adult RDA from nutrient_definitions;
     * weight/sex-dependent targets are not wired yet.
     */
    fun saveUserProfile(weightKg: Float, heightCm: Float, ageYears: Int, sex: String) {
        viewModelScope.launch { graph.settings.saveUserProfile(weightKg, heightCm, ageYears, sex) }
    }

    // ---- Nutrient goals -------------------------------------------------------

    /** One editable goal row in the Goals section. */
    data class GoalRow(
        val nutrientId: String,
        val name: String,
        val unit: String,
        val tier: Int,
        val rda: Double?,
        val target: Double?,   // null → RDA default applies
        val isCustom: Boolean
    ) {
        val effectiveTarget: Double get() = target ?: rda ?: 0.0
    }

    val goalRows: StateFlow<List<GoalRow>> =
        combine(graph.nutrients.observeDefinitions(), graph.nutrients.observeGoals()) { defs, goals ->
            defs.map { def ->
                val g = goals.firstOrNull { it.nutrientId == def.id }
                GoalRow(
                    nutrientId = def.id,
                    name = def.name,
                    unit = def.unit,
                    tier = g?.priority ?: def.tier,
                    rda = def.rdaValue,
                    target = g?.targetValue,
                    isCustom = g?.isCustom == true
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Persists a custom target (priority defaults to the definition tier). */
    fun saveCustomGoal(row: GoalRow, value: Double) {
        if (value <= 0.0) return
        viewModelScope.launch {
            graph.nutrients.upsertGoal(
                NutrientGoalEntity(
                    id = "goal:${row.nutrientId}",
                    nutrientId = row.nutrientId,
                    targetValue = value,
                    isCustom = true,
                    priority = row.tier
                )
            )
            onGoalsChanged()
        }
    }

    /** Resets one nutrient to its RDA default (drops the custom row). */
    fun resetGoal(nutrientId: String) {
        viewModelScope.launch {
            runCatching { graph.nutrients.deleteGoal(nutrientId) }
            onGoalsChanged()
        }
    }

    /** Resets every nutrient to its RDA default. */
    fun resetAllGoals() {
        viewModelScope.launch {
            runCatching {
                graph.nutrients.definitionsAll().forEach { def -> graph.nutrients.deleteGoal(def.id) }
            }
            onGoalsChanged()
        }
    }

    /**
     * Goals (targets/priority) feed both the score engine and the
     * recommender, but neither observes the goals table — recompute the
     * affected artifacts explicitly after any goal edit.
     */
    private suspend fun onGoalsChanged() {
        runCatching { graph.scoreSnapshotter.recomputeAll() }
            .onFailure { android.util.Log.e("HootSettings", "goal-change snapshot recompute failed", it) }
        graph.recommendForToday()
    }

    // ---- Data & cache ---------------------------------------------------------

    fun clearCache(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { graph.lookups.clearCacheKeepSources() }  // keeps citation Sources
            refreshCacheStats()
            onDone()
        }
    }

    /** Kicks the NutritionProcessor queue: re-enqueues unresolved ingredients/supplements. */
    fun reresolveFailed() {
        graph.nutritionProcessor.refreshAll()
    }

    /**
     * Builds the JSON export (meals + ingredients + supplements + per-day
     * intake totals). Null on unexpected failure — the UI shows a toast.
     */
    suspend fun exportDataJson(): String? = withContext(Dispatchers.IO) {
        runCatching { buildExport() }.getOrNull()
    }

    private suspend fun buildExport(): String {
        val mealDays = graph.meals.distinctMealDays()
        val suppDays = graph.meals.distinctSupplementDays()

        val mealsArr = JSONArray()
        for (day in mealDays) {
            for (m in graph.meals.mealsByDay(day)) {
                val ingredients = JSONArray()
                for (ing in graph.meals.ingredientsForMeal(m.id)) {
                    ingredients.put(
                        JSONObject()
                            .put("text", ing.rawText)
                            .put("amount", ing.amount ?: JSONObject.NULL)
                            .put("unit", ing.unit ?: JSONObject.NULL)
                            .put("grams", ing.gramsEstimate ?: JSONObject.NULL)
                    )
                }
                mealsArr.put(
                    JSONObject()
                        .put("id", m.id)
                        .put("day", m.day)
                        .put("timestamp", m.timestamp)
                        .put("title", m.title ?: JSONObject.NULL)
                        .put("text", m.rawText)
                        .put("source", m.source)
                        .put("habit", m.tailHabitName)
                        .put("ingredients", ingredients)
                )
            }
        }

        val suppArr = JSONArray()
        for (day in suppDays) {
            for (s in graph.meals.supplementsByDay(day)) {
                suppArr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("day", s.day)
                        .put("timestamp", s.timestamp)
                        .put("label", s.label)
                        .put("text", s.rawText)
                )
            }
        }

        val intakeArr = JSONArray()
        for (day in (mealDays + suppDays).distinct().sorted()) {
            for (t in graph.nutrients.dailyTotals(day)) {
                intakeArr.put(
                    JSONObject()
                        .put("day", day)
                        .put("nutrientId", t.nutrientId)
                        .put("total", t.total)
                )
            }
        }

        return JSONObject()
            .put("app", "hoot")
            .put("exportedAt", Instant.now().toString())
            .put("meals", mealsArr)
            .put("supplements", suppArr)
            .put("intake", intakeArr)
            .toString(2)
    }
}
