package com.example.hoot.ui.home

import android.content.Context
import com.example.hoot.data.local.entity.ScoreSnapshotEntity
import com.example.hoot.domain.insights.FocusNowItem
import com.example.hoot.domain.insights.Insight
import com.example.hoot.domain.insights.InsightKind
import com.example.hoot.domain.insights.InsightSeverity
import com.example.hoot.domain.insights.SmartFoodPick
import com.example.hoot.domain.insights.SmartNutrientHit
import com.example.hoot.domain.insights.SymptomMatcher
import com.example.hoot.domain.score.ScoreComponent
import com.example.hoot.domain.score.ScoreStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Last-session snapshot of [HomeUiState] (startup UX fix, 2026-11-16).
 *
 * Problem: Home's first frame waits on the full DB pipeline — score snapshot
 * recompute, a 7-day reactive window, the deficiency model (30/90/365-day
 * scans) and smart-picks candidate scan. Right after launch the startup
 * self-heals ([com.example.hoot.di.NutritionPipeline]) rewrite the intake
 * ledger concurrently, invalidating the observe-flows, so the in-flight
 * compute is cancelled and restarted over and over — the dashboard spinner
 * sometimes spun for a very long time with NOTHING on screen.
 *
 * Fix (stale-while-revalidate): every computed state is persisted here as
 * JSON; on the next launch the cached state is emitted FIRST (loading=false,
 * fromCache=true) and the live pipeline continues in the background, swapping
 * sections in as they land. The cache is display-only — a stale or missing
 * file just falls back to the previous loading behavior.
 */
object HomeStateCache {

    private const val DIR = "home_state_cache"
    private const val FILE = "home_ui_state.json"

    // ---- File I/O -------------------------------------------------------------

    private fun file(context: Context): File =
        File(File(context.filesDir, DIR), FILE)

