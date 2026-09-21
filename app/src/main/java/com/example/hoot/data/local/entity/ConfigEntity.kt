package com.example.hoot.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/** Singleton-row config for the Tail integration (sync arrives in phase 2). */
@Entity(tableName = "tail_app_config")
data class TailAppConfigEntity(
    @PrimaryKey val id: Int = 1,         // singleton row
    val integrationEnabled: Boolean = false,
    val mealHabitName: String? = null,   // e.g. "Food"  (meal-type habit)
    val pillsHabitName: String? = null,  // e.g. "Took Pills" (text-entry habit)
    val lastMealSyncAt: Long? = null,    // incremental-sync cursor (millis)
    val lastPillsSyncAt: Long? = null,
    val knownEntryIdsJson: String = "[]", // JSON set for fast dedup (compact window)
    // v5: water + miscellaneous habit mappings (additive migration).
    val waterHabitName: String? = null,      // text/counter habit id holding water logs
    val miscHabitNamesJson: String = "[]",   // JSON array of N misc habit ids (e.g. electrolytes)
    val lastWaterSyncAt: Long? = null,       // incremental-sync cursor (millis)
    val lastMiscSyncAt: Long? = null         // shared cursor across all misc habits
) {
    /** Decoded misc habit ids; tolerant of legacy/blank/invalid JSON. */
    val miscHabitNames: List<String>
        get() = runCatching {
            val arr = org.json.JSONArray(miscHabitNamesJson)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.getOrDefault(emptyList())
}

/** Singleton-row dietary profile used by the recommender to filter foods. */
@Entity(tableName = "dietary_profile")
data class DietaryProfileEntity(
    @PrimaryKey val id: Int = 1,
    val dietStyle: String = "omnivore",  // "omnivore" | "vegan" | "vegetarian" | "pescatarian" | "keto" | …
    val allergiesJson: String = "[]",    // ["peanuts", "shellfish"]
    val dislikesJson: String = "[]",     // foods to never recommend
    val excludeFromScoring: Boolean = false
)

/**
 * Per-nutrient goal. `isCustom = false` rows mirror the seeded RDA default;
 * user overrides set `isCustom = true` (and may override `priority`).
 */
@Entity(
    tableName = "nutrient_goals",
    indices = [Index(value = ["nutrientId"], unique = true)]
)
data class NutrientGoalEntity(
    @PrimaryKey val id: String,
    val nutrientId: String,
    val targetValue: Double,             // canonical unit
    val isCustom: Boolean,               // false = RDA default, true = user override
    val priority: Int                    // 1-3; overrides definition tier when set
)

/** A food recommendation issued by the (phase 3+) recommender. */
@Entity(
    tableName = "recommendation_log",
    indices = [Index("day"), Index("nutrientId")]
)
data class RecommendationEntity(
    @PrimaryKey val id: String,
    val day: String,                     // issued on
    val nutrientId: String,              // deficiency targeted
    val foodName: String,                // "lentils"
    val reasonText: String,              // shown in carousel
    val imageUrl: String?,               // Coil-loaded
    val sourceIdsJson: String,           // citations backing the claim
    val accepted: Boolean?               // user tapped "ate it" / dismissed (null = open)
)

/** One scored day — the time series behind the dashboard ring and stats charts. */
@Entity(tableName = "score_snapshot")
data class ScoreSnapshotEntity(
    @PrimaryKey val day: String,         // "yyyy-MM-dd"
    val score: Double,                   // 0-100
    val completeness: Double,            // micronutrient-coverage component
    val deficiencyPenalty: Double,
    val adherence: Double,               // recommendation-following component
    val nutrientsMet: Int,
    val nutrientsTracked: Int
)
