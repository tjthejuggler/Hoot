# Hoot

Nutrition-recommendation companion for the Tail habit tracker
(`com.example.hoot`, Kotlin + Compose M3, manual DI, Room + DataStore).

Authoritative design doc: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
Tail provider feature request: [`docs/TAIL_REQUEST.md`](docs/TAIL_REQUEST.md).

---

## Overview

Hoot turns raw meal/pill logs from [Tail](docs/TAIL_REQUEST.md) (or manual
entry) into a daily 0–100 nutrition score, trend insights, and concrete
food recommendations — entirely on-device, no accounts, no backend.

### Features

- **Today dashboard** — animated score ring, Tier-1 nutrient bars vs target,
  calories + macros, logged items with an unresolved-analysis retry chip,
  Tail-sync status banner, insight summary, 7-day score sparkline.
- **History** — nutrient-centric: searchable tier-badged picker, 7d/30d/90d/1y/all
  windows, intake-vs-target chart, stats header (avg / days-met / trend),
  top food contributors, per-day tab.
- **Insights** — score trend (weekly bars + daily line), Tier-1 coverage
  radar, deficiency/excess cards, food-recommendation carousel with
  accept/dismiss (feeds adherence), adherence %, LLM coach note
  (template fallback).
- **Settings** — LLM + MCP configuration with reachability tests, Tail
  integration setup + sync controls, dietary profile, personal stats,
  per-nutrient custom goals (RDA defaults), cache management, JSON export.
- **Input** — quick-add meal (free-text ingredients, live parse preview) and
  supplement entry via the Today "+ Add" FAB.

### How Tail integration works

Tail is a habit tracker exposing a read-only `ContentProvider`
(`content://com.example.tail.provider`) guarded by a signature permission
(`com.example.tail.permission.TAIL_INTEGRATION`) — only same-keystore apps
can read. Hoot's `data/tail/` layer:

1. **Setup wizard** (`TailSetupScreen`) maps the user's meal habit and
   "Took Pills" text habit into the singleton `tail_app_config` row.
2. **`TailClient`** probes `/v2/habits`, `/v2/habits/{id}/entries`,
   `/v2/capabilities` and falls back to the v1 surface (`/habits`,
   `/text_habits/recent`) when absent. Meal logs are currently not exposed
   by Tail, so the meal habit's *text entries* are ingested as raw-text
   meals and the UI flags `mealLogsUnavailable` (see the
   [Tail request](docs/TAIL_REQUEST.md)).
3. **`TailSyncManager`** does a full backlog on first run, then `?after=`
   incremental pulls using persisted cursors; dedup is by stable entry id
   (`tail:<entryId>`, with a habit+timestamp fallback). A coroutine periodic
   loop honors the sync-interval setting; a permission-guarded
   `TailSyncReceiver` is the future push hook (protocol v5 pattern, R4).
4. Every sync that ingests rows kicks the nutrition processor.

**v2 readiness:** Hoot already speaks the proposed v2 contract (R1/R2/R3/R6)
and degrades gracefully per feature flag; when Tail ships meal-log rows
(R2-for-meal) and stable ids (R5), richer ingestion is a zero-migration
upgrade path already implemented behind `MealLogsResult`.

### How the nutrition engine works

`domain/nutrition/NutritionResolver` resolves each ingredient/supplement
through a **cache → LLM → MCP web → sources** pipeline:

1. **Cache** — `lookup_cache` hit within TTL (default 90 d) → reuse the
   stored food profile (popularity telemetry on hit).
2. **LLM** — one strict-JSON chat call requesting a per-100 g nutrient
   panel (46 seeded nutrients), confidence ≥ 0.75 required.
3. **MCP web** — otherwise `McpWebTools.searchWeb("… per 100g USDA")` +
   `readUrl` (tool budget capped), then an LLM extraction pass over the page.
4. **Sources** — every success persists `Food` + `FoodNutrientProfile`
   (canonical units) + `LookupCache` + `Source` citation rows (model and
   URLs recorded). Total failure leaves the item unresolved — visible in the
   UI and retryable.

`IntakeAggregator` then recomputes the per-nutrient-per-day ledger
(idempotent per day): Σ ingredient grams ÷ perAmount × profile values +
supplement contributions (canonicalized by `Units.kt`).

### Scoring, in short

`domain/score/ScoreEngine.kt` (pure JVM):
`score = 0.45·completeness + 0.35·(1 − deficiencyPenalty) + 0.20·adherence`.

