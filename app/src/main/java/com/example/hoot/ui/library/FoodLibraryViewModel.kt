package com.example.hoot.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hoot.appGraph
import com.example.hoot.data.local.entity.FoodEntity
import com.example.hoot.data.local.entity.FoodNutrientProfileEntity
import com.example.hoot.data.local.entity.NutrientDefinitionEntity
import com.example.hoot.domain.nutrition.NutrientKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/** Filter chips above the library list. */
enum class FoodLibraryFilter(val label: String) {
    ALL("All"),
    WITH_DATA("With data"),
    WITHOUT_DATA("No data")
}

/** One row of the library list. */
data class FoodLibraryRow(
    val food: FoodEntity,
    val profile: FoodNutrientProfileEntity?,
    val valueCount: Int,
    /** "Protein 12 g · Iron 2.1 mg · …" (top nutrients by tier) or a hint. */
    val summary: String
)

/** One editable nutrient row inside the per-food editor sheet. */
data class NutrientEditRow(
    val key: String,        // canonical nutrient id (or raw panel key if unknown)
    val displayName: String,
    val unit: String,
    val valueText: String   // blank = drop the nutrient on save
)

/**
 * Full state of the per-food editor sheet: identity, per-basis and the
 * nutrient rows parsed from [FoodNutrientProfileEntity.valuesJson], plus the
 * seeded definitions that are NOT yet on the food (the "add" picker).
 */
data class FoodEditState(
    val food: FoodEntity,
    val profile: FoodNutrientProfileEntity?,
    val rows: List<NutrientEditRow>,
    val perAmountText: String,
    val perUnit: String,
    val nameText: String,
    /** Full seeded-definition snapshot (tier/order source for the editor). */
    val defs: List<NutrientDefinitionEntity>,
    val addable: List<NutrientDefinitionEntity>,
    val dirty: Boolean
)

/**
 * Food library (Settings entry): browse every food Hoot has resolved with its
 * per-100 g nutrient panel, and edit any association by hand — change values,
 * add/remove nutrients, fix the per-basis, rename the food, or wipe the
 * panel so it re-resolves. Manual edits are written with
 * `resolutionMethod="manual"`, `confidence=1.0`, exactly like the pipeline's
 * self-heal path.
 */
class FoodLibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app.appGraph

    // ---- List surface ------------------------------------------------------

    val search = MutableStateFlow("")
    val filter = MutableStateFlow(FoodLibraryFilter.ALL)

    val rows: StateFlow<List<FoodLibraryRow>> = combine(
        graph.foods.observeAllFoods(),
        graph.foods.observeProfiles(),
        graph.nutrientDefinitions.observeDefinitions(),
        search,
        filter
    ) { foods, profiles, definitions, query, mode ->
        val defById = definitions.associateBy { it.id }
        val byFood = profiles.associateBy { it.foodId }
        val q = query.trim().lowercase()
        foods.mapNotNull { food ->
            val profile = byFood[food.id]
            if (mode == FoodLibraryFilter.WITH_DATA && profile == null) return@mapNotNull null
            if (mode == FoodLibraryFilter.WITHOUT_DATA && profile != null) return@mapNotNull null
            if (q.isNotEmpty() &&
                !food.displayName.lowercase().contains(q) &&
                !food.normalizedName.contains(q)
            ) return@mapNotNull null
            val (count, summary) = summarize(profile, defById)
            FoodLibraryRow(food, profile, count, summary)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- Editor surface ------------------------------------------------------

    private val _edit = MutableStateFlow<FoodEditState?>(null)
    val edit: StateFlow<FoodEditState?> = _edit.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Snapshot used for the dirty badge (name + basis + row signature). */
    private var original: EditOriginal? = null

    private data class EditOriginal(
        val name: String,
        val perAmount: String,
        val perUnit: String,
        val rows: String
    )

    fun setSearch(q: String) { search.value = q }
    fun setFilter(f: FoodLibraryFilter) { filter.value = f }
    fun clearMessage() { _message.value = null }

    fun openEditor(foodId: String) {
        viewModelScope.launch {
            val food = graph.foods.food(foodId) ?: return@launch
            val profile = graph.foods.profileForFood(foodId)
            val defs = graph.nutrientDefinitions.definitionsAll()
                .filter { it.id !in NutrientKeys.DERIVED_IDS }
            _edit.value = buildEditState(food, profile, defs)
        }
    }

    fun closeEditor() {
        _edit.value = null
        original = null
    }

    fun updateName(text: String) = rebuild { it.copy(nameText = text) }

    fun updatePerAmount(text: String) = rebuild { it.copy(perAmountText = text) }

    fun updatePerUnit(text: String) = rebuild { it.copy(perUnit = text) }

    fun updateEditValue(key: String, text: String) = rebuild { state ->
        state.copy(rows = state.rows.map { if (it.key == key) it.copy(valueText = text) else it })
    }

    fun removeEditRow(key: String) = rebuild { state ->
        state.copy(rows = state.rows.filterNot { it.key == key })
    }

    fun addEditNutrient(def: NutrientDefinitionEntity) = rebuild { state ->
        if (state.rows.any { it.key == def.id }) return@rebuild state
        val row = NutrientEditRow(def.id, def.name, def.unit, "")
        val tier = { id: String -> state.defs.firstOrNull { it.id == id }?.tier ?: 99 }
        val sorted = (state.rows + row).sortedWith(
            compareBy({ tier(it.key) }, { it.displayName.lowercase() })
        )
        state.copy(rows = sorted, addable = state.addable - def)
    }

    /**
     * Persists the edited panel: blank values drop nutrients, unparseable
     * values abort with a snackbar; manual saves carry confidence 1.0 and
     * `resolutionMethod="manual"` so later cache refreshes won't fight them.
     */
    fun saveEdit() {
        val state = _edit.value ?: return
        viewModelScope.launch {
            val values = JSONObject()
            for (row in state.rows) {
                val raw = row.valueText.trim().replace(',', '.')
                if (raw.isEmpty()) continue // blank = drop
                val v = raw.toDoubleOrNull()
                if (v == null || v.isNaN() || v < 0.0) {
                    _message.value = "“${row.displayName}” is not a valid number"
                    return@launch
                }
                values.put(row.key, v)
            }
            val perAmount = state.perAmountText.trim().replace(',', '.').toDoubleOrNull()
            if (perAmount == null || perAmount <= 0.0) {
                _message.value = "“Per amount” must be a positive number"
                return@launch
            }
            graph.foods.upsertProfile(
                FoodNutrientProfileEntity(
                    id = state.profile?.id ?: UUID.randomUUID().toString(),
                    foodId = state.food.id,
                    perAmount = perAmount,
                    perUnit = state.perUnit.trim().ifBlank { "g" },
                    valuesJson = values.toString(),
                    confidence = 1.0,
                    resolutionMethod = "manual"
                )
            )
            var name = state.food.displayName
            val newName = state.nameText.trim()
            if (newName.isNotEmpty() && newName != state.food.displayName) {
                graph.foods.upsertFood(state.food.copy(displayName = newName))
                name = newName
            }
            _message.value = "Saved “$name”"
            openEditor(state.food.id) // rebuild from what was persisted
        }
    }

    /** Removes the whole panel; the next lookup re-resolves from scratch. */
    fun deleteAssociation() {
        val state = _edit.value ?: return
        val profileId = state.profile?.id ?: return
        viewModelScope.launch {
            graph.foods.deleteProfile(profileId)
            _message.value = "Nutrition data cleared — it will re-resolve on the next lookup"
            closeEditor()
        }
    }

    // ---- Internals -----------------------------------------------------------

    private fun buildEditState(
        food: FoodEntity,
        profile: FoodNutrientProfileEntity?,
        defs: List<NutrientDefinitionEntity>
    ): FoodEditState {
        val defById = defs.associateBy { it.id }
        val rows = parseRows(profile, defById)
        val state = FoodEditState(
            food = food,
            profile = profile,
            rows = rows,
            perAmountText = profile?.let { fmt(it.perAmount) } ?: "100",
            perUnit = profile?.perUnit ?: "g",
            nameText = food.displayName,
            defs = defs,
            addable = defs.filter { d -> rows.none { it.key == d.id } },
            dirty = false
        )
        original = EditOriginal(
            name = state.nameText,
            perAmount = state.perAmountText,
            perUnit = state.perUnit,
            rows = rowSignature(rows)
        )
        return state
    }

    private fun rebuild(transform: (FoodEditState) -> FoodEditState) {
        val cur = _edit.value ?: return
        val next = transform(cur)
        val orig = original
        _edit.value = next.copy(
            dirty = orig == null ||
                next.nameText != orig.name ||
                next.perAmountText != orig.perAmount ||
                next.perUnit != orig.perUnit ||
                rowSignature(next.rows) != orig.rows
        )
    }

    private fun rowSignature(rows: List<NutrientEditRow>): String =
        rows.joinToString("|") { "${it.key}=${it.valueText.trim().replace(',', '.')}" }

    private fun parseRows(
        profile: FoodNutrientProfileEntity?,
        defById: Map<String, NutrientDefinitionEntity>
    ): List<NutrientEditRow> {
        if (profile == null) return emptyList()
        val json = runCatching { JSONObject(profile.valuesJson) }.getOrNull() ?: return emptyList()
        data class Tmp(
            val key: String, val name: String, val unit: String,
            val text: String, val tier: Int, val lower: String
        )
        return json.keys().asSequence().map { rawKey ->
            val def = defById[rawKey] ?: NutrientKeys.canonicalId(rawKey)?.let { defById[it] }
            val v = json.optDouble(rawKey, Double.NaN)
            Tmp(
                key = def?.id ?: rawKey,
                name = def?.name ?: prettifyKey(rawKey),
                unit = def?.unit ?: "",
                text = if (v.isNaN()) "" else fmt(v),
                tier = def?.tier ?: 99,
                lower = (def?.name ?: prettifyKey(rawKey)).lowercase()
            )
        }.sortedWith(compareBy({ it.tier }, { it.lower }))
            .map { NutrientEditRow(it.key, it.name, it.unit, it.text) }
            .toList()
    }

    private fun summarize(
        profile: FoodNutrientProfileEntity?,
        defById: Map<String, NutrientDefinitionEntity>
    ): Pair<Int, String> {
        if (profile == null) return 0 to "No nutrition data yet"
        val json = runCatching { JSONObject(profile.valuesJson) }.getOrNull() ?: return 0 to "—"
        val keys = json.keys().asSequence().toList()
        if (keys.isEmpty()) return 0 to "Empty panel"
        data class Entry(val tier: Int, val lower: String, val text: String)
        val entries = keys.map { k ->
            val def = defById[k] ?: NutrientKeys.canonicalId(k)?.let { defById[it] }
            val name = def?.name ?: prettifyKey(k)
            val unit = def?.unit.orEmpty()
            Entry(
                tier = def?.tier ?: 99,
                lower = name.lowercase(),
                text = "$name ${fmtOrDash(json.optDouble(k, Double.NaN))}" +
                    if (unit.isEmpty()) "" else " $unit"
            )
        }.sortedWith(compareBy({ it.tier }, { it.lower }))
        return keys.size to entries.take(3).joinToString(" · ") { it.text }
    }
}

/** Shortest stable double rendering ("12", "12.35"). */
internal fun fmt(v: Double): String = runCatching {
    java.math.BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
}.getOrDefault(v.toString())

private fun fmtOrDash(v: Double): String = if (v.isNaN()) "—" else fmt(v)

private fun prettifyKey(key: String): String =
    key.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }
