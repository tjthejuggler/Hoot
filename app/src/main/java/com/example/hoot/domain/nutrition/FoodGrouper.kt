package com.example.hoot.domain.nutrition

/**
 * Pure grouping/batching math for the per-FOOD resolution pipeline (the 891-row
 * → ~74-foods fix). Resolution work must be per DISTINCT normalized foodKey,
 * not per ingredient row; this object holds the deterministic pieces of that
 * transformation so they stay unit-testable without Android or Room.
 */
object FoodGrouper {

    /** One unresolved-ingredient row flattened to what the group step needs. */
    data class IngredientRow(
        val id: String,
        val rawText: String,
        val mealDay: String?      // parent meal's day key (ledger scope)
    )

    /** One unresolved-supplement row flattened for grouping. */
    data class SupplementRow(
        val id: String,
        val label: String,
        val rawText: String,
        val day: String           // "" → today at drain time
    )

    /** A distinct food workload: one normalized key + every row sharing it. */
    data class FoodGroup(
        val foodKey: String,
        val displayName: String,          // first raw text's pretty form
        val ingredientIds: List<String>,
        val touchedDays: Set<String>      // distinct days whose ledger changes
    )

    /** Distinct supplement name-group (same name AND same dose → same panel). */
    data class SupplementGroup(
        val groupKey: String,             // name + dose discriminator (prompt key)
        val normalizedName: String,
        val displayName: String,
        val supplementIds: List<String>,
        val touchedDays: Set<String>
    )

    /**
     * Groups unresolved ingredient rows by their normalized foodKey
     * ([FoodNormalizer.normalize] over the raw text — the same key the
     * resolver and LookupCache use, so cache hits line up exactly).
     *
     * Rows whose key is blank can never be resolved; they are dropped (the
     * caller marks them failed, as the old per-row path did immediately).
     */
    fun groupIngredients(rows: List<IngredientRow>): List<FoodGroup> {
        val byKey = LinkedHashMap<String, MutableList<IngredientRow>>()
        for (row in rows) {
            val key = FoodNormalizer.normalize(row.rawText)
            if (key.isBlank()) continue
            byKey.getOrPut(key) { mutableListOf() }.add(row)
        }
        return byKey.map { (key, groupRows) ->
            FoodGroup(
                foodKey = key,
                displayName = FoodNormalizer.displayName(groupRows.first().rawText),
                ingredientIds = groupRows.map { it.id },
                touchedDays = groupRows.mapNotNull { it.mealDay }.toSet()
            )
        }
    }

    /**
     * Groups unresolved supplements by normalized label. Contributions are
     * PER SERVING, so groups also split on the parsed dose ("Magnesium 400 mg"
     * and "Magnesium 200 mg" normalize to the same name but must never share
     * one LLM panel). Direct-label parses stay free per row; grouping only
     * batches the LLM-assist remainder.
     */
    fun groupSupplements(rows: List<SupplementRow>): List<SupplementGroup> {
        val byKey = LinkedHashMap<String, MutableList<SupplementRow>>()
        for (row in rows) {
            val text = row.label.ifBlank { row.rawText }
            val key = supplementGroupKey(text)
            if (key.normalizedName.isBlank()) continue
            byKey.getOrPut(key.groupKey) { mutableListOf() }.add(row)
        }
        return byKey.map { (groupKey, groupRows) ->
            val first = groupRows.first()
            SupplementGroup(
                groupKey = groupKey,
                normalizedName = FoodNormalizer.normalize(first.label.ifBlank { first.rawText }),
                displayName = FoodNormalizer.displayName(first.label.ifBlank { first.rawText }),
                supplementIds = groupRows.map { it.id },
                touchedDays = groupRows.map { it.day }.filter { it.isNotBlank() }.toSet()
            )
        }
    }

    /** Composite grouping key: normalized name + parsed dose ("" when absent). */
    internal fun supplementGroupKey(text: String): SupplementGroupKey {
        val parsed = SupplementLabelParser.parse(text)
        val name = parsed?.normalizedName ?: FoodNormalizer.normalize(text)
        val dose = parsed?.doseAmount
        val unit = parsed?.doseUnit
        val discriminator = if (dose != null && !unit.isNullOrBlank())
            "|${trimDose(dose)}|$unit" else ""
        return SupplementGroupKey(groupKey = "$name$discriminator", normalizedName = name)
    }

    /** (groupKey, normalizedName) pair — keeps call sites readable. */
    data class SupplementGroupKey(val groupKey: String, val normalizedName: String)

    /** 400.0 → "400" (stable discriminator formatting). */
    internal fun trimDose(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    /** Partitions [items] into consecutive batches of at most [size] (size ≥ 1). */
    fun <T> batch(items: List<T>, size: Int): List<List<T>> {
        val n = size.coerceAtLeast(1)
        if (items.isEmpty()) return emptyList()
        return items.chunked(n)
    }

    /**
     * Coverage math for a resolution run: given the total distinct groups and
     * how many finished (resolved or attempt-failed), how many distinct foods
     * remain — the number the Today chip shows.
     */
    fun remainingCount(totalGroups: Int, processed: Int): Int =
        (totalGroups - processed).coerceAtLeast(0)
}
