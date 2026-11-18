package com.example.hoot.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/**
 * Canonical nutrient definition, pre-seeded on first launch from
 * `docs/NUTRIENTS.md` via the database [androidx.room.RoomDatabase.Callback]
 * (see [com.example.hoot.data.local.HootDatabase]).
 */
@Entity(tableName = "nutrient_definitions")
data class NutrientDefinitionEntity(
    @PrimaryKey val id: String,          // "protein", "vitamin_d", "magnesium", …
    val name: String,                    // "Vitamin D"
    val group: String,                   // "macronutrient" | "vitamin" | "mineral" | "other"
    val unit: String,                    // canonical unit: "g" | "mg" | "mcg" | "kcal" | "L"
    val rdaValue: Double?,               // adult RDA/AI in canonical unit
    val ulValue: Double?,                // tolerable Upper Limit (null = none)
    val tier: Int,                       // 1 = critical, 2 = important, 3 = nice-to-have
    val deficiencySymptoms: String?,     // markdown
    val excessRisks: String?,
    val foodSources: String?             // markdown list of common sources
)

/**
 * Per-nutrient-per-day intake ledger. One row per (nutrient, day, contributing
 * source); daily totals are `SUM(amount)` grouped by (nutrient, day).
 */
@Entity(
    tableName = "nutrient_intake_log",
    indices = [Index("nutrientId"), Index("day"), Index(value = ["nutrientId", "day"])]
)
data class NutrientIntakeEntity(
    @PrimaryKey val id: String,
    val nutrientId: String,              // FK → nutrient_definitions
    val day: String,                     // "yyyy-MM-dd"
    val amount: Double,                  // canonical unit
    val sourceMealId: String?,           // FK → meals (null for supplement-only rows)
    val sourceSupplementId: String?
)

/**
 * MANUAL per-item nutrient override (feedback 2026-11-18): the user edited a
 * nutrient amount attributed to one meal/supplement on one day (e.g. a bogus
 * "3120 mcg iodine" estimate). One row REPLACES every computed ledger row for
 * that (source, nutrient) on every [com.example.hoot.domain.nutrition.IntakeAggregator]
 * recompute — the edit survives re-resolution and re-sync because it lives
 * outside the wiped-and-rebuilt ledger. `amount = 0` means "suppress this
 * nutrient for this item entirely" (no ledger row is emitted).
 */
@Entity(
    tableName = "nutrient_overrides",
    indices = [
        Index(value = ["sourceType", "sourceId", "nutrientId"], unique = true),
        Index("sourceId")
    ]
)
data class NutrientOverrideEntity(
    @PrimaryKey val id: String,          // "<sourceType>:<sourceId>:<nutrientId>"
    val sourceType: String,              // "meal" | "supplement"
    val sourceId: String,                // mealId / supplementId
    val nutrientId: String,              // FK → nutrient_definitions
    val day: String,                     // day the source belongs to (recompute scoping)
    val amount: Double,                  // canonical unit; 0 = suppress
    val updatedAt: Long                  // epoch millis of the edit
) {
    companion object {
        const val TYPE_MEAL = "meal"
        const val TYPE_SUPPLEMENT = "supplement"

        /** Deterministic primary key for one (source, nutrient) manual edit. */
        fun buildId(sourceType: String, sourceId: String, nutrientId: String): String =
            "$sourceType:$sourceId:$nutrientId"
    }
}

/**
 * One nutrient ↔ symptom association (deficiency system, 2026-11-16).
 * Rows come from the curated seed (NIH-derived, [Source.SEED]) or from the
 * per-nutrient research agent ([Source.RESEARCH]); `confidence` modulates
 * how strongly the nutrient's deficiency drives the symptom in the
 * likelihood blend.
 */
@Entity(
    tableName = "nutrient_symptom",
    indices = [Index("nutrientId"), Index(value = ["symptomKey", "kind"])]
)
data class NutrientSymptomEntity(
    @PrimaryKey val id: String,            // "$nutrientId:$kind:$symptomKey"
    val nutrientId: String,                // FK → nutrient_definitions
    val symptomKey: String,                // "fatigue", "muscle_cramps" (normalized)
    val displayName: String,               // "Fatigue", "Muscle cramps"
    val kind: String,                      // "DEFICIENCY" | "EXCESS"
    val confidence: Double,                // 0..1 strength of association
    val source: String,                    // "SEED" | "RESEARCH"
    val researchedAt: Long?                // null for seed rows
)

/**
 * Resolved nutrition panel for a food. `valuesJson` maps nutrient keys
 * ("protein", "iron", …) to amounts per [perAmount] [perUnit] in canonical units.
 */
@Entity(
    tableName = "food_nutrient_profile",
    indices = [Index(value = ["foodId"], unique = true)]
)
data class FoodNutrientProfileEntity(
    @PrimaryKey val id: String,
    val foodId: String,                  // FK → foods (unique per food)
    val perAmount: Double,               // e.g. 100
    val perUnit: String,                 // "g" — values are per 100 g unless noted
    val valuesJson: String,              // {"protein": 12.3, "iron": 2.1, …} canonical units
    val confidence: Double,              // 0-1 from LLM/self-report
    val resolutionMethod: String         // "cache" | "llm" | "web_usda" | "manual"
)

/**
 * Cache row for a normalized food-name query — first stop of the resolution
 * pipeline (cache → LLM → web). `fetchedAt` drives TTL; `hitCount` is
 * popularity telemetry.
 */
@Entity(tableName = "lookup_cache")
data class LookupCacheEntity(
    @PrimaryKey val normalizedKey: String,   // normalized food-name query
    val resolvedFoodId: String?,             // FK → foods
    val profileId: String?,                  // FK → food_nutrient_profile
    val sourceUrlsJson: String,              // ["https://…usda…", …]
    val fetchedAt: Long,                     // epoch millis — TTL decisions
    val hitCount: Int                        // popularity telemetry
)

/** Citation record for a URL that backed a resolved profile. */
@Entity(
    tableName = "sources",
    indices = [Index("lookupKey")]
)
data class SourceEntity(
    @PrimaryKey val id: String,
    val lookupKey: String,               // FK → lookup_cache
    val url: String,
    val title: String?,                  // page/document title
    val publisher: String?,              // "USDA FDC", "NIH ODS", …
    val fetchedAt: Long,
    val toolName: String?                // MCP tool that produced it: "web-search-prime"
)
