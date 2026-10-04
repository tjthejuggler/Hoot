package com.example.hoot.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.hoot.data.local.HootDatabase
import com.example.hoot.data.local.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Daily TailCue export.
 *
 * Writes one JSON file per calendar day into the user-picked SAF folder
 * (the Syncthing-synced habitsdb folder — the same pipeline Tail and Wags
 * use). TailCue on the PC picks the newest `hoot_auto_export_*.json` and
 * turns it into daily "Hoot: …" feature metrics.
 *
 * Format (see TailCue backend/app/ingest_apps.py :: ingest_hoot):
 * {
 *   "generated_at": "<iso>",
 *   "days":         { "2026-09-30": { "meals": 3, "supplements": 2, "score": 78.5 } },
 *   "nutrients":    { "2026-09-30": { "protein": 82.4, "magnesium": 310.0, … } },
 *   "definitions":  { "protein": {"name":"Protein","unit":"g"}, … }
 * }
 *
 * * At most one file per calendar day (same-day file is replaced).
 * * Fully additive read-only export; nothing in Hoot's own DB is touched.
 * * Failures are logged and swallowed — export must never break startup.
 */
class AutoExportManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val database: HootDatabase,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "HootAutoExport"
        private const val FILE_PREFIX = "hoot_auto_export_"
        private const val FILE_SUFFIX = ".json"
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    }

    /** Safe startup hook — returns immediately, does all work on IO. */
    fun runOnStartup() {
        scope.launch {
            try {
                exportIfNeeded()
            } catch (t: Throwable) {
                Log.w(TAG, "Auto-export failed: ${t.message}", t)
            }
        }
    }

    suspend fun exportIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        val dirUri = settings.current().tailcueExportDirUri
        if (dirUri.isBlank()) return@withContext false

        val json = buildJson()
        val name = FILE_PREFIX + DATE_FORMAT.format(Date()) + FILE_SUFFIX

        val dir = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, Uri.parse(dirUri))
            ?: return@withContext false
        dir.findFile(name)?.delete()
        val target = dir.createFile("application/json", name) ?: return@withContext false
        context.contentResolver.openOutputStream(target.uri)?.use { os ->
            os.write(json.toString().toByteArray(Charsets.UTF_8))
        } ?: return@withContext false
        Log.i(TAG, "Auto-export written: $name (${json.length()} chars)")
        true
    }

    private suspend fun buildJson(): JSONObject {
        val intakeDao = database.intakeDao()
        val mealDao = database.mealDao()
        val nutrientDao = database.nutrientDao()

        val nutrients = JSONObject()          // day -> { nutrientId -> total }
        val days = JSONObject()               // day -> { meals, supplements, score }
        for (row in intakeDao.dailyTotalsRange("0000-00-00", "9999-99-99")) {
            val day = row.day ?: continue
            val perDay = nutrients.optJSONObject(day) ?: JSONObject().also { nutrients.put(day, it) }
            perDay.put(row.nutrientId, round(row.total))
        }

        val supplementDao = database.supplementDao()
        for (day in mealDao.distinctDays()) {
            val agg = JSONObject()
            agg.put("meals", mealDao.byDay(day).size)
            agg.put("supplements", supplementDao.byDay(day).size)
            days.put(day, agg)
        }
        for (snap in database.scoreSnapshotDao().observeFrom("0000-00-00").first()) {
            val agg = days.optJSONObject(snap.day)
                ?: JSONObject().also { days.put(snap.day, it) }
            agg.put("score", round(snap.score))
        }

        val definitions = JSONObject()
        for (def in nutrientDao.all()) {
            val d = JSONObject()
            d.put("name", def.name)
            d.put("unit", def.unit)
            d.put("group", def.group)
            d.put("tier", def.tier)
            definitions.put(def.id, d)
        }

        val root = JSONObject()
        root.put("generated_at", ISO.format(Date()))
        root.put("days", days)
        root.put("nutrients", nutrients)
        root.put("definitions", definitions)
        return root
    }

    private fun round(v: Double): Double = Math.round(v * 100.0) / 100.0
}
