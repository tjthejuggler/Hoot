# Hoot — LLM Usage Audit (Ingredient Resolution)

> Audit date: 2026-09-21 · Scope: every `LlmClient` call site, the
> cache→LLM→web pipeline, and the cost/accuracy trade-offs of replacing most
> of it with a bundled nutrition LUT + USDA/OFF lookup.
> Verdict up front: **~85–90 % of today's ingredient-resolution LLM calls can
> be eliminated with a bundled USDA-based seed + a "seed-on-first-resolve"
> LUT, at equal or better accuracy. The LLM remains a fallback, not the
> primary source.**
>
> **STATUS (2026-09-21, later same day): IMPLEMENTED.** §4's pipeline ships as
> [`SeedFoodLibrary`](../app/src/main/java/com/example/hoot/domain/nutrition/SeedFoodLibrary.kt)
> (~65 USDA-derived foods + aliases, integrity-tested) wired into
> [`NutritionResolver`](../app/src/main/java/com/example/hoot/domain/nutrition/NutritionResolver.kt)
> (single / batch / single-food paths, `method="seed"`,
> `seed://usda-sr-legacy` source records) and
> [`NutritionProcessor`](../app/src/main/java/com/example/hoot/domain/nutrition/NutritionProcessor.kt)
> (seed persists before any LLM batch call). §6.3 (smart-picks thin-cache LLM
> batch) is REMOVED — replaced by the deterministic seed top-up in
> [`SmartFoodProvider`](../app/src/main/java/com/example/hoot/domain/insights/SmartFoodProvider.kt).
> §6.1 (batch retry waste) is fixed: batch calls use one tolerant
> `chat` + `parsePanelBatch`; only missing keys take the single-food
> fallback. §6.2 remains documented behavior. Remaining opportunity: expand
> the LUT (§3) and, optionally, a user-cache export (§4.3).

---

## 1. Where LLM calls happen today (complete map)

| # | Call site | Purpose | Frequency | Batchable |
|---|---|---|---|---|
| 1 | [`IntakeCaptureService.captureMeal`](../app/src/main/java/com/example/hoot/data/intake/IntakeCaptureService.kt:76) | photo/text → structured `CapturedMeal` | every capture | no (needs LLM) |
| 2 | [`NutritionResolver.resolveIngredient`](../app/src/main/java/com/example/hoot/domain/nutrition/NutritionResolver.kt:54) | single-ingredient per-100 g panel | cache miss | yes |
| 3 | [`NutritionResolver.resolveFoodsBatch`](../app/src/main/java/com/example/hoot/domain/nutrition/NutritionResolver.kt:202) | 8 foods per call | cache miss drain | — |
| 4 | [`NutritionResolver.resolveSingleFood`](../app/src/main/java/com/example/hoot/domain/nutrition/NutritionResolver.kt:256) | batch-failure fallback | rare | no |
| 5 | [`NutritionResolver.resolveSupplementGroupsBatch`](../app/src/main/java/com/example/hoot/domain/nutrition/NutritionResolver.kt:300) | per-serving supplement panels | cache miss | yes |
| 6 | [`NutritionResolver.resolveSupplementGroupSingle`](../app/src/main/java/com/example/hoot/domain/nutrition/NutritionResolver.kt:356) | supplement fallback | rare | no |
| 7 | [`NutritionResolver.resolveViaWeb`](../app/src/main/java/com/example/hoot/domain/nutrition/NutritionResolver.kt:431) | MCP web-read → LLM extraction | low confidence | no |
| 8 | [`SmartFoodProvider.generateViaLlm`](../app/src/main/java/com/example/hoot/domain/insights/SmartFoodProvider.kt:231) | thin-cache food suggestions | ≤1/gap-signature | — |
| 9 | [`RecommendationEngine` LLM asks](../app/src/main/java/com/example/hoot/domain/insights/RecommendationEngine.kt:368) | daily recommendations | per refresh | — |
| 10 | [`CoachNote.generate`](../app/src/main/java/com/example/hoot/domain/insights/CoachNote.kt:83) | daily coach note | 1/day | no |

The intake path the user asked about is #2–#7 (plus #1's structured extraction,
which genuinely needs an LLM for photos/free-text). #8–#10 are insights-layer
and out of the core complaint, though #8 partially overlaps the LUT idea.

**What already avoids calls** (credit where due — the skeleton is right):
- [`NutritionProcessor.drainPending`](../app/src/main/java/com/example/hoot/domain/nutrition/NutritionProcessor.kt:211) phase 1 is a **cache sweep with zero LLM calls**; per-distinct-food grouping means 891 rows ≈ 74 foods, not 891 calls.
- Free supplement label parse ([`SupplementLabelParser`](../app/src/main/java/com/example/hoot/domain/nutrition/SupplementLabelParser.kt)) before any LLM.
- [`SmartFoodProvider`](../app/src/main/java/com/example/hoot/domain/insights/SmartFoodProvider.kt:60) memoizes per gap-signature.
- [`LlmRateGuard`](../app/src/main/java/com/example/hoot/domain/nutrition/LlmRateGuard.kt) + batching + 90-day TTL cache.

