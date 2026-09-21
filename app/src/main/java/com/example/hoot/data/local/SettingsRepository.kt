package com.example.hoot.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "hoot_settings")

/**
 * Seed MCP configuration: z.ai internet tools (streamable-http) — identical
 * to Inuit's `DEFAULT_MCP_JSON`. The user pastes their own API key in
 * Settings. stdio servers cannot run on Android and are ignored by the
 * MCP client (phase 2+).
 */
const val DEFAULT_MCP_JSON = """{
  "mcpServers": {
    "web-search-prime": {
      "type": "streamable-http",
      "url": "https://api.z.ai/api/mcp/web_search_prime/mcp",
      "headers": { "Authorization": "Bearer PASTE_ZAI_API_KEY_HERE" }
    },
    "web-reader": {
      "type": "streamable-http",
      "url": "https://api.z.ai/api/mcp/web_reader/mcp",
      "headers": { "Authorization": "Bearer PASTE_ZAI_API_KEY_HERE" }
    }
  }
}"""

/**
 * App settings snapshot. LLM keys/defaults mirror Inuit's [com.example.inuit.data.SettingsStore]
 * one-for-one ("llm_base_url", "llm_api_key", "llm_model", "llm_temperature",
 * "llm_disable_thinking"); `llmConfigured` uses Inuit's rule.
 */
data class AppSettings(
    // LLM — keys identical to Inuit; defaults blank like Inuit.
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Float = 0.7f,
    /** Send thinking:disabled — GLM reasoning models skip internal chains (faster/cheaper). */
    val disableThinking: Boolean = false,

    // MCP — same JSON shape and same DEFAULT_MCP_JSON seed as Inuit.
    val mcpJson: String = DEFAULT_MCP_JSON,
    /** Tool calls per resolution run (0-20). */
    val mcpBudget: Int = 3,

    // Hoot-specific: Tail sync
    val syncEnabled: Boolean = true,
    val syncIntervalMinutes: Int = 60,
    /** LookupCache freshness window in days. */
    val cacheTtlDays: Int = 90,
    /** Allow MCP web-search fallback when LLM parse confidence is low. */
    val webSearchFallback: Boolean = true,

    // Nutrition pipeline throughput (no UI required; DataStore defaults rule).
    /** Distinct foods per batched LLM panel call (6-8 keeps replies in budget). */
    val llmBatchSize: Int = 8,
    /** Max LLM calls per sliding minute during a drain (0 = unlimited). */
    val llmCallsPerMinute: Int = 20,

    // Tail app mapping (habit selection duplicated in tail_app_config Room row
    // for sync-cursor atomicity; DataStore holds what the Settings UI edits).
    val tailPackage: String = "com.example.tail",
    val tailMealHabit: String = "",
    val tailPillsHabit: String = "",
    /** Water habit id; blank = unmapped (v5). */
    val tailWaterHabit: String = "",
    /**
     * What a BARE number in the Tail water habit means (feedback 2026-09:
     * Tail logs raw ml — "2500" = 2.5 L — but Hoot previously assumed
     * unitless numbers were 250 ml glasses). One of: "auto" (heuristic:
     * ≥100 → ml, else glasses), "ml", "l", "oz", "glass". Explicit units in
     * the text ("500 ml", "1.5 l") always win over this setting.
     */
    val waterUnitMode: String = "auto",
    /** Misc habit ids (JSON array string); blank = none (v5). */
    val tailMiscHabitsJson: String = "",

    // Dietary profile (mirrors dietary_profile Room row; source of truth for UI).
    val dietStyle: String = "omnivore",
    val dietAllergies: Set<String> = emptySet(),
    val dietDislikes: Set<String> = emptySet(),

    // User profile for RDA adjustments (0/blank = unknown → use adult defaults).
    val userWeightKg: Float = 0f,
    val userHeightCm: Float = 0f,
    val userAgeYears: Int = 0,
    val userSex: String = "",            // "male" | "female" | ""

    // UI preferences.
    val uiDynamicColors: Boolean = true,
    val uiDarkMode: Boolean = true       // dark-first
) {
    val llmConfigured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()
}

/**
 * DataStore-backed settings store (mirrors Inuit's SettingsStore API shape:
 * a `settings` Flow, `current()`, and small focused `saveX` editors).
 */
class SettingsRepository(private val context: Context) {