- **Completeness** — tier-weighted micronutrient coverage (T1 ×3, T2 ×2,
  T3 ×1), capped at 100 %; limit-trackers (sodium, added sugar, sat/trans
  fat) score against their cap instead.
- **Deficiency penalty** — Tier-1 nutrients below 50 % of target, amplified
  up to 2× by consecutive low-day streaks; UL exceedances add penalties
  (supplemental-only ULs like magnesium never penalize food intake).
- **Adherence** — share of trailing-7-day food recommendations actually
  accepted; dismissals cost −0.25 each.
- A "well-rounded" bonus floors the score at 85 when all Tier-1 ≥ 90 %
  with zero UL exceedances.

Snapshots are persisted per day (`score_snapshot`, PK = day) by
`ScoreSnapshotter`; goal/diet edits and ledger changes both trigger
recomputation (see `SettingsViewModel.onGoalsChanged` and the
`AppGraph` post-drain observer).

### Build

```bash
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:installDebug         # to a connected device/emulator
```

Requirements: JDK 17+, Android SDK (min 26 / target 37), no API keys needed
to build — LLM/MCP keys are configured in-app under Settings and every
network feature degrades gracefully without them.

### Unit tests

```bash
./gradlew :app:testDebugUnitTest
```

60+ tests cover the pure-JVM domain layers: `IngredientParser`, `Units`,
`FoodNormalizer`, `SupplementLabelParser`, `LlmJson` (strict-JSON
extraction), `ScoreEngine` and `NutrientGrades` (F–A report-card grading).

### Requesting Tail changes

The deeper integration (full history, meal-log rows, stable ids, change
notifications) is specified in a ready-to-send document:
**[`docs/TAIL_REQUEST.md`](docs/TAIL_REQUEST.md)** — a standalone,
copy-pasteable feature & compatibility request addressed to the Tail team.
It proposes a read-only v2 provider contract (R1–R6) plus the labels /
descriptions schema for supplement parsing, phased by priority, and asks
for no breaking changes to the existing v1 surface.

---

## Changelog

### 2026-09-23 (2) — Nutrient detail sheet: window coverages + food-suggestion quality

- `domain/insights/NutrientSourceQuality.kt` — NEW shared quality engine for
  food suggestions, applied at BOTH generation and render boundaries:
  1. **Name precision** — vague category rows ("Herbs and seasonings",
     "Spices", "Vegetables") are rejected via a category blacklist on top of
     the existing `SmartFoodMatcher.isPlausibleFoodName` gate. A name is
     vague only when EVERY meaningful token is a category word — "Chia
     seeds" still passes, "Herbs and seasonings" never will.
  2. **Density floor** — one realistic serving must cover ≥ 10% of the
     daily target; weak rows (1% DV herbs) can no longer pad the list.
  3. **Per-serving ranking** — candidates rank by % of target delivered by
     the food's typical serving (per-100 g × serving/100), fixing the
     inversion where 100 g-only "paper" foods outranked real portions.
- `RecommendationEngine` — `findCachedSources` now takes the target, applies
  all three gates, and returns `CachedSource(food, perAmount, per100,
  servingCoverage)`; both the batch and single-nutrient LLM paths also gate
  generated food names through the same precision check.
- `ui/common/NutrientDetailViewModel.kt` + `NutrientDetailSheet.kt` — the
  sheet now shows TODAY + THIS WEEK + THIS MONTH % of target (7d/30d
  averages over logged days, "no data" when never logged), and persisted
  suggestions are re-gated (vague names dropped) and ordered by stored %
  coverage, best first.
- `NutrientSourceQualityTest` — 9 unit tests: vague-name rejection matrix,
  per-serving scaling math, density floor, herbs-vs-chia inversion case,
  reason-template parsing.

### 2026-09-23 — Insights: letter-grade report card + consistent gaps

- `domain/insights/NutrientGrades.kt` — NEW pure-JVM engine: every nutrient
  from the complete definition list gets an F–A school grade over the active
  window, direction-agnostic (too low AND too high both degrade the grade).
  Never-logged nutrients grade F (unknown must not look good); untracked days
  pull the grade down. Unit-tested in `NutrientGradesTest` (11 cases: A/B/D/F
  boundaries, excess symmetry, untracked penalties, row ordering).
