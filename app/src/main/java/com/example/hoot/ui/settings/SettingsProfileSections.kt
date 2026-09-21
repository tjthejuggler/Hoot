package com.example.hoot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.hoot.data.local.AppSettings

// ── Dietary profile section ──────────────────────────────────────────────────

/** Canonical restriction labels offered as multi-select chips. */
private val DIET_RESTRICTIONS = listOf(
    "Vegan", "Vegetarian", "Pescatarian", "Gluten-free", "Dairy-free",
    "Nut allergy", "Shellfish allergy", "Soy allergy", "Egg allergy",
    "Halal", "Kosher", "Low-sodium", "Diabetic/low-sugar"
)

/** Style chips — persisted as `diet_style`, NOT as allergy entries. */
internal val STYLE_CHIPS: Set<String> = setOf(
    "Vegan", "Vegetarian", "Pescatarian", "Dairy-free", "Gluten-free", "Kosher", "Halal"
)

/**
 * The three LIFESTYLE chips. As allergy keywords they are no-ops (no
 * exclusion vocabulary contains "vegan"/"vegetarian"/"pescatarian"), so they
 * are REMOVED from the allergies set on save and represented solely by
 * `diet_style`. Secondary style chips (Dairy-free / Gluten-free / Kosher /
 * Halal) STAY in the allergies set too — Dairy-free/Gluten-free filter via
 * their allergy synonym groups and may legitimately combine with a primary
 * style (e.g. Vegan + Gluten-free).
 */
internal val LIFESTYLE_CHIPS: Set<String> = setOf("Vegan", "Vegetarian", "Pescatarian")

/** `diet_style` value → the chip label that represents it (null = none). */
internal fun styleChipFor(style: String?): String? = when (style?.trim()?.lowercase()) {
    "vegan" -> "Vegan"
    "vegetarian" -> "Vegetarian"
    "pescatarian" -> "Pescatarian"
    "dairy-free", "lactose-free" -> "Dairy-free"
    "gluten-free" -> "Gluten-free"
    "kosher" -> "Kosher"
    "halal" -> "Halal"
    else -> null
}

/**
 * Derives the persisted diet-STYLE value from the selected restriction chips
 * (diet-leak root-cause fix, 2026-09). The Save button previously passed an
 * EMPTY style while "Vegan"/"Vegetarian"/… lived only in the allergies set —
 * and no exclusion keyword matches "vegan", so every downstream filter ran as
 * OMNIVORE while the Settings UI showed the chip selected. Most restrictive
 * wins when several lifestyle chips are selected; secondary style chips only
 * become the style when no lifestyle chip is present.
 */
internal fun derivedDietStyle(selected: Set<String>): String = when {
    "Vegan" in selected -> "vegan"
    "Vegetarian" in selected -> "vegetarian"
    "Pescatarian" in selected -> "pescatarian"
    "Dairy-free" in selected -> "dairy-free"
    "Gluten-free" in selected -> "gluten-free"
    "Kosher" in selected -> "kosher"
    "Halal" in selected -> "halal"
    else -> ""
}

/**
 * Dietary restrictions (multi-select chips + custom add) and disliked foods
 * (free text). Saved on button into DataStore AND mirrored to the
 * dietary_profile Room row the recommender filters on.
 */
