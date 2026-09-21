package com.example.hoot.data.tail

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Read-only client for the Tail habit tracker's ContentProvider
 * (`content://com.example.tail.provider`, signature permission
 * `com.example.tail.permission.TAIL_INTEGRATION` — same keystore).
 *
 * Mirrors Inuit's [com.example.inuit.data.TailIntegration] approach: raw
 * [ContentResolver] queries with defensive column access and soft-fail
 * everywhere — a missing Tail app degrades to empty results / a
 * [MealLogsResult.Unavailable] marker, never a crash. (OkHttp enters the
 * picture only in the phase-3 LLM/MCP client; provider IPC needs none.)
 *
 * Endpoint strategy (see `docs/TAIL_REQUEST.md`):
 *  - `/habits` (v1: habit_id, habit_name) — always available.
 *  - `/v2/habits` (adds habit_type) — probed, v1 fallback.
 *  - `/text_habits`, `/text_habits/recent?limit=N` (v1, bounded slice).
 *  - `/v2/habits/{id}/entries` (full backlog, `?after=` incremental) — probed;
 *    falls back to `/text_habits/recent` when absent.
 *  - Meal-log rows on the v2 entries endpoint — probed; when Tail has not
 *    shipped them yet [fetchMealLogs] returns [MealLogsResult.Unavailable].
 */
class TailClient(context: Context) {

    private val appContext = context.applicationContext

    // ── App discovery ─────────────────────────────────────────────────────

