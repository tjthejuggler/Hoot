package com.example.hoot.ui.home

import com.example.hoot.data.local.entity.ScoreSnapshotEntity
import com.example.hoot.domain.insights.FocusNowItem
import com.example.hoot.domain.insights.Insight
import com.example.hoot.domain.insights.InsightKind
import com.example.hoot.domain.insights.InsightSeverity
import com.example.hoot.domain.insights.SmartFoodPick
import com.example.hoot.domain.insights.SmartNutrientHit
import com.example.hoot.domain.insights.SymptomMatcher
import com.example.hoot.domain.score.ScoreComponent
import com.example.hoot.domain.score.ScoreStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for the last-session Home snapshot codec (startup UX
 * fix, 2026-11-16): whatever the dashboard computed last launch must come
 * back EXACTLY after serialize → parse, so the first frame can render it.
 */
class HomeStateCacheTest {

    private fun fullState(): HomeUiState = HomeUiState(
        day = "2026-11-16",
        loading = false,
        score = ScoreSnapshotEntity(
            day = "2026-11-16", score = 82.5, completeness = 74.0,
            deficiencyPenalty = 0.12, adherence = 0.9, nutrientsMet = 21, nutrientsTracked = 30
        ),
        focusNow = listOf(
            FocusNowItem(
                nutrientId = "magnesium", name = "Magnesium", tier = 1, unit = "mg",
                target = 400.0, recentAvgIntake = 210.0, todayIntake = 80.0,
                recentCoverage = 0.525, todayCoverage = 0.2
            )
        ),
        tier1 = listOf(
            ScoreComponent(
                nutrientId = "vitamin_d", name = "Vitamin D", tier = 1, unit = "µg",
                intake = 5.0, target = 15.0, coverage = 0.33, isLimitTracker = false,
                ulExceeded = false, status = ScoreStatus.LOW
            )
        ),
        otherTiers = listOf(
            ScoreComponent(
                nutrientId = "sodium", name = "Sodium", tier = 2, unit = "mg",
                intake = 3500.0, target = 2300.0, coverage = 0.0, isLimitTracker = true,
                ulExceeded = true, status = ScoreStatus.EXCESS
            )
        ),
        calories = 1850.0 to "kcal",
        macros = listOf(
            ScoreComponent(
                nutrientId = "protein", name = "Protein", tier = 1, unit = "g",
                intake = 90.0, target = 120.0, coverage = 0.75, isLimitTracker = false,
                ulExceeded = false, status = ScoreStatus.CLOSE
            )
        ),
        insights = listOf(
            Insight(
                kind = InsightKind.DEFICIENCY, severity = InsightSeverity.WARNING,
                title = "Vitamin D running low",
                message = "You've covered 33% for 5 straight days.",
                nutrientId = "vitamin_d", effects = "Tiredness, weaker immunity"
            )
        ),
        sparkline = listOf(70.0, 72.5, 68.0, 75.0, 82.5),
        unresolvedCount = 2,
        smartPicks = listOf(
            SmartFoodPick(
                foodId = "seed:almonds", displayName = "Almonds", category = "nuts",
                servingGrams = 28.0,
                hits = listOf(
                    SmartNutrientHit(
                        nutrientId = "magnesium", name = "Magnesium", unit = "mg",
                        deficitCovered = 0.19, servingAmount = 76.0
                    )
                ),
                score = 0.61, cautions = listOf("watch calories"), source = "cache",
                why = "Covers magnesium + vitamin E", emojiHint = "🥜"
            )
        ),
        likelySymptoms = listOf(
            SymptomMatcher.RankedSymptom(
                symptomKey = "muscle_cramps", displayName = "Muscle cramps",
                likelihood = 0.44,
                contributors = listOf(
                    SymptomMatcher.Contributor(
                        nutrientId = "magnesium", nutrientName = "Magnesium",
                        deficiencyLikelihood = 0.7, associationConfidence = 0.8,
                        unit = "mg", target = 400.0
                    )
                )
            )
        ),
        smartPicksCacheCold = false,
        knowledgeCount = 42
    )

    @Test
    fun `full state survives a serialize-parse round trip`() {
        val original = fullState()
        val parsed = HomeStateCache.fromJson(HomeStateCache.toJson(original))

        assertTrue(parsed != null)
        assertEquals(original.day, parsed!!.day)
        assertFalse(parsed.loading)
        assertEquals(original.score, parsed.score)
        assertEquals(original.focusNow, parsed.focusNow)
        assertEquals(original.tier1, parsed.tier1)
        assertEquals(original.otherTiers, parsed.otherTiers)
        assertEquals(original.calories, parsed.calories)
        assertEquals(original.macros, parsed.macros)
        assertEquals(original.insights, parsed.insights)
        assertEquals(original.sparkline, parsed.sparkline)
        assertEquals(original.unresolvedCount, parsed.unresolvedCount)
        assertEquals(original.smartPicks, parsed.smartPicks)
        assertEquals(original.allSmartPicks, parsed.allSmartPicks)
        assertEquals(original.deepSmartPicks, parsed.deepSmartPicks)
        assertEquals(original.likelySymptoms, parsed.likelySymptoms)
        assertEquals(original.smartPicksCacheCold, parsed.smartPicksCacheCold)
        assertEquals(original.knowledgeCount, parsed.knowledgeCount)
    }

    @Test
    fun `empty-list and null-heavy state round trips`() {
        val minimal = HomeUiState(day = "2026-11-16", loading = false)
        val parsed = HomeStateCache.fromJson(HomeStateCache.toJson(minimal))

        assertEquals("2026-11-16", parsed!!.day)
        assertNull(parsed.score)
        assertTrue(parsed.focusNow.isEmpty())
        assertTrue(parsed.tier1.isEmpty())
        assertNull(parsed.calories)
        assertTrue(parsed.sparkline.isEmpty())
        assertTrue(parsed.smartPicks.isEmpty())
        assertTrue(parsed.likelySymptoms.isEmpty())
    }

    @Test
    fun `stored JSON string parses back`() {
        val json = HomeStateCache.toJson(fullState()).toString()
        val parsed = HomeStateCache.fromJson(JSONObject(json))

        assertEquals(fullState(), parsed!!.copy(fromCache = false))
    }

    @Test
    fun `corrupt or empty payload yields null instead of crashing`() {
        // No day key at all → blank → rejected.
        assertNull(HomeStateCache.fromJson(JSONObject("{}")))
        // Junk beyond the guard still can't crash the codec.
        HomeStateCache.fromJson(JSONObject("{\"day\": \"2026-11-16\", \"tier1\": 5}"))
    }
}
