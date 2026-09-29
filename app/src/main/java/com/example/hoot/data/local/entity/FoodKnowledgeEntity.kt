package com.example.hoot.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Hoot's long-term FOOD KNOWLEDGE BASE (feedback 2026-09-27): every food the
 * resolution pipeline ever researches — via LLM panel, MCP web lookup or the
 * bundled seed LUT — is remembered here FOREVER, independently of the
 * TTL-bound lookup cache. As months pass this table becomes the user's
 * private "what is high in what" database:
 *
 *  - `valuesJson` keeps the full per-100 g panel (canonical units), so the
 *    recommendation pool can be re-built even after a cache/profile clear.
 *  - `highInJson` lists the nutrient ids this food is notably RICH in
 *    (≥ [com.example.hoot.domain.nutrition.FoodKnowledgeIndex.HIGH_RDA_SHARE_PER_100G]
 *    of the RDA per 100 g) — the queryable "what they are high in" summary.
 *  - `researchedForJson` records the gap nutrients that motivated targeted
 *    research, so foods found specifically to fill a user gap stay linked
 *    to that gap.
 *
 * Deliberately NOT cleared by "Clear cache" (ARCHITECTURE.md §8.6): only
 * explicit deletion of a food removes knowledge.
 */
@Entity(
    tableName = "food_knowledge",
    indices = [Index(value = ["normalizedName"], unique = true)]
)
data class FoodKnowledgeEntity(
    @PrimaryKey val id: String,             // UUID
    val normalizedName: String,             // FoodNormalizer join key (unique)
    val displayName: String,                // user-facing spelling
    val valuesJson: String,                 // per-100 g panel, canonical units
    val highInJson: String,                 // ["iron","vitamin_b12", …] rich-in ids
    val confidence: Double,                 // of the panel that produced the row
    val resolutionMethod: String,           // "llm" | "web_usda" | "seed" | "manual"
    val sourceUrlsJson: String,             // provenance URLs (merged, capped)
    val researchedForJson: String,          // gap nutrient ids that triggered research
    val firstResearchedAt: Long,            // epoch millis of first knowledge
    val lastResearchedAt: Long,             // epoch millis of latest refresh
    val researchCount: Int                  // how many times re-encountered
)
