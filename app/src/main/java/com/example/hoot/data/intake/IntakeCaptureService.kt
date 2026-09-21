package com.example.hoot.data.intake

import android.util.Log
import com.example.hoot.data.local.SettingsRepository
import com.example.hoot.data.local.entity.IngredientEntity
import com.example.hoot.data.local.entity.MealEntity
import com.example.hoot.data.local.entity.SupplementEntity
import com.example.hoot.data.local.entity.TailEntryEntity
import com.example.hoot.data.remote.LlmClient
import com.example.hoot.data.remote.LlmConfig
import com.example.hoot.data.repository.MealRepository
import com.example.hoot.data.repository.TailConfigRepository
import com.example.hoot.data.repository.TailEntryRepository
import com.example.hoot.domain.intake.CapturedMeal
import com.example.hoot.domain.nutrition.IntakeAggregator
import com.example.hoot.domain.intake.CapturedMealJson
import com.example.hoot.domain.intake.CapturePrompts
import com.example.hoot.domain.intake.VisionContent
import com.example.hoot.domain.nutrition.DayKeys
import com.example.hoot.domain.nutrition.IngredientParser
import com.example.hoot.domain.nutrition.SupplementListSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Result of one Intake capture ("Save" press). */
sealed interface CaptureOutcome {
    /** LLM produced a Tail-compatible structure; persisted with full detail. */
    data class Analyzed(
        val mealId: String,
        val captured: CapturedMeal,
        val ingredientCount: Int,
        /** True when the photo was actually sent to a vision model. */
        val usedVision: Boolean
    ) : CaptureOutcome

    /** LLM unavailable/unparseable — meal saved as raw text (still fully usable). */
    data class SavedUnanalyzed(
        val mealId: String,
        val reason: String
    ) : CaptureOutcome

    /** Nothing to save (blank input). */
    data object Empty : CaptureOutcome

    data class Error(val message: String) : CaptureOutcome
}

/**
 * In-app capture pipeline for the Intake tab — produces the SAME structured
 * meal Tail's capture produces (title, summary, kcal, macros,
 * ingredientsDetected, vegan flag, health notes) so results are
 * interchangeable between the two apps, then lands it in Hoot's engine the
 * same way Tail sync ingestion does:
 *
 *   input (text / voice transcript / photo) → LLM (vision when a photo is
 *   attached) → [CapturedMeal] → MealEntity + IngredientEntity rows
 *   ([MealRepository.ingestPreservingResolution]) → caller kicks
 *   [com.example.hoot.domain.nutrition.NutritionProcessor].
 *
 * Photo handling mirrors Tail: the image is downscaled + base64'd into an
 * OpenAI `image_url` content part; when the vision call fails or the LLM is
 * unconfigured, we degrade to the text path (or a raw-text save) — never a
 * silent dead end.
 */