    private object K {
        val BASE_URL = stringPreferencesKey("llm_base_url")
        val API_KEY = stringPreferencesKey("llm_api_key")
        val MODEL = stringPreferencesKey("llm_model")
        val TEMPERATURE = floatPreferencesKey("llm_temperature")
        val DISABLE_THINKING = booleanPreferencesKey("llm_disable_thinking")
        val MCP_BUDGET = intPreferencesKey("mcp_budget")
        val MCP_JSON = stringPreferencesKey("mcp_json")
        val SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        val SYNC_INTERVAL = intPreferencesKey("sync_interval_minutes")
        val CACHE_TTL_DAYS = intPreferencesKey("cache_ttl_days")
        val WEB_SEARCH_FALLBACK = booleanPreferencesKey("web_search_fallback")
        val LLM_BATCH_SIZE = intPreferencesKey("llm_batch_size")
        val LLM_CALLS_PER_MINUTE = intPreferencesKey("llm_calls_per_minute")
        val TAIL_PACKAGE = stringPreferencesKey("tail_package")
        val TAIL_MEAL_HABIT = stringPreferencesKey("tail_meal_habit")
        val TAIL_PILLS_HABIT = stringPreferencesKey("tail_pills_habit")
        val TAIL_WATER_HABIT = stringPreferencesKey("tail_water_habit")
        val WATER_UNIT_MODE = stringPreferencesKey("water_unit_mode")
        val TAIL_MISC_HABITS_JSON = stringPreferencesKey("tail_misc_habits_json")
        val DIET_STYLE = stringPreferencesKey("diet_style")
        val DIET_ALLERGIES = stringSetPreferencesKey("diet_allergies")
        val DIET_DISLIKES = stringSetPreferencesKey("diet_dislikes")
        val USER_WEIGHT_KG = floatPreferencesKey("user_weight_kg")
        val USER_HEIGHT_CM = floatPreferencesKey("user_height_cm")
        val USER_AGE_YEARS = intPreferencesKey("user_age_years")
        val USER_SEX = stringPreferencesKey("user_sex")
        val UI_DYNAMIC_COLORS = booleanPreferencesKey("ui_dynamic_colors")
        val UI_DARK_MODE = booleanPreferencesKey("ui_dark_mode")
        val PILLS_SPLIT_RESYNC = booleanPreferencesKey("pills_split_resync_done")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            baseUrl = p[K.BASE_URL] ?: "",
            apiKey = p[K.API_KEY] ?: "",
            model = p[K.MODEL] ?: "",
            temperature = p[K.TEMPERATURE] ?: 0.7f,
            disableThinking = p[K.DISABLE_THINKING] ?: false,
            mcpBudget = (p[K.MCP_BUDGET] ?: 3).coerceIn(0, 20),
            mcpJson = p[K.MCP_JSON] ?: DEFAULT_MCP_JSON,
            syncEnabled = p[K.SYNC_ENABLED] ?: true,
            syncIntervalMinutes = (p[K.SYNC_INTERVAL] ?: 60).coerceIn(15, 720),
            cacheTtlDays = (p[K.CACHE_TTL_DAYS] ?: 90).coerceIn(1, 365),
            webSearchFallback = p[K.WEB_SEARCH_FALLBACK] ?: true,
            llmBatchSize = (p[K.LLM_BATCH_SIZE] ?: 8).coerceIn(1, 20),
            llmCallsPerMinute = (p[K.LLM_CALLS_PER_MINUTE] ?: 20).coerceIn(0, 120),
            tailPackage = p[K.TAIL_PACKAGE] ?: "com.example.tail",
            tailMealHabit = p[K.TAIL_MEAL_HABIT] ?: "",
            tailPillsHabit = p[K.TAIL_PILLS_HABIT] ?: "",
            tailWaterHabit = p[K.TAIL_WATER_HABIT] ?: "",
            waterUnitMode = p[K.WATER_UNIT_MODE] ?: "auto",
            tailMiscHabitsJson = p[K.TAIL_MISC_HABITS_JSON] ?: "",
            dietStyle = p[K.DIET_STYLE] ?: "omnivore",
            dietAllergies = p[K.DIET_ALLERGIES] ?: emptySet(),
            dietDislikes = p[K.DIET_DISLIKES] ?: emptySet(),
            userWeightKg = p[K.USER_WEIGHT_KG] ?: 0f,
            userHeightCm = p[K.USER_HEIGHT_CM] ?: 0f,
            userAgeYears = p[K.USER_AGE_YEARS] ?: 0,
            userSex = p[K.USER_SEX] ?: "",
            uiDynamicColors = p[K.UI_DYNAMIC_COLORS] ?: true,
            uiDarkMode = p[K.UI_DARK_MODE] ?: true
        )
    }

    suspend fun current(): AppSettings = settings.first()

    /** LLM endpoint — identical semantics to Inuit's saveLlm. */
    suspend fun saveLlm(baseUrl: String, apiKey: String, model: String, temperature: Float) {
        context.dataStore.edit {
            it[K.BASE_URL] = baseUrl.trim()
            it[K.API_KEY] = apiKey.trim()
            it[K.MODEL] = model.trim()
            it[K.TEMPERATURE] = temperature
        }
    }

    suspend fun setDisableThinking(disable: Boolean) {
        context.dataStore.edit { it[K.DISABLE_THINKING] = disable }
    }

    suspend fun saveMcpJson(json: String) {
        context.dataStore.edit { it[K.MCP_JSON] = json }
    }

    suspend fun setMcpBudget(budget: Int) {
        context.dataStore.edit { it[K.MCP_BUDGET] = budget.coerceIn(0, 20) }
    }

    /** Tail-sync cadence and cache freshness. */
    suspend fun saveSync(
        syncEnabled: Boolean,
        syncIntervalMinutes: Int,
        cacheTtlDays: Int,
        webSearchFallback: Boolean
    ) {
        context.dataStore.edit {
            it[K.SYNC_ENABLED] = syncEnabled
            it[K.SYNC_INTERVAL] = syncIntervalMinutes.coerceIn(15, 720)
            it[K.CACHE_TTL_DAYS] = cacheTtlDays.coerceIn(1, 365)
            it[K.WEB_SEARCH_FALLBACK] = webSearchFallback
        }
    }

    /** Tail app package + habit mapping (meal, pills, water, misc habits). */
    suspend fun saveTailMapping(
        tailPackage: String,
        mealHabit: String,
        pillsHabit: String,
        waterHabit: String = "",
        miscHabitsJson: String = "",
        waterUnitMode: String? = null
    ) {
        context.dataStore.edit {
            it[K.TAIL_PACKAGE] = tailPackage.trim()
            it[K.TAIL_MEAL_HABIT] = mealHabit.trim()
            it[K.TAIL_PILLS_HABIT] = pillsHabit.trim()
            it[K.TAIL_WATER_HABIT] = waterHabit.trim()
            waterUnitMode?.let { mode -> it[K.WATER_UNIT_MODE] = mode }
            it[K.TAIL_MISC_HABITS_JSON] = miscHabitsJson.trim()
        }
    }

    /** Water unit interpretation for bare numbers (see [AppSettings.waterUnitMode]). */
    suspend fun saveWaterUnitMode(mode: String) {
        context.dataStore.edit { it[K.WATER_UNIT_MODE] = mode }
    }

    /** Dietary restrictions: style + free-form allergy/dislike sets. */
    suspend fun saveDiet(style: String, allergies: Set<String>, dislikes: Set<String>) {
        context.dataStore.edit {
            it[K.DIET_STYLE] = style.trim().ifBlank { "omnivore" }
            it[K.DIET_ALLERGIES] = allergies
            it[K.DIET_DISLIKES] = dislikes
        }
    }

    /** Body metrics used for weight-based RDAs (protein) and sex-specific targets. */
    suspend fun saveUserProfile(weightKg: Float, heightCm: Float, ageYears: Int, sex: String) {
        context.dataStore.edit {
            it[K.USER_WEIGHT_KG] = weightKg.coerceIn(0f, 400f)
            it[K.USER_HEIGHT_CM] = heightCm.coerceIn(0f, 260f)
            it[K.USER_AGE_YEARS] = ageYears.coerceIn(0, 120)
            it[K.USER_SEX] = if (sex == "male" || sex == "female") sex else ""
        }
    }

    /** UI preferences (theme mode, dynamic color). */
    suspend fun saveUiPrefs(dynamicColors: Boolean, darkMode: Boolean) {
        context.dataStore.edit {
            it[K.UI_DYNAMIC_COLORS] = dynamicColors
            it[K.UI_DARK_MODE] = darkMode
        }
    }

    /**
     * One-time migration flag for the pills multi-item fix: true once the
     * pills sync cursor has been reset to re-ingest the pre-split backlog.
     */
    suspend fun pillsSplitResyncDone(): Boolean =
        context.dataStore.data.first()[K.PILLS_SPLIT_RESYNC] ?: false

    suspend fun markPillsSplitResyncDone() {
        context.dataStore.edit { it[K.PILLS_SPLIT_RESYNC] = true }
    }
}
