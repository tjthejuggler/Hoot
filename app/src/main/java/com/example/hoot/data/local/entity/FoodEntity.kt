package com.example.hoot.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * A known food (or supplement-as-food). `normalizedName` is the lowercased,
 * singularized, trimmed join key used by [LookupCacheEntity]; `displayName`
 * preserves the user-facing spelling.
 */
@Entity(
    tableName = "foods",
    indices = [Index(value = ["normalizedName"], unique = true)]
)
data class FoodEntity(
    @PrimaryKey val id: String,          // UUID
    val normalizedName: String,          // lowercased, singularized, trimmed — join key
    val displayName: String,
    val category: String?,               // "vegetable", "supplement", …
    val isSupplement: Boolean = false,
    val createdAt: Long,
    // v3 (phase 3): serving hint for count-unit gram estimates + image lookup.
    val typicalServingGrams: Double? = null,
    val imageSearchTerm: String? = null
)

/**
 * One ingredient row per ingredient per meal — Hoot's "MealItem", realized as
 * a child of [com.example.hoot.data.local.entity.MealEntity].
 */
@Entity(
    tableName = "ingredients",
    indices = [Index("mealId"), Index("foodId")],
    foreignKeys = [
        ForeignKey(
            entity = FoodEntity::class,
            parentColumns = ["id"],
            childColumns = ["foodId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class IngredientEntity(
    @PrimaryKey val id: String,
    val mealId: String,                  // FK → meals
    val rawText: String,                 // exactly as written by user/LLM
    val foodId: String?,                 // FK → foods (null until resolved)
    val amount: Double?,                 // numeric quantity
    val unit: String?,                   // "g", "ml", "cup", "tbsp", "piece", …
    val gramsEstimate: Double?,          // normalized weight for aggregation
    // v4: failed resolution attempts (pending queue skips rows at the cap so
    // restarts never re-analyze permanently-failed items).
    val resolveAttempts: Int = 0
)

/**
 * A meal — either synced from Tail (dedup key = Tail entry id) or logged in
 * Hoot manually. `day` uses the local `yyyy-MM-dd` key convention.
 *
 * v6 adds the Tail-compatible capture structure (same fields as Tail's
 * `MealLog`, docs/TAIL_REQUEST.md §R2): meals captured in-app via the Intake
 * tab store the LLM's structured result directly (macros, kcal, summary,
 * vegan flag, health notes) plus capture provenance (voice transcript, photo
 * path). Tail-synced rows keep the defaults — their structured data lives in
 * rawText + ingredient rows.
 */
@Entity(
    tableName = "meals",
    indices = [Index("day"), Index("timestamp")]
)
data class MealEntity(
    @PrimaryKey val id: String,          // Tail entry dedup key (or manual UUID)
    val tailHabitName: String,           // e.g. "Food"
    val timestamp: Long,                 // epoch millis
    val day: String,                     // "yyyy-MM-dd" local
    val title: String?,
    val rawText: String,                 // full meal description from Tail
    val source: String,                  // "tail" | "manual" | "hoot"
    // v6 — Tail-compatible capture structure (additive migration):
    val summary: String? = null,         // 1-2 sentence LLM/user summary
    val calories: Int = 0,               // estimated kcal
    val proteinGrams: Double = 0.0,      // macros (0 = unknown)
    val carbsGrams: Double = 0.0,
    val fatGrams: Double = 0.0,
    val isVegan: Boolean = false,        // LLM-verified per dietary rules
    val healthNotes: String? = null,
    val transcript: String? = null,      // spoken description (voice capture)
    val photoPath: String? = null        // app-private captured photo
)

/**
 * One supplement-TAKING event (a Tail "Took Pills" entry) — phase 2 sync
 * preserves the raw text verbatim; structured dose parsing happens in phase 3.
 * Contributions to tracked nutrients live in `nutrientContributions` JSON:
 * [{"nutrientId","amount","unit"}].
 */
@Entity(tableName = "supplements")
data class SupplementEntity(
    @PrimaryKey val id: String,          // "tail:<entryId>" or "tail:pills:<habit>:<tsKey>"
    val label: String,                   // exact Tail "Took Pills" option label, e.g. "Magnesium"
    val description: String?,            // Tail option description (dose/notes) when exposed
    val resolvedFoodId: String?,         // FK → foods
    val doseAmount: Double?,             // parsed from label/description
    val doseUnit: String?,               // "mg", "mcg", "IU", "g"
    val nutrientContributions: String,   // JSON: [{nutrientId, amount, unit}]
    // Per-entry provenance (phase 2 Tail sync):
    val tailHabitName: String = "",      // e.g. "Took Pills"
    val timestamp: Long = 0L,            // epoch millis of the log entry
    val day: String = "",                // "yyyy-MM-dd" local
    val rawText: String = "",            // full, untruncated entry text from Tail
    val source: String = "tail",         // "tail" | "manual"
    // v4: failed resolution attempts (pending queue skips rows at the cap so
    // restarts never re-analyze permanently-failed items).
    val resolveAttempts: Int = 0
)