class IntakeCaptureService(
    private val meals: MealRepository,
    private val tailEntries: TailEntryRepository,
    private val settings: SettingsRepository,
    private val tailConfig: TailConfigRepository,
    private val llm: LlmClient,
    private val aggregator: IntakeAggregator? = null   // nullable: JVM tests construct without DB
) {
    /** Text / voice / photo meal capture. [photoFile] nullable. */
    suspend fun captureMeal(
        description: String,
        photoFile: File?,
        timestamp: Long = System.currentTimeMillis()
    ): CaptureOutcome = withContext(Dispatchers.IO) {
        val text = description.trim()
        if (text.isEmpty() && photoFile == null) return@withContext CaptureOutcome.Empty

        val appSettings = runCatching { settings.current() }.getOrNull()
            ?: return@withContext persistUnanalyzed(text, photoFile, timestamp, "settings unavailable")
        val cfg = LlmConfig(appSettings.baseUrl, appSettings.apiKey, appSettings.model)

        var captured: CapturedMeal? = null
        var usedVision = false
        var failureReason: String? = null

        if (cfg.configured) {
            val diet = runCatching { tailConfig.dietaryProfile() }.getOrNull()
            val allergyList = diet?.allergiesJson?.let { json ->
                runCatching {
                    val arr = org.json.JSONArray(json)
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                }.getOrNull()
            } ?: emptyList()
            val photoDataUrl = photoFile?.let { VisionContent.encodeJpegDataUrl(it) }
            if (photoFile != null && photoDataUrl == null) {
                Log.w(TAG, "Photo encode failed — analysing text only")
            }
            val system = CapturePrompts.mealSystemPrompt(
                hasPhoto = photoDataUrl != null,
                dietaryRules = CapturePrompts.dietaryRulesLine(diet?.dietStyle, allergyList)
            )
            // 1) Vision path (photo attached)…
            if (photoDataUrl != null) {
                captured = runCatching {
                    llm.chatVision(
                        cfg, system,
                        VisionContent.userContent(
                            text.ifBlank { "Describe the attached meal photo." },
                            photoDataUrl
                        ),
                        temperature = 0.2f,
                        disableThinking = appSettings.disableThinking
                    )
                }.mapCatching { CapturedMealJson.parse(it) ?: throw IllegalStateException("unparseable") }
                    .onFailure {
                        Log.w(TAG, "Vision capture failed — falling back to text: ${it.message}")
                        failureReason = it.message
                    }
                    .getOrNull()
                usedVision = captured != null
            }
            // 2) …text path (photo failed or no photo; photo-only input keeps a hint).
            if (captured == null) {
                captured = runCatching {
                    val content = VisionContent.userContent(
                        text.ifBlank { "(photo could not be analyzed — no description given)" },
                        null
                    )
                    llm.chatVision(cfg, system, content, temperature = 0.2f,
                        disableThinking = appSettings.disableThinking)
                }.mapCatching { CapturedMealJson.parse(it) ?: throw IllegalStateException("unparseable") }
                    .onFailure {
                        Log.w(TAG, "Text capture failed: ${it.message}")
                        failureReason = it.message
                    }
                    .getOrNull()
            }
        } else {
            failureReason = "LLM not configured (Settings → LLM)"
        }

        if (captured == null) {
            return@withContext persistUnanalyzed(
                text, photoFile, timestamp,
                failureReason ?: "no analysis available", transcript = if (text.isNotEmpty()) text else null
            )
        }

        // Persist — same landing path as Tail sync ingestion.
        val mealId = "hoot:${UUID.randomUUID()}"
        val ingredients = captured.ingredientsDetected.joinToString("\n")
        val meal = MealEntity(
            id = mealId,
            tailHabitName = "",
            timestamp = timestamp,
            day = DayKeys.fromEpoch(timestamp),
            title = captured.title.ifBlank { null },
            rawText = buildRawText(captured, text),
            source = "hoot",
            summary = captured.summary?.ifBlank { null },
            calories = captured.calories,
            proteinGrams = captured.proteinGrams,
            carbsGrams = captured.carbsGrams,
            fatGrams = captured.fatGrams,
            isVegan = captured.isVeganVerified,
            healthNotes = captured.healthNotes,
            transcript = if (text.isNotEmpty()) text else null,
            photoPath = photoFile?.absolutePath
        )
        meals.ingestPreservingResolution(listOf(meal), emptyList(), ingredientRows(mealId, ingredients))
        CaptureOutcome.Analyzed(
            mealId = mealId,
            captured = captured,
            ingredientCount = captured.ingredientsDetected.size,
            usedVision = usedVision
        )
    }

    /** Supplement quick-mode: text → individual SupplementEntity rows. Returns row count. */
    suspend fun captureSupplements(
        text: String,
        timestamp: Long = System.currentTimeMillis()
    ): Int = withContext(Dispatchers.IO) {
        val items = SupplementListSplitter.split(text)
        if (items.isEmpty()) return@withContext 0
        val rows = items.mapIndexed { index, item ->
            SupplementEntity(
                id = "hoot:${UUID.randomUUID()}",
                label = item.take(80),
                description = null,
                resolvedFoodId = null,
                doseAmount = null,
                doseUnit = null,
                nutrientContributions = "[]",
                tailHabitName = "",
                timestamp = timestamp,
                day = DayKeys.fromEpoch(timestamp),
                rawText = item,
                source = "hoot"
            )
        }
        meals.ingestPreservingResolution(emptyList(), rows)
        rows.size
    }

    /**
     * Water quick-add — a local `tail_entries` row (kind=water), Tail-compatible.
     * When an [aggregator] is wired, the day's ledger is recomputed immediately
     * so the water stat + goal % update without waiting for the next full pass
     * (water was previously ledger-invisible: Home showed "0 L").
     */
    suspend fun quickAddWater(ml: Int, timestamp: Long = System.currentTimeMillis()) {
        val text = "$ml ml"
        val day = DayKeys.fromEpoch(timestamp)
        tailEntries.upsertAll(
            listOf(
                TailEntryEntity(
                    id = "local:water:${UUID.randomUUID()}",
                    kind = KIND_LOCAL_WATER,
                    habitName = "Water",
                    timestamp = timestamp,
                    day = day,
                    text = text,
                    amount = ml.toDouble(),
                    unit = "ml"
                )
            )
        )
        aggregator?.let { agg ->
            runCatching { agg.recomputeDays(listOf(day)) }
                .onFailure { Log.e(TAG, "water quick-add ledger recompute failed", it) }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private suspend fun persistUnanalyzed(
        text: String,
        photoFile: File?,
        timestamp: Long,
        reason: String,
        transcript: String? = null
    ): CaptureOutcome {
        val mealId = "hoot:${UUID.randomUUID()}"
        val meal = MealEntity(
            id = mealId,
            tailHabitName = "",
            timestamp = timestamp,
            day = DayKeys.fromEpoch(timestamp),
            title = null,
            rawText = text.ifBlank { "(photo capture — ${reason})" },
            source = "hoot",
            transcript = transcript,
            photoPath = photoFile?.absolutePath
        )
        meals.ingestPreservingResolution(
            listOf(meal), emptyList(),
            ingredientRows(mealId, text)  // deterministic parser still gets rows to work with
        )
        return CaptureOutcome.SavedUnanalyzed(mealId, reason)
    }

    /** Ingredient rows from an ingredientsDetected-style block (Tail convention). */
    private fun ingredientRows(mealId: String, block: String): List<IngredientEntity> =
        IngredientParser.parse(block).map { p ->
            IngredientEntity(
                id = "$mealId#${p.rawText.trim().lowercase().hashCode().toUInt()}",
                mealId = mealId,
                rawText = p.rawText,
                foodId = null,
                amount = p.quantity,
                unit = p.unit,
                gramsEstimate = null
            )
        }

    /** Meal raw text mirrors Tail sync's buildMealRawText shape. */
    private fun buildRawText(captured: CapturedMeal, transcript: String): String = buildString {
        append(captured.title.ifBlank { "Captured meal" })
        if (captured.calories > 0) append(" (").append(captured.calories).append(" kcal)")
        if (captured.ingredientsDetected.isNotEmpty()) {
            append("\n").append(captured.ingredientsDetected.joinToString("\n"))
        }
        val summary = captured.summary?.takeIf { it.isNotBlank() && it != captured.title }
        if (summary != null) append("\n").append(summary)
        if (transcript.isNotBlank()) append("\n— \"").append(transcript).append("\"")
    }

    companion object {
        private const val TAG = "IntakeCapture"

        /** [TailEntryEntity.kind] for in-app water quick-adds (source=local rows). */
        const val KIND_LOCAL_WATER = "water"
    }
}