- `ui/insights/InsightsScreen.kt` — "What stands out" replaced by the
  "Nutrient report card": F rows first, then D/C/B, with the A rows collapsed
  behind a "Show more — N you're acing (A)" toggle (tier filter chips kept).
  The ambiguous high/watch/info severity chips no longer drive the ranking.
- `ui/insights/InsightsCards.kt` — `GradeRow` + `gradeColor` composables;
  the old `InsightCard` (severity chip) removed.
- `ui/insights/InsightsViewModel.kt` — computes `gradeRows` /
  `gradeRowsVisible` / `gradeRowsHiddenA` over the window; the
  "Foods high in your lacking nutrients" carousel is now ordered by the
  TRAILING MONTH's (30d) lacking-nutrient ranking instead of issue order —
  worst monthly gap's foods first (`monthGapRanking`, same 80% gap rule as
  `RecommendationEngine`).
- Iodine consistency fix: Home's FocusNow treated "never logged" as 0 intake
  (so sparse nutrients like iodine showed gaps + smart picks) while
  InsightsEngine skipped them entirely — the report card grades ALL
  definitions with untracked-day penalties, closing the discrepancy.

### 2026-09-21 — Seed LUT: bundled USDA panels replace most ingredient LLM calls

Full audit: [`docs/LLM_AUDIT.md`](docs/LLM_AUDIT.md). Resolution pipeline is
now **cache → seed → LLM → web**:

