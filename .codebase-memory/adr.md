# ADR: Supplement peer repair + reactive Home window (2026-11-16, round 2)

## Context
Follow-ups: (1) Home Focus Now showed a stale pre-repair "2%" while the
nutrient page showed 167% — the trailing-7d window was read with one-shot
`.first()` inside a chain that only re-ran on the selected day's totals.
(2) `Omega-3 Sunday Natural` (brand label, no dose) rows since 11-10 were
attempt-capped despite an identical resolved row from 11-09 carrying
`omega3_epa_dha: 600`.

## Decision
1. HomeViewModel now `combine`s `observeDailyTotals(day)` with a LIVE
   `observeDailyTotalsRange(recentFrom, day)` — historical backfills
   re-emit and Focus Now recomputes immediately.
2. `NutritionProcessor.repairSupplementsFromPeers()`: unresolved/capped rows
   whose EXACT label (or parsed name+dose) matches a resolved row clone its
   `nutrientContributions` + `resolvedFoodId` — zero LLM. Runs at startup
   (then ledger + snapshot recompute) and after every Tail sync before kick.

## Consequences
- On-device: 328 rows repaired across all brand supplements; Omega-3 ledger
  now 1200–1800 mg/day; vitamin D 7-day avg consistent across screens.
- New daily pills of an already-known supplement resolve the moment Tail
  syncs them, never depending on an LLM batch completing.
