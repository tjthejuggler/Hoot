# ADR addendum: bulk symptoms backfill (2026-11-17, feedback round 3)

**Status:** accepted

## Context
The per-nutrient Research button (NutrientResearcher, Scope.SYMPTOMS) had to be tapped manually on every nutrient page to extend symptom coverage. The user wanted a one-tap bulk pass over every nutrient that has never been researched / has no symptom associations yet.

## Decision
1. Target criterion: nutrients with ZERO rows in `nutrient_symptom` (new DAO query `nutrientIdsWithSymptoms`, exposed via `NutrientSymptomRepository`). Nutrients that already have seed or research associations are skipped — they already have symptoms listed.
2. `NutrientResearcher.researchAllMissingSymptoms()`: sequential per-nutrient `research(id, Scope.SYMPTOMS)` passes (SYMPTOMS scope = the LLM prompt asks only for deficiency/excess symptom associations, no foods). One LLM call per nutrient, rate-limit friendly; per-nutrient failures never stop the run; aborts early if the LLM is unconfigured. Progress surfaced via callback (`BulkProgress(done, total, added, current)`).
3. Driver in HomeViewModel: `symptomBackfill` (StateFlow<BulkProgress?>, final result retained for the outcome message) + `symptomBackfillRunning` flag; completion bumps `_researchTick` so the Likely-symptoms ranking re-computes from fresh associations.
4. UI: "Research symptoms for all nutrients" TextButton on the LikelySymptomsCard header (card is now always rendered, not gated on symptoms being non-empty, since with no associations is exactly when the button is most needed). During the run it shows live "done/total — current nutrient name" progress; after, either "N associations added" or "nothing new found".

## Consequences
- Re-running the backfill after a completed pass is a cheap no-op unless new nutrients appeared (all existing ones now have rows).
- Canonicalization still applies per pass (`canonicalizeKeys` runs inside each `research` call), keeping symptom keys grouped.
- Full unit suite green (44 classes, 0 failures); deployed to SM-S918U1.