- **Bundled seed LUT** ([`SeedFoodLibrary`](app/src/main/java/com/example/hoot/domain/nutrition/SeedFoodLibrary.kt)):
  ~65 commonly logged whole foods with USDA SR Legacy-derived per-100 g
  panels in canonical nutrient ids/units, plus a phrase-alias table ("ground
  beef" → "beef", "spaghetti" → "pasta", …). Pure JVM, deterministic,
  integrity-tested ([`SeedFoodLibraryTest`](app/src/test/java/com/example/hoot/domain/nutrition/SeedFoodLibraryTest.kt):
  key stability under `FoodNormalizer`, canonical ids, macro/kcal sanity).
- **Resolver integration** ([`NutritionResolver`](app/src/main/java/com/example/hoot/domain/nutrition/NutritionResolver.kt)):
  seed tier (a2) sits between LookupCache and the LLM panel in the single,
  batch, and single-food-fallback paths. Seed hits persist through the normal
  Food + Profile + LookupCache + Sources path with `resolutionMethod = "seed"`
  and a `seed://usda-sr-legacy` source record — later runs are plain cache
  hits. The drain's batch phase persists seed panels BEFORE any LLM call, so
  LUT-covered foods cost zero model spend; batch LLM calls now send only
  LUT-uncovered keys.
- **Smart picks without LLM** ([`SmartFoodProvider`](app/src/main/java/com/example/hoot/domain/insights/SmartFoodProvider.kt)):
  the thin-cache LLM enhancement batch is gone; a thin cache is topped up
  from the seed LUT deterministically (diet filter still applies in the
  matcher). Works with no LLM configured at all.
- **Cheaper batch retries**: batch panel calls no longer route through
  `completeJson`'s full-batch re-ask on a malformed reply — one tolerant
  `chat` + tolerant parse salvages the keys that parsed, and the existing
  per-key single-food fallback re-asks only what's missing.
- LLM remains for photo/text meal capture, supplements, branded/composite
  foods, the web tier, coach notes, and recommendations — untouched.

### 2026-09-20 — Integration-verification fixes (follow-ups to batches A–C)

- **Tail setup remembers the saved mapping** — re-entering setup preselects the
  previously configured meal/pills/water/misc habits (same Tail package), so
  changing one mapping no longer risks silently clearing the others.
- **Searchable habit picker** — the meal/pills/water/misc habit dialogs gain a
  search-as-you-type filter when a Tail install exposes more than 5 habits.
- **Diet-filtered food mentions** — Insights "Good sources" / "Foods high in X"
  cards and Home's "What stands out" cards now run their seed food lists
  through the new deterministic [`DietTextFilter`](domain/insights/InsightsEngine.kt)
  (diet style + allergies + dislikes from Settings), matching Smart picks'
  behavior.
- **Legacy Tail backlog self-heal (fiber 0 % fix)** — Tail meals ingested
  before the ingredient-derivation fix have no ingredient rows and their sync
  cursors already advanced, so nothing ever re-analyzed them. `NutritionProcessor.backfillMissingIngredients()`
  now parses orphan Tail meals' raw text into ingredient rows at startup and
  re-aggregates the touched days; the resolver then fills their nutrition
  panels normally.

### 2026-09-20 — Post-overhaul batches A–C (Tail quick fixes, Intake capture, Smart picks)

- **Batch A — quick fixes & Tail water/misc (DB v5)**: Tail entries for water and
  misc habits now map into the ledger and render in Home's "Logged today" list
  (`HomeViewModel.tailEntries`, v5 migration + `TailEntryEntity` water/misc kinds).
  Focus/coach plumbing kept intact after the refactor — `InsightsViewModel`
  still populates `CoachNote.dietContext` from the Tail dietary profile.
- **Batch B — Intake tab (DB v6)**: new five-tab navigation
  (Home / **Intake** / History / Insights / Settings). The Intake tab hosts the
  Tail-style composer (text / photo / voice capture via `IntakeCaptureService`)
  and a read-only "Today's intake" list combining meals, supplements and
  water/misc Tail rows (`IntakeViewModel`). Meal capture columns (transcript,
  photo path) added additively via `MIGRATION_5_6`. The Home "+ Add" FAB now
  jumps to the Intake tab; legacy `add_meal` / `add_supplement` / `tail_setup`
  routes and the `Today` alias remain reachable.
- **Batch C — Smart picks**: "Smart picks for you" strip on Home
  (`SmartPicksSection.kt` + `SmartFoodMatcher`/`SmartFoodProvider`): multi-gap
  food matches from the resolved-food cache, diet-filtered, with an optional
  single batched LLM enhancement when the cache is thin and an idempotency
  memo so ledger-driven recomputes never storm the LLM.
- **Verification**: clean `assembleDebug` + `testDebugUnitTest` fully green
  (148 tests), warning-free compile after the `HelpOutline` AutoMirrored fix.

### 2026-09-19 — Phase 7: UI/UX overhaul (Home, actionability, all-tier display)

- **Home start tab** (`ui/home/`): hybrid "what should I eat today" dashboard
  replacing Today as the start destination. Primary "Focus now" section =
  nutrients lacking over the trailing 7-day average AND still lacking today,
  each row tappable into the actionable detail sheet. Score ring labeled
  ("72 /100 · Daily score") with tap → `ScoreExplainerSheet` (completeness /
  deficiency-penalty / adherence breakdown + color legend). Tier-1 bars
  visible by default with an expandable "Show Tier 2 & 3" section; quick
  stats, sparkline, Tail banner, unresolved-retry chip and logged meals all
  preserved. Bottom nav is now Home / History / Insights / Settings
  (`HootRoute.HOME`); `Today` kept as an alias route. Add-meal FAB lives on
  Home.
- **Actionable nutrient detail sheet** (`ui/common/NutrientDetailSheet.kt` +
  `NutrientDetailViewModel.kt`): remaining amount vs target, "why it
  matters" (seeded deficiency symptoms), food suggestions with per-food
  coverage (cache-first via `RecommendationEngine.generateForNutrient` —
  new on-demand single-nutrient, dedup-aware generation path), loading
  state on "More suggestions", graceful no-LLM text; accept/dismiss
  persisted to `recommendation_log` feeding adherence + ScoreEngine.
- **All-tier display**: `FocusNow` engine (`domain/insights/FocusNow.kt`,
  pure + unit-tested in `FocusNowEngineTest`) selects the focus rows;
  Insights' "What stands out" now lists deficiencies/excesses from ALL
  tiers with All/T1/T2/T3 `FilterChip`s and TierChips per row, tappable
  into the same detail sheet. Coverage radar extended to Tier-1+2 with
  labeled axes (name + %) via Canvas `drawText` and a plain-language
  caption.
- Tests: 101 unit tests green (92 prior + 9 new FocusNow selection tests);
  `assembleDebug` + `installDebug` verified on device.

### 2026-09-19 — Phase 6: final verification, polish & docs

- **Reactivity fixes** (end-to-end smoke trace): custom-goal edits
  (`saveCustomGoal` / `resetGoal` / `resetAllGoals`) now recompute all score
  snapshots and regenerate today's recommendations
  (`SettingsViewModel.onGoalsChanged`); diet changes regenerate
  recommendations too, and no longer clobber `dietary_profile.excludeFromScoring`
  when saving.
- **Dead link fixed** — the Today Tail banner's "Connect Tail" tap now opens
  the `tail_setup` route (`TodayScreen(onOpenTailSetup = …)`), previously a
  no-op.
- **Polish** — Today loading indicator (first ledger load); FAB-clear bottom
  content padding; theme-aware chart gridlines/reference lines (fixes dark
  theme contrast); friendly empty states for History (both tabs) and
  Insights (first-run guidance); deprecated `TabRow` → `PrimaryTabRow`.
- **Warning cleanup** — `@SuppressWarnings(RoomWarnings.QUERY_MISMATCH)` on
  the three intentional aggregate projections in `IntakeDao`;
  `fallbackToDestructiveMigration(dropAllTables = true)` (new non-deprecated
  overload); null-safe MCP error message formatting. Compile is now
  warning-free.
- **Docs** — README gained the final overview (features, Tail integration
  current + v2 readiness, nutrition engine, scoring, build/test
  instructions, "Tail app request" pointer). `docs/TAIL_REQUEST.md`
  verified final & standalone (no changes needed). No architecture
  deviations introduced in this phase.

### 2026-09-19 — Phase 5: full settings UI (Inuit-style)

- `ui/settings/` rebuilt from placeholder to a sectioned, Inuit-mirroring
  settings screen. New `SettingsViewModel` (AndroidViewModel over `AppGraph`)
  exposing the DataStore snapshot, LLM/MCP `TestResult` flows (Running →
  Ok/Fail), Tail config + sync state, lookup-cache stats, per-nutrient goal
  rows and JSON export. Sections:
  - **LLM** — base URL / API key (masked + Show/Hide) / model (+ suggestion
    chips glm-4.7, gpt-4o, llama-3.1-70b) / temperature slider 0–2 /
    disable-thinking switch; Save + **Test** (GET /models reachability,
    Inuit-identical messaging) with inline status; dirty-state badge.
  - **MCP** — monospace JSON editor seeded from `DEFAULT_MCP_JSON`, tool
    budget slider 0–20 (default 3), Save w/ JSON validation (inline error,
    not persisted when invalid), Test (initialize + tools/list per HTTP
    server), Reset-to-default; stdio-skipped hint.
  - **Tail** — status rows (connected app, mapped habits, last sync),
    meal-logs-unavailable banner (from `TailSyncManager` state), error
    banner, "Set up / change" → tail_setup route, "Sync now".
  - **Dietary profile** — 13 restriction chips + custom add + removable
    custom chips; disliked foods free text; Save mirrors into DataStore
    (source of truth for UI) AND the `dietary_profile` Room row the
    recommender filters on.
  - **Personal stats** — sex chips, age/height/weight fields; persisted via
    `saveUserProfile` (TODO hook: RDA adjustments not yet wired into
    ScoreEngine — static adult RDA still applies).
  - **Nutrient goals** — all `nutrient_definitions` rows with effective
    target (custom or RDA), tier badge, tap-to-edit target dialog, per-nutrient
    + reset-all-to-RDA (via new `NutrientRepository.deleteGoal`); goals feed
    ScoreEngine/recommender immediately.
  - **Sync & freshness** — background-sync toggle, interval slider
    (15–720 min), cache-TTL slider (1–365 d), web-search fallback switch.
  - **Data & cache** — cache count + oldest entry, "Clear nutrition cache"
    (keeps citation Sources), "Re-resolve failed ingredients" (kicks
    `NutritionProcessor.refreshAll`), "Export data (JSON)" share-sheet
    export of meals/ingredients/supplements/per-day intake totals; About
    card with version.
- `data/remote/LlmClient.kt` — added Inuit-parity `listModels(cfg)` +
  `normalizeModelsUrl` (Settings Test button).
- `data/repository/NutrientRepository.kt` — added `deleteGoal(nutrientId)`
  (reset-to-RDA passthrough over `NutrientGoalDao.deleteForNutrient`).
- Save-on-button semantics with `● unsaved changes` badges throughout;
  everything observable via SettingsRepository/DataStore flows.

### 2026-09-19 — Phase 4: analytics, insights, scoring & recommendations

- `domain/score/ScoreEngine.kt` (pure JVM, 21 unit tests) — daily 0–100
  score per ARCHITECTURE.md §6: `score = 0.45·completeness +
  0.35·(1 − deficiencyPenalty) + 0.20·adherence`. Completeness =
  tier-weighted coverage (T1=×3, T2=×2, T3=×1), capped at 100 %; limit-
  trackers (sodium/added sugar/sat-/trans-fat) score against their cap,
  decaying linearly to 0 at 1.5× cap (NUTRIENTS.md §7.7). Deficiency
  penalty: Tier-1 below 50 % of target, amplified up to 2× by consecutive
  low-day streaks; UL exceedances add tier-scaled penalties. Adherence =
  share of trailing-7-day recommendations actually eaten (dismissed count
  −0.25 each). Well-rounded bonus: all Tier-1 ≥ 90 % + zero UL
  exceedances floors the score at 85. `ScoreSnapshotter` persists the
  idempotent daily `ScoreSnapshotEntity` (PK = day).
- `domain/insights/InsightsEngine.kt` (pure JVM) — window analysis:
  persistent Tier-1/2 deficiencies, over-cap/UL days, score trend vs the
  previous window (±3 pts), ≥3-day streaks, "what to eat" gaps;
  severity-ranked `Insight` cards. `CoachNote.kt` — 1 strict-JSON LLM
  call with deterministic template fallback (LLM off/unreachable →
  template, never an error).
- `domain/insights/RecommendationEngine.kt` — deficiency → food:
  resolved `FoodNutrientProfile`s ranked per lacking nutrient (diet/
  allergy/dislike keyword filtering for vegan/vegetarian/pescatarian/
  keto), else ONE batched LLM call (name, why, optional image URL);
  persisted to `recommendation_log` (`accepted = null` = open) so
  ScoreEngine measures adherence; dedup per day via
  `nutrientIdsForDay`.
- `ui/charts/HootCharts.kt` — custom Compose-Canvas charts (no deps):
  animated `LineChart` w/ dashed reference line + bar underlay,
  `BarChart`, `ProgressRing`, `Sparkline`, `RadarChart` (Tier-1 shape);
  semantics content-descriptions for a11y.
- `ui/today/` — real dashboard: date header, animated score ring
  (color-coded), Tier-1 intake-vs-RDA bars, calories + macros, logged
  meals/supplements with unresolved-retry chip, Tail-sync banner,
  insight summary (top 3), 7-day score sparkline.
- `ui/history/` — nutrient-centric history: searchable tier-badged
  picker, 7d/30d/90d/1y/all windows, intake-vs-target line chart with
  dashed reference, stats header (avg, days meeting, trend arrow),
  top food contributors, and a per-day "Days" tab.
- `ui/insights/` — window selector; score trend (weekly bars + daily
  line vs 70 ref), Tier-1 %RDA radar, deficiency/excess lists with
  severity chips, recommendations carousel (Coil `AsyncImage` when an
  LLM image URL exists, deterministic food-emoji fallback otherwise —
  never crashes when absent), accept/dismiss → adherence %, coach note
  card, link into History.
- `di/AppGraph.kt` — wires `ScoreSnapshotter` + `RecommendationEngine`;
  when the nutrition queue drains, all known days' snapshots are
  recomputed (idempotent) and today's recommendations refreshed. Nav
  routes today/history/insights now host the real screens. DAO adds:
  per-(nutrient, day) totals (suspend + Flow), meal contributions,
  recommendation range/recent/count queries, `foods.all()`,
  `lookup_cache.byResolvedFoodId`.

### 2026-09-19 — Phase 3: native input + nutrition resolution engine

- `ui/input/AddMealScreen.kt` — quick-add meal (title, free-text ingredients
  with live parse preview, optional HH:mm time); saves `MealEntity` +
  one raw `IngredientEntity` per detected ingredient (pending resolution),
  kicks the processor. `ui/input/AddSupplementScreen.kt` — free text or
  known-supplement chips; saves `SupplementEntity` (source="native").
- `ui/HootNavHost.kt` — Today "+ Add" FAB menu → new `add_meal` /
  `add_supplement` routes; "Analyzing nutrition… n left" status chip on Today.
- `data/remote/LlmClient.kt` — Inuit-style OpenAI-compatible chat
  completions: URL normalization, Bearer key, `thinking:disabled` (GLM),
  `response_format: json_object`, strict-JSON `completeJson` helper with
  balanced-scanner extraction (`extractJson`) + one retry.
- `data/remote/McpClient.kt` — streamable-http MCP client (initialize
  handshake + session id, tools/list, tools/call, SSE-tolerant), stdio
  skipped; `McpWebTools` facade: `searchWeb(query)` → title/url/snippet
  results, `readUrl(url)` → text, per-run tool budget (default 3), MCP
  timeouts, graceful failure.
- `domain/nutrition/` (pure JVM, 33 unit tests green):
  - `Units.kt` — quantity/unit model (g, kg, oz, cup, tbsp, tsp, piece, …)
    + canonical nutrient conversions (IU→mcg RAE/mcg D/mg E, mg↔mcg↔g,
    NE/DFE identity, L↔ml) per `docs/NUTRIENTS.md` §6.
  - `FoodNormalizer.kt` — plural/synonym folding + descriptor stripping →
    lookup keys ("2 cups fresh tomatoes" → "tomato").
  - `IngredientParser.kt` — segment split (`,`/`;`/`+`/newline, parens
    safe), quantity+unit extraction incl. attached units ("150g"), count
    defaults, gram estimation (mass → volume → per-item hint → portion
    default).
  - `SupplementLabelParser.kt` — tolerant "Name dose unit" /
    "Name: dose" parsing ("Magnesium 400 mg" → no LLM needed).
  - `NutritionPrompts.kt` + panel parsing for all 46 seeded nutrients.
- `domain/nutrition/NutritionResolver.kt` — the pipeline:
  **LookupCache (TTL, default 90 d) → LLM per-100 g panel (confidence ≥
  0.75) → MCP web-search "…per 100g USDA" + web-reader → LLM extract →
  persist** Food + `FoodNutrientProfile` + `LookupCache` + `Source` rows
  (LLM model provenance + fetched URLs always recorded). Total failure
  leaves the ingredient unresolved (visible, retry via processor kick).
- `domain/nutrition/IntakeAggregator.kt` — idempotent per-day ledger
  recompute: grams/100 g × profile (canonicalized) + supplement
  contributions; `domain/nutrition/NutritionProcessor.kt` — sequential
  (concurrency 1) queue over unresolved ingredients/supplements,
  `state` Flow for the chip, kicked after Tail sync ingestion, manual
  entries, and startup drain.
- `di/AppGraph.kt` — wires LlmClient, resolver, aggregator, processor;
  tail-sync Success → processor kick; startup drain of stale pendings.
- DB v3: `foods` gained `typicalServingGrams` + `imageSearchTerm`
  (destructive fallback, pre-release). Settings: `cacheTtlDays` default
  90; `testImplementation("org.json:json")` for JVM tests.

### 2026-09-19 — Phase 2: Tail integration

- `data/tail/TailClient.kt` — soft-fail ContentResolver client for
  `content://com.example.tail.provider`: `/habits`, `/text_habits`,
  `/text_habits/recent?limit=N` (v1), probed `/v2/habits`,
  `/v2/habits/{id}/entries` (full backlog + `?after=` incremental) with v1
  fallback, probed `/v2/capabilities` feature flags. Meal-log endpoint
  probed; absent → `MealLogsResult.Unavailable` (never a crash).
- `data/tail/TailSyncManager.kt` — full backlog on first run, incremental
  afterwards (cursors `lastMealSyncAt`/`lastPillsSyncAt` in `tail_app_config`),
  stable-id dedup (`tail:<entryId>` / `tail:<kind>:<habit>:<tsKey>` fallback),
  `syncState` Flow (Idle/Syncing/Success/Error), coroutine periodic loop.
- `data/tail/TailSyncReceiver.kt` — permission-guarded receiver
  (`com.example.tail.permission.TAIL_INTEGRATION`) for Tail push broadcasts
  (protocol v5 `EXTRA_TIMESTAMP` pattern; R4 action pre-supported).
- `ui/tail/` — `TailSetupScreen` wizard (pick app → map meal + pills habits →
  first sync), `TailSyncBanner` on Today, `tail_setup` route with first-launch
  auto-prompt; entry point in Settings.
- Meal habit text fallback: when Tail does not expose meal logs yet, the
  meal habit's shared text entries are ingested as raw-text meals and a UI
  notice references `docs/TAIL_REQUEST.md`.
- `MainActivity` now renders `HootTheme { HootNavHost() }` (was bare Text).
- DB v2: `supplements` gained per-entry provenance columns
  (`tailHabitName`, `timestamp`, `day`, `rawText`, `source`).

### 2026-09-18 — Phase 1: foundation

Room schema (`docs/ARCHITECTURE.md` §3), nutrient seed, DataStore settings,
repositories, manual `AppGraph`, four-tab Compose shell.
