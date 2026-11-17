# ADR: Tail-is-fact macro precedence in the intake ledger

**Date:** 2026-11-17 · **Status:** accepted · **Supersedes:** 2026-09-21 fill-missing macro semantics

## Context
User feedback: Hoot's daily calories and protein/carbs/fat disagree with Tail for the same day. Both apps read the same meal rows, but Hoot's `IntakeAggregator` credited Tail's structured meal macros only when the meal's resolved ingredients produced nothing for that nutrient — ingredient estimates (gram estimation × per-100 g profiles) silently overrode Tail's numbers whenever both existed and differed.

## Decision
Structured macros on the meal row (`calories`, `proteinGrams`, `carbsGrams`, `fatGrams` > 0) are FACT — the exact numbers Tail displays. `IntakeAggregator.recomputeDays` now:
1. Credits structured macros unconditionally (`structuredMacroRows`, deterministic `macro:<mealId>:<nutrient>` ids).
2. Computes `structuredMacroNutrientIds(meal)` and the ingredient-enrichment loop SKIPS exactly those nutrient ids — no override, no double-count. Ingredients enrich only what Tail doesn't assert (fiber, micronutrients, …).
3. Meals without structured macros (v1 text fallback, manual) keep ingredient-driven aggregation; partial rows (kcal-only) stay ingredient-driven for missing macros.
4. Supplements still never credit energy macros (`SUPPLEMENT_MACRO_SKIP`).

Home/History/Insights/Score all read the ledger (`dailyTotals`), so one writer + per-launch full ledger self-heal (NutritionPipeline) makes history converge to Tail's numbers without user action.

Pinned by `IntakeAggregatorStructuredMacroTest` (TAIL-IS-FACT precedence, partial-macro enrichment).