    /**
     * Detects installed packages that expose a Tail-style provider under the
     * known authority pattern. The canonical `com.example.tail` is always
     * probed first; [CANDIDATE_PACKAGES] holds fallbacks. Returns apps whose
     * provider answered a lightweight `/habits` query.
     */
    suspend fun listTailApps(): List<TailAppInfo> = withContext(Dispatchers.IO) {
        val found = mutableListOf<TailAppInfo>()
        val pm = appContext.packageManager
        for (pkg in CANDIDATE_PACKAGES) {
            val info = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PROVIDERS) }
                .getOrNull() ?: continue
            val exposesProvider = info.providers?.any {
                it.authority?.startsWith(TAIL_AUTHORITY_PREFIX) == true
            } == true
            // Package visible but provider row not readable via GET_PROVIDERS —
            // still probe the authority directly (Android 11 <queries> grants).
            val reachable = if (exposesProvider) true else probeReachable(pkg)
            if (exposesProvider || reachable) {
                found += TailAppInfo(
                    packageName = pkg,
                    appName = appLabel(pm, pkg),
                    providerReachable = reachable && probeReachable(pkg)
                )
            }
        }
        found
    }

    // ── Capabilities probe (R6) ───────────────────────────────────────────

    /**
     * Probes the v2 surface: `/v2/capabilities` when present, else feature
     * detection by direct endpoint pokes. Never throws.
     */
    suspend fun probeCapabilities(pkg: String): TailCapabilities = withContext(Dispatchers.IO) {
        var caps = TailCapabilities()
        // 1) Official flags endpoint, when Tail ships it.
        queryOrNull(pkg, PATH_V2_CAPABILITIES)?.use { c ->
            if (c.moveToFirst()) {
                val features = c.columnString("features")
                caps = capsFromFeatures(features)
            }
        } ?: run {
            // 2) Feature detection: v2 habits carrying habit_type.
            val v2Habits = queryOrNull(pkg, PATH_V2_HABITS)?.use { c ->
                c.columnIndex("habit_type") >= 0
            } ?: false
            caps = caps.copy(v2Habits = v2Habits)
        }
        // 3) Full text entries: does the first text habit answer a v2 entries query?
        //    (Cheap one-row probe; the actual sync falls back per habit anyway.)
        val textHabit = listHabits(pkg).firstOrNull { it.isTextType || it.habitType == null }
        if (textHabit != null) {
            val full = queryOrNull(
                pkg, "$PATH_V2_HABITS/${Uri.encode(textHabit.habitId)}/entries?limit=1"
            )
            full?.use { c ->
                val hasTextCol = c.columnIndex(COL_ENTRY_TEXT) >= 0
                // Optimistically assume incremental (`?after=`) support: if Tail
                // ignores the param it returns everything, and idempotent
                // upsert-dedup makes the redundant rows harmless.
                caps = caps.copy(fullTextHistory = hasTextCol, incremental = hasTextCol)
            }
        }
        // 4) Meal logs: does the first meal-type habit (or any v2 entries) expose meal columns?
        val mealHabit = listHabits(pkg).firstOrNull { it.isMealType }
        if (mealHabit != null) {
            queryOrNull(pkg, "$PATH_V2_HABITS/${Uri.encode(mealHabit.habitId)}/entries?limit=1")
                ?.use { c ->
                    if (c.columnIndex("ingredients") >= 0 || c.columnIndex("ingredientsDetected") >= 0) {
                        caps = caps.copy(mealLogs = true)
                    }
                }
        }
        caps
    }

    // ── Habits ────────────────────────────────────────────────────────────

    /**
     * Lists all habits of the given Tail app, in screen order. Prefers
     * `/v2/habits` (typed), falls back to `/habits` (v1, untyped).
     */
    suspend fun listHabits(pkg: String): List<TailHabit> = withContext(Dispatchers.IO) {
        val v2 = queryOrNull(pkg, PATH_V2_HABITS)?.use { c -> c.toHabits(typed = true) }
        if (!v2.isNullOrEmpty()) return@withContext v2
        queryOrNull(pkg, PATH_HABITS)?.use { c -> c.toHabits(typed = false) } ?: emptyList()
    }

    /** Shared text-input habits (Tail: Settings → Integrations opt-in). */
    suspend fun listTextHabits(pkg: String): List<TailHabit> = withContext(Dispatchers.IO) {
        queryOrNull(pkg, PATH_TEXT_HABITS)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val name = c.columnString(COL_HABIT_NAME) ?: continue
                    add(TailHabit(habitId = c.columnString(COL_HABIT_ID) ?: name, habitName = name))
                }
            }
        } ?: emptyList()
    }

    // ── Text entries ──────────────────────────────────────────────────────

    /**
     * Recent text entries across shared text habits (v1 bounded slice:
     * ≤[limit] per habit, 14-day window, 300 chars). When [habitId] is
     * non-null only that habit's rows are returned.
     */
    suspend fun fetchRecentTextEntries(
        pkg: String,
        habitId: String? = null,
        limit: Int = RECENT_FALLBACK_LIMIT
    ): List<TailTextEntry> = withContext(Dispatchers.IO) {
        val uri = Uri.withAppendedPath(authority(pkg), PATH_TEXT_HABITS_RECENT)
            .buildUpon()
            .appendQueryParameter("limit", limit.coerceIn(1, RECENT_FALLBACK_LIMIT).toString())
            .build()
        parseTextRows(queryOrNull(uri = uri)) { it == habitId || habitId == null }
    }

    /**
     * Full history of one text habit. Probes `/v2/habits/{id}/entries`
     * (unbounded, optional `?after=` epoch-millis incremental per R3) and
     * falls back to the v1 `/text_habits/recent` slice when v2 is absent.
     * Entries are returned oldest-first within each source batch.
     */
    suspend fun fetchFullHistory(
        pkg: String,
        habitId: String,
        afterTimestamp: Long? = null
    ): TailTextHistory = withContext(Dispatchers.IO) {
        val v2Uri = buildString {
            append(authority(pkg))
            append(PATH_V2_HABITS)
            append('/')
            append(Uri.encode(habitId))
            append("/entries")
            if (afterTimestamp != null) append("?after=").append(afterTimestamp)
        }
        val v2 = queryOrNull(null, null, Uri.parse(v2Uri))
        if (v2 != null) {
            val rows = parseTextRows(v2) { true }
            return@withContext TailTextHistory(full = true, entries = rows)
        }
        // v1 fallback: bounded recent slice (backlog bug fix, 2026-09: the old
        // limit=5 meant only the 5 newest rows per habit ever synced — a
        // water habit with a multi-week backlog showed a single day of data
        // and the "first run = full backlog" promise silently broke). Pull
        // the maximum slice the v1 surface allows; dedup keys make re-pulls
        // idempotent, so the bounded window is safe to re-read each pass.
        val recent = fetchRecentTextEntries(pkg, habitId, limit = RECENT_FALLBACK_LIMIT)
        TailTextHistory(full = false, entries = recent)
    }

    // ── Meal logs (v2, currently unshipped — see docs/TAIL_REQUEST.md) ────

    /**
     * Meal-log rows for a meal-type habit from `/v2/habits/{id}/entries`.
     * Returns [MealLogsResult.Unavailable] — not an error — when Tail does
     * not expose meal logs yet, so callers can show a notice instead of
     * failing the sync.
     */
    suspend fun fetchMealLogs(
        pkg: String,
        habitId: String,
        afterTimestamp: Long? = null
    ): MealLogsResult = withContext(Dispatchers.IO) {
        val uri = buildString {
            append(authority(pkg))
            append(PATH_V2_HABITS)
            append('/')
            append(Uri.encode(habitId))
            append("/entries")
            if (afterTimestamp != null) append("?after=").append(afterTimestamp)
        }
        val cursor = try {
            withTimeout(PROBE_TIMEOUT_MS) {
                appContext.contentResolver.query(Uri.parse(uri), null, null, null, null)
            }
        } catch (e: Exception) {
            null
        } ?: return@withContext MealLogsResult.Unavailable

        cursor.use { c ->
            // Text-shaped rows mean Tail routed this habit through the text
            // entry path (or the endpoint shape changed) — treat as unavailable.
            if (c.columnIndex(COL_ENTRY_TEXT) >= 0 && c.columnIndex("ingredients") < 0) {
                return@withContext MealLogsResult.Unavailable
            }
            val rows = mutableListOf<TailMealEntry>()
            while (c.moveToNext()) {
                val id = c.columnString("entry_id")
                    ?: c.columnString("id")
                    ?: continue
                val ts = c.columnLong("timestamp")
                rows += TailMealEntry(
                    entryId = id,
                    habitName = c.columnString("habit_name") ?: habitId,
                    timestamp = ts,
                    title = c.columnString("title"),
                    summary = c.columnString("summary"),
                    calories = if (c.columnIndex("calories") >= 0 && !c.isNull(c.columnIndex("calories")))
                        c.getInt(c.columnIndex("calories")) else null,
                    proteinGrams = c.columnDouble("protein_grams") ?: c.columnDouble("proteinGrams"),
                    carbsGrams = c.columnDouble("carbs_grams") ?: c.columnDouble("carbsGrams"),
                    fatGrams = c.columnDouble("fat_grams") ?: c.columnDouble("fatGrams"),
                    ingredientsDetected = parseIngredients(
                        c.columnString("ingredients") ?: c.columnString("ingredientsDetected")
                    ),
                    isManual = c.columnLong("is_manual") == 1L || c.columnLong("isManual") == 1L
                )
            }
            MealLogsResult.Available(rows)
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────

    /** Content URI for a path on this app's Tail authority. */
    private fun authority(pkg: String): Uri =
        Uri.parse("content://" + if (pkg == CANONICAL_TAIL_PACKAGE) TAIL_AUTHORITY else "$pkg.provider")

    /**
     * Runs a provider query; any failure (missing app, permission, column,
     * timeout) → null. Suspending so [withTimeout] can bound the caller's
     * wait — a stuck binder call cannot be cancelled, but the coroutine
     * resumes as cancelled and the catch converts it to a soft null.
     */
    private suspend fun queryOrNull(pkg: String? = null, path: String? = null, uri: Uri? = null): Cursor? = try {
        val target = uri ?: Uri.withAppendedPath(authority(pkg ?: CANONICAL_TAIL_PACKAGE), path!!)
        withTimeout(PROBE_TIMEOUT_MS) {
            appContext.contentResolver.query(target, null, null, null, null)
        }
    } catch (e: Exception) {
        null
    }

    private suspend fun probeReachable(pkg: String): Boolean =
        queryOrNull(pkg = pkg, path = PATH_HABITS)?.use { true } ?: false

    private fun appLabel(pm: PackageManager, pkg: String): String = try {
        val appInfo = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(appInfo).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun Cursor.toHabits(typed: Boolean): List<TailHabit> = buildList {
        while (moveToNext()) {
            val name = columnString(COL_HABIT_NAME) ?: continue
            val id = columnString(COL_HABIT_ID) ?: name
            val type = if (typed) columnString("habit_type") else null
            add(TailHabit(habitId = id, habitName = name, habitType = type))
        }
    }

    /**
     * Parses entry rows (text AND value shapes); [filter] receives the raw
     * habit_id column (may be null on v1). Counter-habit rows carry the
     * v2 `value` column (daily count) with an empty `entry_text` — captured
     * so mapped counter habits (the user's water) stop reading as blank.
     */
    private fun parseTextRows(cursor: Cursor?, filter: (String?) -> Boolean): List<TailTextEntry> {
        cursor ?: return emptyList()
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val habitCol = c.columnString(COL_HABIT_ID) ?: c.columnString(COL_HABIT_NAME)
                    if (!filter(habitCol)) continue
                    val tsRaw = c.columnString(COL_ENTRY_TS) ?: continue
                    add(
                        TailTextEntry(
                            entryId = c.columnString("entry_id") ?: c.columnString("id"),
                            habitName = c.columnString(COL_HABIT_NAME) ?: habitCol ?: "",
                            timestampRaw = tsRaw,
                            timestampMs = parseTailTimestamp(tsRaw),
                            text = c.columnString(COL_ENTRY_TEXT) ?: "",
                            value = c.columnDouble(COL_VALUE)
                        )
                    )
                }
            }
        }
    }

    private fun capsFromFeatures(featuresJson: String?): TailCapabilities {
        val features = runCatching {
            val arr = JSONArray(featuresJson ?: "[]")
            (0 until arr.length()).map { arr.optString(it) }
        }.getOrDefault(emptyList())
        return TailCapabilities(
            v2Habits = features.any { it.contains("habits_v2") },
            fullTextHistory = features.any { it.contains("entries_full") },
            incremental = features.any { it.contains("entries_incremental") },
            mealLogs = features.any { it.contains("meal_logs") }
        )
    }

    /** Tail text log keys are "yyyy-MM-dd HH:mm:ss" local time; 0 when unparseable. */
    fun parseTailTimestamp(raw: String): Long = try {
        LocalDateTime.parse(raw.trim(), TAIL_TS_FORMAT)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } catch (e: Exception) {
        0L
    }

    /** `["150 g lentils","spinach"]` → [“150 g lentils”, “spinach”]; tolerates raw comma text. */
    private fun parseIngredients(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.getOrElse {
            json.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    /** Result of [fetchFullHistory]: whether the unbounded v2 surface was available. */
    data class TailTextHistory(
        val full: Boolean,
        val entries: List<TailTextEntry>
    )

    companion object {
        /** The Tail habit tracker's package and provider authority. */
        const val CANONICAL_TAIL_PACKAGE = "com.example.tail"
        const val TAIL_AUTHORITY = "com.example.tail.provider"
        const val TAIL_AUTHORITY_PREFIX = "com.example.tail.provider"

        /** Signature permission Tail declares; guards all provider reads. */
        const val PERMISSION_TAIL = "com.example.tail.permission.TAIL_INTEGRATION"

        /** Packages probed by [listTailApps] (canonical first). */
        val CANDIDATE_PACKAGES = listOf(CANONICAL_TAIL_PACKAGE)

        // Provider paths.
        const val PATH_HABITS = "/habits"
        const val PATH_V2_HABITS = "/v2/habits"
        const val PATH_V2_CAPABILITIES = "/v2/capabilities"
        const val PATH_TEXT_HABITS = "/text_habits"
        const val PATH_TEXT_HABITS_RECENT = "/text_habits/recent"

        // Provider columns (v1 names per Inuit's integration).
        const val COL_HABIT_ID = "habit_id"
        const val COL_HABIT_NAME = "habit_name"
        const val COL_ENTRY_TS = "entry_ts"
        const val COL_ENTRY_TEXT = "entry_text"

        /** v2 value-habit (counter) daily count column (Tail's COL_VALUE). */
        const val COL_VALUE = "value"

        /** Soft cap on any single provider probe (binder calls can block). */
        const val PROBE_TIMEOUT_MS = 8_000L

        /**
         * v1 fallback slice size (backlog fix 2026-09): the old 5-row slice
         * starved water/pills histories — only the newest entries ever
         * reached `tail_entries`, so a fully-backlogged water habit rendered
         * ONE day on the Home card. Up to ~3 months of daily entries; stable
         * dedup keys keep re-pulls idempotent.
         */
        const val RECENT_FALLBACK_LIMIT = 100

        /** Shared formatter for Tail's text log keys. */
        val TAIL_TS_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** Convenience test hook: valid JSON for a capabilities row's features column. */
        fun capabilitiesJson(features: List<String>): String {
            val arr = JSONArray()
            features.forEach { arr.put(it) }
            return JSONObject().put("features", arr).toString()
        }
    }
}

// ── Defensive cursor helpers ──────────────────────────────────────────────

/** Column index or -1 (never throws, unlike getColumnIndexOrThrow). */
private fun Cursor.columnIndex(name: String): Int =
    runCatching { getColumnIndex(name) }.getOrDefault(-1)

private fun Cursor.columnString(name: String): String? {
    val i = columnIndex(name)
    if (i < 0) return null
    return runCatching { getString(i) }.getOrNull()
}

private fun Cursor.columnLong(name: String): Long {
    val i = columnIndex(name)
    if (i < 0) return 0L
    return runCatching { getLong(i) }.getOrDefault(0L)
}

private fun Cursor.columnDouble(name: String): Double? {
    val i = columnIndex(name)
    if (i < 0) return null
    return runCatching { getDouble(i) }.getOrNull()
}