**So the waste is not architecture — it's that the cache *starts empty* and
can only be filled by LLM calls.** Every new food key on a fresh install = 1+
LLM call, forever paying LLM variance for the same "chicken breast" values.

## 2. What the LLM is being asked to do (and why that's the weak point)

[`NutritionPrompts.foodPanelUserPrompt`](../app/src/main/java/com/example/hoot/domain/nutrition/NutritionPrompts.kt:40)
asks for a **46-nutrient USDA-grade panel from model memory**. Problems:

1. **Memory ≠ database.** LLMs interpolate nutrition from training text; for
   ~500 common foods the "true" USDA values are public data. The model is
   being used as a *compression* of the exact table we could bundle.
2. **Self-reported confidence is uncalibrated.** The
   [`CONFIDENCE_THRESHOLD = 0.75`](../app/src/main/java/com/example/hoot/domain/nutrition/NutritionResolver.kt:753)
   gate trusts a number the model made up about itself. Web fallback then
   does MCP search + LLM extraction — 2+ more calls with LLM-parsed numbers.
3. **Non-determinism.** Same food asked twice (different day, temperature
   0.2, thinking on/off) yields different panels; the cache freezes whichever
   came first. A database lookup is identical every time.
4. **Batch fragility.** 46-nutrient × 8-food batches invite truncated/malformed
   replies (hence the whole [`parsePanelBatch`](../app/src/main/java/com/example/hoot/domain/nutrition/NutritionPrompts.kt:145)
   tolerance layer, single-food fallbacks, and alias-folding in
   [`NutrientKeys`](../app/src/main/java/com/example/hoot/domain/nutrition/NutrientKeys.kt)).
   A LUT can't drop keys or spell "fibre".

## 3. Is there an existing library/dataset? — Yes; no Android "library," but the data is free and small

There is no polished Android/Java library that ships ingredient→nutrient data
in the shape Hoot needs (per-100 g panel keyed to Hoot's 46 `nutrient_id`s).
What exists is *data sources*, which is exactly what a LUT needs:

| Source | What it gives | Fit for Hoot |
|---|---|---|
| **USDA FoodData Central (Foundation + SR Legacy)** | Gold-standard per-100 g, ~300k foods, full micronutrients | Best accuracy; mapping `fdc_id → Hoot nutrient_id` is a one-time build script. Public domain. |
| **Open Food Facts** (offline SQLite/RDF dumps, `openfoodfacts-android` lib exists but stale) | Packaged/branded products | Good for branded items; per-100 g normalized already. |
| **Open Nutrition / food-nutrient SQLite dumps** (community repackages of USDA SR on GitHub) | Pre-built ~1–4 MB SQLite of core foods | Fastest to ship: drop-in `assets/` DB, read-only Room/SQLiteDatabase. |
| `nutrition-calc` style JVM libs | RDA math, not food tables | Not useful — Hoot already has that in [`ScoreEngine`](../app/src/main/java/com/example/hoot/domain/score/ScoreEngine.kt). |

**Recommendation: bundle a curated top-~600-food subset of USDA SR Legacy as a
read-only asset database (or flat JSON → Room prepopulate), keyed by the exact
`normalizedName` produced by [`FoodNormalizer`](../app/src/main/java/com/example/hoot/domain/nutrition/FoodNormalizer.kt).**
The `FoodNormalizer` synonym/singularization table is already the front of the
LUT — it just currently has no static table behind it.

Why subset, not full 300k: Hoot's grammar is meal ingredients ("tomato",
"chicken breast", "brown rice"), not barcode products. The ~600-food subset of
commonly logged whole foods covers the overwhelming majority of distinct keys
the processor will ever see, at ~200–400 KB. Branded/processed items fall
through to the existing LLM/web tier — which is precisely where an LLM adds
value over a table.

## 4. Proposed pipeline (replaces steps (b)/(c) for the common case)

```
ingredient text
 → parse (IngredientParser)          — free, deterministic
 → normalize (FoodNormalizer)        — free, deterministic
 → (a) LookupCache (Room, TTL)       — free, exact today's behavior
 → (a2) BUNDLED SEED LUT             — free, deterministic, accurate   ← NEW
       hit → same persistResolution(...) with method="seed"
 → (b) LLM panel                     — only genuine misses (branded/
                                       composite/regional foods)
 → (c) MCP web → LLM extract         — unchanged, budgeted
 → (d) persist + LookupCache         — unchanged (seed entries cache too,
                                       so even repeated seeds cost 0)
```

Implementation notes (small, no schema change required):

1. New `SeedFoodSource` in `domain/nutrition/`: `suspend fun lookup(key: String): FoodPanel?`
   reading `assets/nutrition_seed.db` (schema = subset of `foods` +
   `food_nutrient_profile` with Hoot nutrient ids).
