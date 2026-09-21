package com.example.hoot.data.tail

/**
 * Wire models for the Tail ContentProvider integration (protocol v1 + probed
 * v2 surface, see `docs/TAIL_REQUEST.md`). Pure Kotlin — no Android imports.
 */

/** A habit exposed by Tail's provider (`/habits`, or `/v2/habits` when present). */
data class TailHabit(
    val habitId: String,
    val habitName: String,
    /** v2 only: "counter" | "text" | "meal" | "timed" | "dated_entry" | "sleep" | "subtyped" | "app_link" | "other"; null on v1. */
    val habitType: String? = null
) {
    val isMealType: Boolean get() = habitType == "meal"
    val isTextType: Boolean get() = habitType == "text"
}

/** One text-habit log entry ("Took Pills" etc.). */
data class TailTextEntry(
    /** Stable entry id (v2); null on v1 — dedup then falls back to habit+timestamp key. */
    val entryId: String?,
    val habitName: String,
    /** Tail log key: "yyyy-MM-dd HH:mm:ss". */
    val timestampRaw: String,
    /** Epoch millis parsed from [timestampRaw] (device-local zone); 0 when unparseable. */
    val timestampMs: Long,
    val text: String
)

/** One meal-log record (Tail's internal `MealLog` shape, v2 `/v2/habits/{id}/entries`). */
data class TailMealEntry(
    val entryId: String,
    val habitName: String,
    /** Epoch millis the meal was logged. */
    val timestamp: Long,
    val title: String?,
    val summary: String?,
    val calories: Int?,
    val proteinGrams: Double?,
    val carbsGrams: Double?,
    val fatGrams: Double?,
    /** Raw ingredient strings, e.g. ["150 g lentils", "spinach"]. */
    val ingredientsDetected: List<String>,
    val isManual: Boolean
)

/** What the configured Tail app can do — probed at connect time (R6 spirit). */
data class TailCapabilities(
    val v2Habits: Boolean = false,
    val fullTextHistory: Boolean = false,
    val incremental: Boolean = false,
    val mealLogs: Boolean = false
)

/** Result of [TailClient.fetchMealLogs] — "unavailable" is an expected state, not a crash. */
sealed interface MealLogsResult {
    /** v2 meal endpoint present; [entries] may still be empty. */
    data class Available(val entries: List<TailMealEntry>) : MealLogsResult

    /** Tail does not expose meal logs yet (see `docs/TAIL_REQUEST.md`). */
    data object Unavailable : MealLogsResult

    /** Provider unreachable (Tail missing, permission denied, I/O error). */
    data class Error(val message: String) : MealLogsResult
}

/** Snapshot of a Tail installation discovered on the device. */
data class TailAppInfo(
    val packageName: String,
    val appName: String,
    /** True when the provider answered at least one query. */
    val providerReachable: Boolean
)
