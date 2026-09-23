# Hoot — Refactoring & Compartmentalization Plan

> Goal: make the codebase easier for both humans and LLM assistants to edit by
> shrinking edit surfaces, removing duplication, and giving every concern one
> obvious home. Written 2026-09-22 after a full size/dependency survey.
>
> **STATUS: EXECUTED 2026-09-22.** All phases (P1–P6) landed; 284 unit tests
> green; installed on device. Largest main-source file is now 481 lines —
> zero files over 500. New files: `TailSyncState.kt`, `TailEntityMapper.kt`,
> `NutrientDefinitionRepository.kt`, `IntakeRepository.kt`, `FoodRepository.kt`,
> `LookupCacheRepository.kt`, `SupplementRepository.kt`,
> `RecommendationRepository.kt`, `ScoreSnapshotRepository.kt`,
> `JsonList.kt`, `NutritionPipeline.kt`, `HomeCards.kt`, `HomeRows.kt`,
> `IntakeRows.kt`, `InsightsCards.kt`, `TailSetupSections.kt`,
> `ResolutionStore.kt`, `SupplementResolver.kt`, `WebResolution.kt`.
> Deleted: `ui/today/` (TodayScreen + TodayViewModel).
> Facades kept during transition: `NutrientRepository`, `NutritionResolver`
> (public APIs unchanged; migrate call sites, then delete).

---

## 0. Why edits have been slow (evidence)

A healthy file for LLM-assisted editing is **< ~300 lines** with a single
responsibility: the assistant can read it in one shot, hold it in context, and
write a surgical diff. Survey findings (23,801 Kotlin lines total):

| Problem | Evidence | Cost per edit |
|---|---|---|
| **Dashboard implemented twice** | `ScoreCard`, `CaloriesCard`, `InsightRow`, `NutritionProcessingChip` exist in both `ui/home/HomeScreen.kt` (803 L) and `ui/today/TodayScreen.kt` (450 L); `HomeViewModel` (359 L) and `TodayViewModel` (231 L) share 14 imports. `Today` is only reachable via the `ROUTE_TODAY_ALIAS` route — it is not a tab. | Every dashboard change costs 2× and risks drift. |
| **God repository** | `data/repository/NutrientRepository.kt`: 24 outgoing imports, ~50 public methods spanning 8 concerns (definitions, goals, intake, foods/profiles, lookup cache, sources, supplements, recommendations, snapshots). Imported by 6+ call sites. | Every feature touches it; large reads for trivial changes. |
| **Oversized orchestrators** | `domain/nutrition/NutritionResolver.kt` (862 L) mixes food + supplement + web-fallback + persistence + token budgeting. `data/tail/TailSyncManager.kt` (549 L) mixes sync orchestration with 10 pure entity-mapping functions. | Reading one flow requires parsing an unrelated half of the file. |
| **Monolithic composables** | `HomeScreen()` body ≈ 400 lines (lines 86–484); `IntakeScreen()` ≈ 308 lines; `HabitMappingSection` ≈ 180 lines. | Diffs have huge ambiguous contexts; writes get truncated or misplaced. |
| **DI graph with behavior** | `di/AppGraph.kt` (301 L) contains a sync→ingest collector with inline JSON parsing (lines ~210–285), not just wiring. | Hard to reason about ownership/lifecycle. |

**Strengths to preserve** (these are why the rest of the app edits fine):
`domain/` is pure-JVM, small, and well-tested (`DayKeys`, `Units`, `IngredientParser`,
`SupplementListSplitter`, … all < 400 L with sibling tests). `ui/settings/` is
already split by section. Keep following that pattern.

---

## 1. Prioritized refactors

### P1 — Collapse the Home/Today twins *(highest value, do first)*
Two mutually-exclusive options; **A is recommended**.

* **Option A — delete `ui/today/`.** Point `ROUTE_TODAY_ALIAS` in
  `ui/HootNavHost.kt` at `HomeScreen` (keep the constant so old back-stack
  entries/deep links still resolve). Deletes ~681 L (`TodayScreen.kt` +
  `TodayViewModel.kt`) and makes every dashboard edit single-surface.
* **Option B — keep both, dedupe.** Extract the 4 shared cards into
  `ui/common/DashboardCards.kt`, parameterized on a small shared interface
  (score/snapshot/intake/processing fields), and unify the overlapping parts of
  the two UiStates.

Risk: low (A) / low (B). Tests: none reference either screen directly.

### P2 — Split `NutrientRepository` into concern-scoped repositories
One repository per DAO cluster, in `data/repository/`:

| New file | Absorbs methods |
|---|---|
| `NutrientDefinitionRepository` | definitions + goals (incl. `upsertGoals`) |
| `IntakeRepository` | intake logging/clearing + `dailyTotals*` + `observeDailyTotals*` |
| `FoodRepository` | foods + profiles |
| `LookupCacheRepository` | lookup cache + sources |
| `SupplementRepository` | supplement lookup/upsert |
| `RecommendationRepository` | recommendations incl. `purgeDietViolatingRecommendations` |
| `ScoreSnapshotRepository` | snapshot read/upsert + history |

Transition trick: keep `NutrientRepository` as a thin facade delegating to the
seven new repos, mark it `@Deprecated`, migrate call sites (`AppGraph`,
ViewModels, engines) one per commit, then delete the facade. Risk: low — it is
pass-through delegation today.

### P3 — Extract the pure mapping layer from `TailSyncManager`
Move the 10 pure functions (`mealEntities`, `textMealEntities`,
`buildMealRawText`, `supplementEntities`, `waterEntities`, `miscEntities`,
`ingredientEntitiesFor`, `ingredientRowKey`, `mealIngredientEntities`,
`textMealIngredientEntities`, `entryKey`) into `data/tail/TailEntityMapper.kt`.
`TailSyncManager` keeps only state + orchestration (~250 L target).
`TailSyncState`/`TailSyncTrigger` move to `data/tail/TailSyncState.kt`.
The three mapping tests (`TailMealIngredientMappingTest`,
`TailPillsMappingTest`, `TailWaterMiscMappingTest`) already cover these
functions — move them unchanged. Risk: minimal; functions are `internal` and pure.

### P4 — Split `NutritionResolver` (862 L) by resolution path
| New file | Contents |
|---|---|
| `Resolution.kt` | `ResolveOutcome` sealed interface + shared token math (`batchMaxTokens`) |
| `FoodResolver.kt` | `resolveIngredient`, `cachedProfiles`, `applyProfileToGroup`, `resolveFoodsBatch`, `seedPanelsFor`, `persistFoodPanel`, `resolveSingleFood`, `markFoodGroupFailed` |
| `SupplementResolver.kt` | `resolveSupplement`, `resolveSupplementGroupsBatch`, `applySupplementPanel`, `resolveSupplementGroupSingle`, `markSupplementGroupFailed` |
| `WebResolution.kt` | `resolveViaWeb`, `getWebTools`, nutrient-ref/unit-map helpers |
| `NutritionResolver.kt` | thin facade keeping today's public API so `NutritionProcessor`, `AppGraph`, and tests stay untouched |

Risk: medium (largest class; do after P1–P3 build confidence). Public API can
remain byte-identical via facade delegation.

### P5 — Decompose the remaining monolithic screens
Same-package extractions, no logic changes:
* `HomeScreen.kt` → `HomeCards.kt` (`ScoreCard`, `CaloriesCard`,
  `ConsumedSummaryCard`) + `HomeRows.kt` (`FocusNowRow`, `InsightRow`,
  `NutritionProcessingChip`); `HomeScreen.kt` drops to ~350 L (or ~350→
  smaller after P1).
* `IntakeScreen.kt` → `IntakeRows.kt` (`IntakeRowCard`, `MealDetailDialog`) +
  move `copyUriToFile` to `ui/intake/IntakeFileUtils.kt`; main file ~250 L.
* `TailSetupScreen.kt` → `HabitMappingSection` (~180 L) into
  `TailHabitMapping.kt`; `AppPickerSection`/`ConnectedSection` likewise.
* `InsightsScreen.kt` → card composables into `InsightsCards.kt`.

### P6 — Make `AppGraph` wiring-only
Extract the sync→ingest collector + JSON parsing into
`data/tail/TailIngestCoordinator.kt` — a small class exposing
`start(appScope)` — and the nutrition pipeline block
into `di/NutritionGraph.kt` returning the wired trio (resolver/processor/
snapshotter). `AppGraph` reads as a pure object table again. Risk: low;
behavior preserved verbatim.

### P7 — Housekeeping (batch with any of the above)
* `ui/common/Components.kt` is the right home for shared UI — add new shared
  pieces there or sibling files, never into screens.
* Convention worth adopting repo-wide: **one top-level composable/class per
  file when a file passes ~300 lines**; extraction is mechanical afterward.
* After each refactor: `./gradlew :app:testDebugUnitTest` (55+ JVM tests must
  stay green), then a fresh index pass so graph-aware tools see the new files.

---

## 2. Suggested execution order

```
P1 (dedupe dashboard)  →  P3 (tail mapper)  →  P2 (repo split)
→  P5 (screen splits)  →  P6 (AppGraph)  →  P4 (resolver split)
```

Rationale: P1 and P3 are pure wins with test coverage already in place; P2 is
mechanical delegation; P4 is the only refactor with real semantic surface, so
it lands last. Expected result: **no main-source file over ~450 lines, most
under 300**, and every future edit targets a file that an assistant can read
in a single pass.