2. Insert one branch in [`NutritionResolver.resolveIngredient`](../app/src/main/java/com/example/hoot/domain/nutrition/NutritionResolver.kt:84)
   and in the processor's phase 2 grouping (attempt `seedNutrientPanel(key)`
   before `resolveFoodsBatch`). Persist through the existing
   `persistResolution(..., method = "seed", ...)` so Sources/provenance shows
   "USDA SR Legacy (bundled)" instead of an `llm://` URL.
3. **Seed-on-first-resolve LUT (the user's idea, generalized):** every LLM/web
   resolution *already* lands in `LookupCache` keyed by `normalizedName`.
   That cache **is** the dynamic LUT. Two cheap upgrades make it self-improving:
   - Ship a first-run **alias expansion**: seed LUT keys ↔ FoodNormalizer
     synonyms, so "prawn" hits the "shrimp" seed row (the synonym table
     already folds most of these — the LUT just inherits it).
   - Optionally sync the user's accumulated cache **out** to a JSON export in
     Settings (already half-built via `LookupRepository.observeCacheByHits`) —
     heavy users effectively curate their own accurate LUT over time.
4. Supplement side is already optimal (label parse → LLM); keep it.

Expected call reduction: on a fresh install, first ~2 weeks of logging drop
from ~1–3 LLM calls per distinct food to **0** for common whole foods
(realistically 80–90 % of keys); the remainder (recipes, branded, unusual
items) still use the existing tiers. Cache hit-rate math is unchanged; the
seed simply pre-warms the cache with *better-than-LLM* values.

## 5. Accuracy: seed beats LLM on every axis it covers

- **Ground truth**: USDA SR numbers vs model memory — the exact source the
  prompt ([`NutritionPrompts.SYSTEM`](../app/src/main/java/com/example/hoot/domain/nutrition/NutritionPrompts.kt:34))
  asks the model to *pretend* to be.
- **No fabricated keys**: the seed contains only valid Hoot nutrient ids; the
  whole `canonicalId` alias-folding defensive layer exists only because LLM
  output drifts.
- **Deterministic tests**: `IngredientParserTest`/`PanelBatchParseTest`-style
  JVM tests can assert exact panels against the seed; LLM paths can't be
  unit-tested for values at all.
- **Serving hints**: seed rows ship with `typical_serving_grams`, improving
  the gram-estimate path ([`PORTION_DEFAULTS`](../app/src/main/java/com/example/hoot/domain/nutrition/NutritionResolver.kt:767)
  currently covers only 20 foods).
- Items *not* in the seed keep today's quality exactly (LLM → web), so the
  change is strictly non-regressive.

## 6. Secondary findings (LLM calls that can be reduced without new data)

1. **`completeJson` retry doubles call cost on malformed replies**
   ([`LlmClient.completeJson`](../app/src/main/java/com/example/hoot/data/remote/LlmClient.kt:108)).
   With 8-food batches this is an entire duplicate batch call. Cheaper:
   track which keys parsed from the *first* reply and only re-ask for missing
   keys (the `parsePanelBatch` + `missing` machinery already supports this —
   currently the retry happens before parsing ever runs).
2. **`disableThinking` inconsistency**: single-food panels honor the user
   setting, batch calls hardcode `disableThinking = true`
   ([`NutritionResolver.resolveFoodsBatch`](../app/src/main/java/com/example/hoot/domain/nutrition/NutritionResolver.kt:221)).
   Fine for cost, but document it — thinking-on singletons can disagree with
   the batch values that pre-filled the cache.
3. **Smart-picks LLM can be replaced by the seed**: `candidates.size < MIN_CANDIDATES`
   ([`SmartFoodProvider.refresh`](../app/src/main/java/com/example/hoot/domain/insights/SmartFoodProvider.kt:156))
   fires an LLM call on cold start. With a 600-food seed, the cache is never
   thin — that call disappears on day one.
4. **RecommendationEngine LLM asks** overlap with the same idea (food→nutrient
   knowledge). Once candidates come from the LUT, recommendations can be
   scored deterministically and the LLM call there becomes optional polish
   rather than the data source.

## 7. What we are NOT recommending

- Dropping the LLM: photos (`chatVision`) and free-text meal structuring need
  it; branded/rare foods benefit from the web tier; coach-note and
  recommendation prose are legitimate generative uses.
- Shipping full USDA FDC (300k rows): bloats the APK ~50–100 MB for keys the
  user will never log. Subset + fallback wins.
- An on-device RAG/embedding index: overkill; `FoodNormalizer` + a few hundred
  synonyms covers the join, and misses degrade to the existing LLM tier.

## 8. Effort estimate

| Step | Size |
|---|---|
| One-time build script: USDA SR → Hoot-schema subset JSON/SQLite (~600 foods) | half a day (script, run off-device) |
| `SeedFoodSource` + resolver branch + `method="seed"` provenance | small (`~120` LOC + tests) |
| Alias table expansion (seed keys ↔ FoodNormalizer synonyms) | trivial |
| `completeJson` partial-retry fix (finding 6.1) | small, independent win |
| Smart-picks seed integration (finding 6.3) | near-zero once seed exists |
