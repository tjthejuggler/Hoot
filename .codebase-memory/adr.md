# ADR: Manual per-item nutrient overrides (2026-11-18)

## Context
User saw "3120 mcg iodine" on 2026-09-13 (~3× the 1100 mcg UL) with no way to see which food/supplement claimed it or to correct it. The intake ledger (nutrient_intake_log) already carries attribution (sourceMealId/sourceSupplementId) but no UI exposed it per item, and the ledger is wiped-and-rebuilt on every IntakeAggregator recompute — so in-place edits would not survive.

## Decision
1. New `nutrient_overrides` Room table (DB v10, additive migration): one row per (sourceType=meal|supplement, sourceId, nutrientId), amount in canonical unit, 0 = suppress. PK = "<type>:<id>:<nutrient>"; unique index on (sourceType, sourceId, nutrientId).
2. IntakeAggregator.applyOverrides(computed, overrides, day) — pure fn applied at the END of each day's recompute: drops computed rows of the overridden (item, nutrient) pair, emits one authoritative row (id "override:<overrideId>") when amount > 0. Guards: overrides only apply when the source still has rows on the day (no ghost credits from deleted meals); water rows never touched. Pinned by IntakeAggregatorOverrideTest.
3. UI: ItemNutrientSheet (ModalBottomSheet) opened by tapping a meal/supplement row in Home "Logged today" or in ConsumedDayDetailSheet. Lists ALL nutrient definitions (attributed ones show credited amount; others "not attributed"), each with an editable numeric field. Save → upsert overrides → re-run recomputeDays(day) → totals/score refresh reactively. ✍️ marker + per-row Reset (deletes override, re-aggregates).
4. Attribution read path: IntakeDao.mealNutrientTotals/supplementNutrientTotals (SUM per nutrient per source per day), surfaced via IntakeRepository and the deprecated NutrientRepository facade.

## Consequences
- Edits survive re-resolution, Tail re-sync, and full ledger recomputes (applied last, every time).
- Overriding macros on Tail-authored rows intentionally diverges from Tail's numbers — user assertion wins for that nutrient.
- Overrides for deleted sources persist as rows but are inert (skipped by the present-source guard); deleteForSource exists for future cleanup wiring.
- Existing HomeViewModel.openItemNutrients reloads from the fresh ledger after save, so the sheet always shows post-edit truth.