    /** Reads the last persisted state, or null when absent/corrupt. */
    suspend fun load(context: Context): HomeUiState? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val f = file(context)
                if (!f.exists()) return@runCatching null
                fromJson(JSONObject(f.readText()))?.copy(fromCache = true)
            }.getOrNull()
        }

    /** Persists [state] atomically (tmp + rename). Never throws. */
    suspend fun store(context: Context, state: HomeUiState) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val f = file(context)
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, "$FILE.tmp")
                tmp.writeText(toJson(state).toString())
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            }
        }
    }

    // ---- JSON codec (org.json — mirrors CapturedMeal's approach) ---------------

    fun toJson(s: HomeUiState): JSONObject = JSONObject().apply {
        put("day", s.day)
        s.score?.let { put("score", scoreToJson(it)) }
        put("focusNow", toJsonArray(s.focusNow.map { focusToJson(it) }))
        put("tier1", toJsonArray(s.tier1.map { compToJson(it) }))
        put("otherTiers", toJsonArray(s.otherTiers.map { compToJson(it) }))
        s.calories?.let { (kcal, unit) ->
            put("calories", JSONArray().put(kcal).put(unit))
        }
        put("macros", toJsonArray(s.macros.map { compToJson(it) }))
        put("insights", toJsonArray(s.insights.map { insightToJson(it) }))
        put("sparkline", toJsonArray(s.sparkline))
        put("unresolvedCount", s.unresolvedCount)
        put("smartPicks", toJsonArray(s.smartPicks.map { pickToJson(it) }))
        put("allSmartPicks", toJsonArray(s.allSmartPicks.map { pickToJson(it) }))
        put("deepSmartPicks", toJsonArray(s.deepSmartPicks.map { pickToJson(it) }))
        put("likelySymptoms", toJsonArray(s.likelySymptoms.map { symptomToJson(it) }))
        put("smartPicksCacheCold", s.smartPicksCacheCold)
        put("knowledgeCount", s.knowledgeCount)
    }

    fun fromJson(o: JSONObject): HomeUiState? = runCatching {
        val day = o.optString("day")
        if (day.isBlank()) return@runCatching null // junk/empty payload
        HomeUiState(
            day = day,
            loading = false,
            score = o.optJSONObject("score")?.let { scoreFromJson(it) },
            focusNow = o.optJSONArray("focusNow").mapList { focusFromJson(it) },
            tier1 = o.optJSONArray("tier1").mapList { compFromJson(it) },
            otherTiers = o.optJSONArray("otherTiers").mapList { compFromJson(it) },
            calories = o.optJSONArray("calories")?.let {
                if (it.length() == 2) it.optDouble(0) to it.optString(1) else null
            },
            macros = o.optJSONArray("macros").mapList { compFromJson(it) },
            insights = o.optJSONArray("insights").mapList { insightFromJson(it) },
            sparkline = o.optJSONArray("sparkline")
                ?.let { arr -> List(arr.length()) { i -> arr.optDouble(i) } } ?: emptyList(),
            unresolvedCount = o.optInt("unresolvedCount", 0),
            smartPicks = o.optJSONArray("smartPicks").mapList { pickFromJson(it) },
            allSmartPicks = o.optJSONArray("allSmartPicks").mapList { pickFromJson(it) },
            deepSmartPicks = o.optJSONArray("deepSmartPicks").mapList { pickFromJson(it) },
            likelySymptoms = o.optJSONArray("likelySymptoms").mapList { symptomFromJson(it) },
            smartPicksCacheCold = o.optBoolean("smartPicksCacheCold", false),
            knowledgeCount = o.optInt("knowledgeCount", 0)
        )
    }.getOrNull()

    // ---- Per-model codecs -------------------------------------------------------

    private fun scoreToJson(e: ScoreSnapshotEntity) = JSONObject().apply {
        put("day", e.day); put("score", e.score); put("completeness", e.completeness)
        put("deficiencyPenalty", e.deficiencyPenalty); put("adherence", e.adherence)
        put("nutrientsMet", e.nutrientsMet); put("nutrientsTracked", e.nutrientsTracked)
    }

    private fun scoreFromJson(o: JSONObject) = ScoreSnapshotEntity(
        day = o.optString("day"), score = o.optDouble("score"),
        completeness = o.optDouble("completeness"),
        deficiencyPenalty = o.optDouble("deficiencyPenalty"),
        adherence = o.optDouble("adherence"),
        nutrientsMet = o.optInt("nutrientsMet"), nutrientsTracked = o.optInt("nutrientsTracked")
    )

    private fun compToJson(c: ScoreComponent) = JSONObject().apply {
        put("nutrientId", c.nutrientId); put("name", c.name); put("tier", c.tier)
        put("unit", c.unit); put("intake", c.intake); put("target", c.target)
        put("coverage", c.coverage); put("isLimitTracker", c.isLimitTracker)
        put("ulExceeded", c.ulExceeded); put("status", c.status.name)
    }

    private fun compFromJson(o: JSONObject) = ScoreComponent(
        nutrientId = o.optString("nutrientId"), name = o.optString("name"),
        tier = o.optInt("tier"), unit = o.optString("unit"),
        intake = o.optDouble("intake"), target = o.optDouble("target"),
        coverage = o.optDouble("coverage"),
        isLimitTracker = o.optBoolean("isLimitTracker"),
        ulExceeded = o.optBoolean("ulExceeded"),
        status = runCatching { ScoreStatus.valueOf(o.optString("status")) }
            .getOrDefault(ScoreStatus.LOW)
    )

    private fun focusToJson(f: FocusNowItem) = JSONObject().apply {
        put("nutrientId", f.nutrientId); put("name", f.name); put("tier", f.tier)
        put("unit", f.unit); put("target", f.target)
        put("recentAvgIntake", f.recentAvgIntake); put("todayIntake", f.todayIntake)
        put("recentCoverage", f.recentCoverage); put("todayCoverage", f.todayCoverage)
        put("likelihood", f.likelihood)
        put("worstWindowDays", f.worstWindowDays)
        put("worstWindowAvgCoverage", f.worstWindowAvgCoverage)
    }

    private fun focusFromJson(o: JSONObject) = FocusNowItem(
        nutrientId = o.optString("nutrientId"), name = o.optString("name"),
        tier = o.optInt("tier"), unit = o.optString("unit"),
        target = o.optDouble("target"),
        recentAvgIntake = o.optDouble("recentAvgIntake"),
        todayIntake = o.optDouble("todayIntake"),
        recentCoverage = o.optDouble("recentCoverage"),
        todayCoverage = o.optDouble("todayCoverage"),
        // Defaults keep pre-2026-11-17 cached states parseable.
        likelihood = o.optDouble("likelihood", 0.0),
        worstWindowDays = o.optInt("worstWindowDays", 0),
        worstWindowAvgCoverage = o.optDouble("worstWindowAvgCoverage", 1.0)
    )

    private fun insightToJson(i: Insight) = JSONObject().apply {
        put("kind", i.kind.name); put("severity", i.severity.name)
        put("title", i.title); put("message", i.message)
        i.nutrientId?.let { put("nutrientId", it) }
        i.effects?.let { put("effects", it) }
    }

    private fun insightFromJson(o: JSONObject) = Insight(
        kind = runCatching { InsightKind.valueOf(o.optString("kind")) }
            .getOrDefault(InsightKind.DEFICIENCY),
        severity = runCatching { InsightSeverity.valueOf(o.optString("severity")) }
            .getOrDefault(InsightSeverity.INFO),
        title = o.optString("title"), message = o.optString("message"),
        nutrientId = if (o.has("nutrientId")) o.optString("nutrientId") else null,
        effects = if (o.has("effects")) o.optString("effects") else null
    )

    private fun pickToJson(p: SmartFoodPick) = JSONObject().apply {
        put("foodId", p.foodId); put("displayName", p.displayName)
        p.category?.let { put("category", it) }
        put("servingGrams", p.servingGrams)
        put("hits", toJsonArray(p.hits.map { h ->
            JSONObject().apply {
                put("nutrientId", h.nutrientId); put("name", h.name); put("unit", h.unit)
                put("deficitCovered", h.deficitCovered); put("servingAmount", h.servingAmount)
            }
        }))
        put("score", p.score)
        put("cautions", toJsonArray(p.cautions))
        put("source", p.source); put("why", p.why)
        p.emojiHint?.let { put("emojiHint", it) }
    }

    private fun pickFromJson(o: JSONObject): SmartFoodPick = SmartFoodPick(
        foodId = o.optString("foodId"),
        displayName = o.optString("displayName"),
        category = if (o.has("category")) o.optString("category") else null,
        servingGrams = o.optDouble("servingGrams"),
        hits = o.optJSONArray("hits").mapList { h ->
            SmartNutrientHit(
                nutrientId = h.optString("nutrientId"), name = h.optString("name"),
                unit = h.optString("unit"),
                deficitCovered = h.optDouble("deficitCovered"),
                servingAmount = h.optDouble("servingAmount")
            )
        },
        score = o.optDouble("score"),
        cautions = o.optJSONArray("cautions")
            ?.let { a -> List(a.length()) { i -> a.optString(i) } } ?: emptyList(),
        source = o.optString("source"), why = o.optString("why"),
        emojiHint = if (o.has("emojiHint")) o.optString("emojiHint") else null
    )

    private fun symptomToJson(s: SymptomMatcher.RankedSymptom) = JSONObject().apply {
        put("symptomKey", s.symptomKey); put("displayName", s.displayName)
        put("likelihood", s.likelihood)
        put("contributors", toJsonArray(s.contributors.map { c ->
            JSONObject().apply {
                put("nutrientId", c.nutrientId); put("nutrientName", c.nutrientName)
                put("deficiencyLikelihood", c.deficiencyLikelihood)
                put("associationConfidence", c.associationConfidence)
                put("unit", c.unit); put("target", c.target)
            }
        }))
    }

    private fun symptomFromJson(o: JSONObject): SymptomMatcher.RankedSymptom =
        SymptomMatcher.RankedSymptom(
            symptomKey = o.optString("symptomKey"),
            displayName = o.optString("displayName"),
            likelihood = o.optDouble("likelihood"),
            contributors = o.optJSONArray("contributors").mapList { c ->
                SymptomMatcher.Contributor(
                    nutrientId = c.optString("nutrientId"),
                    nutrientName = c.optString("nutrientName"),
                    deficiencyLikelihood = c.optDouble("deficiencyLikelihood"),
                    associationConfidence = c.optDouble("associationConfidence"),
                    unit = c.optString("unit"), target = c.optDouble("target")
                )
            }
        )

    // ---- Small helpers ----------------------------------------------------------

    /**
     * One generic helper (JVM signature clash fix): type-erased overloads of
     * the same name collide on List<?>, so a single `fun` takes any items.
     */
    private fun toJsonArray(items: List<Any?>): JSONArray {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        return arr
    }

    private inline fun <T> JSONArray?.mapList(transform: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return List(length()) { i -> transform(optJSONObject(i) ?: JSONObject()) }
    }
}
