package com.example.hoot.ui.intake

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hoot.appGraph
import com.example.hoot.data.intake.CaptureOutcome
import com.example.hoot.data.local.entity.MealEntity
import com.example.hoot.data.local.entity.SupplementEntity
import com.example.hoot.data.local.entity.TailEntryEntity
import com.example.hoot.ui.common.todayKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** Input mode of the Intake composer (Tail-style). */
enum class IntakeMode { TEXT, PHOTO, VOICE }

/** One row of "Today's intake" (meals, supplements, water/misc, read-only). */
data class IntakeRow(
    val key: String,
    val kind: String,               // "meal" | "supplement" | "water" | "misc"
    val title: String,
    val subtitle: String?,
    val timestamp: Long,
    val calories: Int = 0,
    val proteinGrams: Double = 0.0,
    val carbsGrams: Double = 0.0,
    val fatGrams: Double = 0.0,
    val photoPath: String? = null,
    val mealId: String? = null,
    val ingredients: List<String> = emptyList(),
    val sourcesCount: Int = 0
)

/** Composer + capture status state. */
data class IntakeUiState(
    val saving: Boolean = false,
    val lastOutcomeText: String? = null,
    val processingPending: Int? = null
)

/**
 * Intake tab VM: today's meals + supplements + water/misc Tail entries as
 * read-only rows, the Tail-style composer (text / photo / voice) via
 * [com.example.hoot.data.intake.IntakeCaptureService], water quick-add.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IntakeViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app.appGraph
    private val day = todayKey()

    private val _ui = MutableStateFlow(IntakeUiState())
    val ui: StateFlow<IntakeUiState> = _ui.asStateFlow()

    val processing = graph.nutritionProcessor.state

    private val refresh = MutableStateFlow(0)

    /** Today's intake rows, newest first. */
    val rows: StateFlow<List<IntakeRow>> = combine(
        graph.meals.observeByDay(day),
        graph.meals.observeSupplementsByDay(day),
        graph.tailEntries.observeByDay(day),
        refresh
    ) { meals, supplements, tailEntries, _ ->
        buildRows(meals.sortedByDescending { it.timestamp }, supplements, tailEntries)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private suspend fun buildRows(
        meals: List<MealEntity>,
        supplements: List<SupplementEntity>,
        tailEntries: List<TailEntryEntity>
    ): List<IntakeRow> {
        val mealRows = meals.map { meal ->
            val ings = graph.meals.ingredientsForMeal(meal.id)
            IntakeRow(
                key = meal.id,
                kind = "meal",
                title = meal.title ?: meal.rawText.lineSequence().firstOrNull().orEmpty().ifBlank { "Meal" },
                subtitle = meal.summary ?: meal.rawText.take(120),
                timestamp = meal.timestamp,
                calories = meal.calories,
                proteinGrams = meal.proteinGrams,
                carbsGrams = meal.carbsGrams,
                fatGrams = meal.fatGrams,
                photoPath = meal.photoPath,
                mealId = meal.id,
                ingredients = ings.map { it.rawText },
                sourcesCount = ings.count { it.foodId != null }
            )
        }
        val suppRows = supplements.map { s ->
            IntakeRow(
                key = s.id,
                kind = "supplement",
                title = s.label,
                subtitle = s.rawText.takeIf { it != s.label },
                timestamp = s.timestamp
            )
        }
        val tailRows = tailEntries.map { e ->
            IntakeRow(
                key = e.id,
                kind = e.kind,
                title = when (e.kind) {
                    "water" -> "💧 " + (e.amount?.toInt()?.toString() ?: "") + " ml water"
                        .replace("  ", " ").trim()
                    else -> e.text.ifBlank { e.habitName }
                },
                subtitle = if (e.kind == "water") null else e.habitName.takeIf { it.isNotBlank() },
                timestamp = e.timestamp
            )
        }
        return (mealRows + suppRows + tailRows).sortedByDescending { it.timestamp }
    }

    /** Save press on the composer (meal mode). */
    fun saveMeal(description: String, photo: File?, timeText: String) {
        if (_ui.value.saving) return
        _ui.value = _ui.value.copy(saving = true, lastOutcomeText = null)
        viewModelScope.launch {
            val ts = parseTime(timeText, System.currentTimeMillis())
            when (val outcome = runCatching {
                graph.intakeCapture.captureMeal(description, photo, ts)
            }.getOrElse { CaptureOutcome.Error(it.message ?: "capture failed") }) {
                is CaptureOutcome.Analyzed -> {
                    graph.nutritionProcessor.kick()
                    val via = if (outcome.usedVision) " (photo analyzed)" else ""
                    _ui.value = _ui.value.copy(
                        saving = false,
                        lastOutcomeText = "Saved “${outcome.captured.title.ifBlank { "meal" }}”$via" +
                            " — analyzing ${outcome.ingredientCount} ingredient(s)…"
                    )
                    refresh.value++
                }
                is CaptureOutcome.SavedUnanalyzed -> {
                    graph.nutritionProcessor.kick()
                    _ui.value = _ui.value.copy(
                        saving = false,
                        lastOutcomeText = "Saved without analysis: ${outcome.reason}"
                    )
                    refresh.value++
                }
                is CaptureOutcome.Empty ->
                    _ui.value = _ui.value.copy(saving = false, lastOutcomeText = "Nothing to save")
                is CaptureOutcome.Error ->
                    _ui.value = _ui.value.copy(saving = false, lastOutcomeText = "Save failed: ${outcome.message}")
            }
        }
    }

    /** Save press in supplement quick-mode. */
    fun saveSupplements(text: String, timeText: String) {
        if (_ui.value.saving) return
        viewModelScope.launch {
            val ts = parseTime(timeText, System.currentTimeMillis())
            val count = runCatching { graph.intakeCapture.captureSupplements(text, ts) }
                .getOrElse { 0 }
            _ui.value = _ui.value.copy(
                saving = false,
                lastOutcomeText = if (count > 0) "Saved $count supplement(s) — analyzing…" else "Nothing to save"
            )
            if (count > 0) {
                graph.nutritionProcessor.kick()
                refresh.value++
            }
        }
    }

    /** +250 ml / +500 ml quick-add. */
    fun quickAddWater(ml: Int) {
        viewModelScope.launch {
            runCatching { graph.intakeCapture.quickAddWater(ml) }
            _ui.value = _ui.value.copy(lastOutcomeText = "Added $ml ml water 💧")
            refresh.value++
        }
    }

    fun clearOutcome() {
        _ui.value = _ui.value.copy(lastOutcomeText = null)
    }

    companion object {
        /** "HH:mm" → epoch millis today; blank/invalid → [fallback]. */
        fun parseTime(text: String, fallback: Long): Long {
            val t = text.trim()
            if (t.isEmpty()) return fallback
            return runCatching {
                java.time.LocalTime.parse(t).let { time ->
                    java.time.LocalDate.now().atTime(time)
                        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
            }.getOrDefault(fallback)
        }
    }
}