@Composable
internal fun DietarySection(
    settings: AppSettings,
    onSave: (style: String, allergies: Set<String>, dislikes: Set<String>) -> Unit
) {
    // Selected set: canonical labels + any previously persisted custom entries.
    // diet-leak fix (2026-09): also re-select the STYLE chip that represents
    // the persisted diet_style so a vegan profile shows "Vegan" selected.
    var selected by remember(settings.dietAllergies, settings.dietStyle) {
        mutableStateOf(settings.dietAllergies + setOfNotNull(styleChipFor(settings.dietStyle)))
    }
    var customAdd by rememberSaveable { mutableStateOf("") }
    var dislikes by rememberSaveable(settings.dietDislikes) {
        mutableStateOf(settings.dietDislikes.joinToString(", "))
    }

    val dirty = (selected - LIFESTYLE_CHIPS) != settings.dietAllergies ||
        derivedDietStyle(selected) != settings.dietStyle.trim().lowercase().takeIf { it != "omnivore" && it != "none" }.orEmpty() ||
        dislikes.split(',').mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }.toSet() !=
        settings.dietDislikes

    SectionCard(
        title = "Dietary profile",
        subtitle = "filters recommendations & flags allergen conflicts"
    ) {
        Text("Restrictions", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        // Flowing chip grid: rows of 3.
        DIET_RESTRICTIONS.chunked(3).forEach { rowLabels ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowLabels.forEach { label ->
                    FilterChip(
                        selected = label in selected,
                        onClick = {
                            selected = if (label in selected) selected - label else selected + label
                        },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = customAdd,
                onValueChange = { customAdd = it },
                label = { Text("Custom restriction") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            AssistChip(
                onClick = {
                    val v = customAdd.trim()
                    if (v.isNotEmpty()) {
                        selected = selected + v
                        customAdd = ""
                    }
                },
                label = { Text("Add") },
                enabled = customAdd.isNotBlank()
            )
        }
        // Custom (non-canonical) selections as removable chips.
        val custom = selected - DIET_RESTRICTIONS.toSet()
        if (custom.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            custom.chunked(3).forEach { rowLabels ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowLabels.forEach { label ->
                        AssistChip(
                            onClick = { selected = selected - label },
                            label = { Text("✕ $label", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = dislikes,
            onValueChange = { dislikes = it },
            label = { Text("Disliked foods (comma-separated)") },
            placeholder = { Text("e.g. cilantro, blue cheese, liver") },
            minLines = 1,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // diet-leak root-cause fix (2026-09): the style must be derived
            // from the selected STYLE chips ("Vegan"…) instead of the previous
            // hard-coded "" that always persisted diet_style=omnivore while
            // the chip merely sat in dietAllergies.
            Button(onClick = {
                onSave(derivedDietStyle(selected), selected - LIFESTYLE_CHIPS, parsedSet(dislikes))
            }) { Text("Save") }
            UnsavedBadge(dirty)
        }
    }
}

private fun parsedSet(raw: String): Set<String> =
    raw.split(',').mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }.toSet()

// ── Personal stats section ───────────────────────────────────────────────────

/**
 * Sex / age / height / weight — persisted for RDA adjustments. NOTE (TODO
 * hook): the score engine still applies the static adult RDA from
 * nutrient_definitions; sex/weight-dependent targets are not wired yet.
 */
@Composable
internal fun PersonalStatsSection(
    settings: AppSettings,
    onSave: (weightKg: Float, heightCm: Float, ageYears: Int, sex: String) -> Unit
) {
    var sex by rememberSaveable(settings.userSex) { mutableStateOf(settings.userSex) }
    var age by rememberSaveable(settings.userAgeYears) { mutableStateOf(if (settings.userAgeYears > 0) settings.userAgeYears.toString() else "") }
    var height by rememberSaveable(settings.userHeightCm) { mutableStateOf(if (settings.userHeightCm > 0f) trimNum(settings.userHeightCm) else "") }
    var weight by rememberSaveable(settings.userWeightKg) { mutableStateOf(if (settings.userWeightKg > 0f) trimNum(settings.userWeightKg) else "") }

    val dirty = sex != settings.userSex ||
        age.toIntOrNull() ?: 0 != settings.userAgeYears ||
        height.toFloatOrNull() ?: 0f != settings.userHeightCm ||
        weight.toFloatOrNull() ?: 0f != settings.userWeightKg

    SectionCard(
        title = "Personal stats",
        subtitle = "used for RDA adjustments (weight-based protein, sex-specific iron)"
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = sex == "male",
                onClick = { sex = if (sex == "male") "" else "male" },
                label = { Text("Male") }
            )
            FilterChip(
                selected = sex == "female",
                onClick = { sex = if (sex == "female") "" else "female" },
                label = { Text("Female") }
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = age,
                onValueChange = { v -> age = v.filter(Char::isDigit).take(3) },
                label = { Text("Age (years)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = height,
                onValueChange = { v -> height = decimalFilter(v) },
                label = { Text("Height (cm)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = weight,
                onValueChange = { v -> weight = decimalFilter(v) },
                label = { Text("Weight (kg)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                onSave(
                    weight.toFloatOrNull() ?: 0f,
                    height.toFloatOrNull() ?: 0f,
                    age.toIntOrNull() ?: 0,
                    sex
                )
            }) { Text("Save") }
            UnsavedBadge(dirty)
        }
    }
}

private fun decimalFilter(v: String): String {
    val t = v.filter { it.isDigit() || it == '.' }
    val dot = t.indexOf('.')
    return if (dot < 0) t else t.substring(0, dot + 1) + t.substring(dot + 1).filter { it != '.' }.take(1)
}

private fun trimNum(f: Float): String =
    if (f == f.toInt().toFloat()) f.toInt().toString() else "%.1f".format(f)
