package com.example.hoot.data.tail

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * **Protocol v7 — STRICTLY ONE-WAY (Tail → Hoot).**
 *
 * User directive (feedback 2026-09): Hoot must NEVER change, adjust or write
 * anything in the Tail app — not for today, not for past days. Tail can
 * change Hoot; Hoot cannot change Tail. The former v6 write path (broadcasts
 * into Tail's `CompanionEntryReceiver` so Hoot captures mirrored into Tail)
 * is therefore DISABLED at this single choke point: every push is a logged
 * no-op, which neutralizes all current and future call sites
 * ([com.example.hoot.data.intake.IntakeCaptureService] meals/supplements/
 * water quick-adds, AddMealScreen, AddSupplementScreen).
 *
 * What still exists:
 *  - [ACTION_ADD_TEXT_ENTRY] / [ACTION_ADD_MEAL_LOG] constants stay for
 *    documentation/back-compat with older builds' protocol notes.
 *  - [EchoRegistry] keeps working: entries pushed by v6 builds may still
 *    reflect back through Tail's text log, and the pull pipeline must keep
 *    skipping them (no double-count). New pushes no longer register.
 *
 * The read path ([TailClient], [TailSyncManager]) is untouched: Tail → Hoot
 * ingestion remains the only data bridge between the apps.
 */
class TailPushClient(private val context: Context) {

    companion object {
        private const val TAG = "TailPushClient"

        /**
         * One-way policy switch (feedback 2026-09). `false` = Hoot never
         * writes to Tail. Flip ONLY if the product decision reverses.
         */
        const val WRITE_ENABLED = false

        /** Must match Tail's [com.example.tail.ipc.CompanionEntryReceiver]. */
        const val ACTION_ADD_TEXT_ENTRY = "com.example.tail.ACTION_ADD_TEXT_ENTRY"
        const val ACTION_ADD_MEAL_LOG = "com.example.tail.ACTION_ADD_MEAL_LOG"

        const val EXTRA_HABIT_NAME = "EXTRA_HABIT_NAME"
        const val EXTRA_TEXT = "EXTRA_TEXT"
        const val EXTRA_TIMESTAMP = "EXTRA_TIMESTAMP"
        const val EXTRA_ENTRY_ID = "EXTRA_ENTRY_ID"
        const val EXTRA_MEAL_JSON = "EXTRA_MEAL_JSON"

        const val TAIL_PACKAGE = "com.example.tail"
        const val PERMISSION_TAIL_INTEGRATION =
            "com.example.tail.permission.TAIL_INTEGRATION"
    }

    /**
     * DISABLED under one-way sync (see [WRITE_ENABLED]): logs and returns
     * without touching Tail, so a Hoot-side meal/supplement/water change can
     * never adjust any Tail habit — today or in the past.
     */
    suspend fun pushTextEntry(habitName: String, text: String, timestampMs: Long) =
        withContext(Dispatchers.IO) {
            if (!WRITE_ENABLED) {
                Log.d(TAG, "one-way sync: text push to '$habitName' suppressed")
                return@withContext
            }
            val entryId = "hoot:${java.util.UUID.randomUUID()}"
            val intent = Intent(ACTION_ADD_TEXT_ENTRY).apply {
                setPackage(TAIL_PACKAGE)
                putExtra(EXTRA_HABIT_NAME, habitName)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_TIMESTAMP, timestampMs)
                putExtra(EXTRA_ENTRY_ID, entryId)
            }
            val delivered = try {
                context.sendBroadcast(intent, PERMISSION_TAIL_INTEGRATION)
                true   // fire-and-forget: Unit return, no exception = delivered
            } catch (_: Exception) {
                false
            }
            // Record BEFORE the echo pull can race ahead of this coroutine:
            // Tail's write fires its change feed immediately, so the pull that
            // ingests the reflection may start before sendBroadcast returns.
            EchoRegistry.remember(habitName, entryId, timestampMs, delivered)
            Log.d(TAG, "pushTextEntry '$habitName' ('$text') delivered=$delivered id=$entryId")
        }

    /**
     * Push one structured meal (the [com.example.hoot.domain.intake.CapturedMeal]
     * interchange shape, docs/TAIL_REQUEST.md §R2). Tail preserves the
     * `hoot:` entry id as its MealLog id (→ provider `entry_id`), which makes
     * meal echoes recognisable even without the registry.
     */
    suspend fun pushMeal(
        habitName: String,
        title: String,
        summary: String?,
        calories: Int,
        proteinGrams: Double,
        carbsGrams: Double,
        fatGrams: Double,
        ingredients: List<String>,
        isVegan: Boolean,
        healthNotes: String?,
        timestampMs: Long
    ) = withContext(Dispatchers.IO) {
        if (!WRITE_ENABLED) {
            Log.d(TAG, "one-way sync: meal push to '$habitName' suppressed")
            return@withContext
        }
        val entryId = "hoot:${java.util.UUID.randomUUID()}"
        val json = JSONObject().apply {
            put("title", title)
            summary?.let { put("summary", it) }
            put("calories", calories)
            put("protein_grams", proteinGrams)
            put("carbs_grams", carbsGrams)
            put("fat_grams", fatGrams)
            put("ingredients", JSONArray(ingredients))
            put("is_vegan", isVegan)
            healthNotes?.let { put("health_notes", it) }
            put("is_manual", false)
        }
        val intent = Intent(ACTION_ADD_MEAL_LOG).apply {
            setPackage(TAIL_PACKAGE)
            putExtra(EXTRA_HABIT_NAME, habitName)
            putExtra(EXTRA_MEAL_JSON, json.toString())
            putExtra(EXTRA_TIMESTAMP, timestampMs)
            putExtra(EXTRA_ENTRY_ID, entryId)
        }
        val delivered = try {
            context.sendBroadcast(intent, PERMISSION_TAIL_INTEGRATION)
            true   // fire-and-forget: Unit return, no exception = delivered
        } catch (_: Exception) {
            false
        }
        EchoRegistry.remember(habitName, entryId, timestampMs, delivered)
        Log.d(TAG, "pushMeal '$habitName' '$title' delivered=$delivered id=$entryId")
    }
}

/**
 * Append-only registry of entries Hoot has pushed to Tail — the ECHO FILTER
 * for the pull pipeline. When Tail's change feed makes [TailSyncManager]
 * pull, rows that are the reflection of Hoot's own push must not be
 * re-ingested (a water quick-add would otherwise double-count, a supplement
 * duplicate itself).
 *
 * Two lookup flavours, because Tail's provider exposes different ids:
 *  - **Meals** — Tail preserves the `hoot:…` id as its MealLog id, which
 *    surfaces as `entry_id`. Recognition is reinstall-proof via the
 *    [isEchoEntryId] prefix check; no registry needed.
 *  - **Text rows** (pills/water/misc) — Tail's text log has no id slot; the
 *    provider derives a SHA-256 id from (habit, timestamp). The registry
 *    keys the echo by (habit, second-truncated event ts) — [isKnownTextEcho].
 *
 * Stored in `files/tail_push_registry.json` (survives reinstall via Android
 * auto-backup; Hoot has allowBackup=true), mirrored into an in-memory set at
 * [init] so context-free readers ([TailSyncManager]) never touch disk.
 * Capped (oldest evicted) and purely additive — never blocks user actions.
 */
object EchoRegistry {

    private const val TAG = "TailEchoRegistry"
    private const val FILE = "tail_push_registry.json"
    private const val MAX_ENTRIES = 2000

    @Volatile private var appContext: Context? = null

    /** (habit|secondTs) keys of pushed text entries (the echo filter). */
    private val textEchoKeys = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    /** Called once from AppGraph init, BEFORE any sync can run. */
    fun init(context: Context) {
        appContext = context.applicationContext
        loadFromDisk()
    }

    private fun loadFromDisk() {
        val ctx = appContext ?: return
        runCatching {
            val file = File(ctx.filesDir, FILE)
            if (!file.exists()) return
            val obj = JSONObject(file.readText())
            for (k in obj.keys()) {
                val row = obj.optJSONObject(k) ?: continue
                val habit = row.optString("habit")
                val ts = row.optLong("ts")
                if (habit.isNotBlank() && ts > 0) textEchoKeys += textKey(habit, ts)
            }
        }.onFailure { Log.w(TAG, "registry load failed: ${it.message}") }
    }

    /**
     * Records a push (both file and memory). [delivered] false (Tail absent)
     * still records — harmless, and correct if delivery raced the flag.
     */
    fun remember(habitName: String, entryId: String, timestampMs: Long, delivered: Boolean) {
        textEchoKeys += textKey(habitName, timestampMs)
        runCatching {
            val ctx = appContext ?: return
            val file = File(ctx.filesDir, FILE)
            val obj = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            if (!obj.has(entryId)) {
                obj.put(
                    entryId,
                    JSONObject()
                        .put("habit", habitName)
                        .put("ts", timestampMs)
                        .put("at", System.currentTimeMillis())
                        .put("delivered", delivered)
                )
                // Cap: evict oldest by push time.
                if (obj.length() > MAX_ENTRIES) {
                    val keys = mutableListOf<Pair<String, Long>>()
                    for (k in obj.keys()) keys += k to (obj.optJSONObject(k)?.optLong("at") ?: 0L)
                    keys.sortBy { it.second }
                    for ((k, _) in keys.take(obj.length() - MAX_ENTRIES)) obj.remove(k)
                }
                file.writeText(obj.toString())
            }
        }.onFailure { Log.w(TAG, "registry write failed: ${it.message}") }
    }

    /** True when a pulled MEAL row is the echo of a Hoot push (reinstall-proof). */
    fun isEchoEntryId(entryId: String?): Boolean = entryId?.startsWith("hoot:") == true

    /** True when a pulled TEXT row is the echo of a Hoot push (registry hit). */
    fun isKnownTextEcho(habitName: String, timestampMs: Long): Boolean =
        timestampMs > 0 && textKey(habitName, timestampMs) in textEchoKeys

    /**
     * The text-echo key: habit + event ts truncated to SECONDS — Tail's text
     * log keys are second-precision ("yyyy-MM-dd HH:mm:ss"), so the row that
     * comes back parses to the truncated instant.
     */
    private fun textKey(habitName: String, timestampMs: Long): String =
        "$habitName|${timestampMs / 1000 * 1000}"
}
