# ADR: Cache-first Home startup (stale-while-revalidate, 2026-11-16)

## Context
Fresh launches of the Home dashboard could show the loading state for a very long time, sometimes seemingly indefinitely. Root causes: (1) the first `HomeUiState` emission waits on the full pipeline — score recompute, `computeSymptoms` (one `dailyTotalsForWindow` GROUP BY per nutrient per window ≈ 111 scans), and `SmartFoodProvider.cacheCandidates` (one `profileForFood` query per food ≈ hundreds of N+1 round-trips); (2) startup self-heals in `NutritionPipeline.start()` rewrite the intake ledger concurrently, invalidating the Room observe-flows, so `flatMapLatest` cancels and restarts the in-flight compute repeatedly; (3) nothing from the previous session was shown while all this ran.

## Decision
1. **Stale-while-revalidate cache**: every computed `HomeUiState` is persisted to `filesDir/home_state_cache/home_ui_state.json` via `HomeStateCache` (org.json codec, atomic tmp+rename). On collector start the cached state is emitted first (`fromCache=true, loading=false`); the live flow continues in the background and its first emission replaces the cache wholesale. UI shows a thin "Updating from your latest logs…" hint instead of a blocking spinner when serving cache.
2. **Batched deficiency windows**: `computeSymptoms` pre-fetches each window (30/90/365-day) ONCE and slices in memory — 3 queries total instead of ~111; output identical.
3. **Bulk profile read**: `ProfileDao.all()` → `FoodRepository.profilesAll()` → `NutrientRepository.profilesAll()`; `cacheCandidates` now joins foods against one prefetched map instead of per-food queries.
4. Only LIVE results (`!fromCache && !loading`) rewrite the cache file; corrupt/blank cache payloads are rejected (null) and fall back to the old loading behavior.

## Consequences
- Home is instantly usable on launch whenever a previous session computed anything; background refresh swaps sections in as they land.
- The cache is display-only: it never gates writes, resolution, or scoring; a stale cache cannot corrupt data.
- `HomeUiState` gained a `fromCache` field (UI-hint only); codec is unit-tested (`HomeStateCacheTest` round-trips every nested model).
- Symptom ranking values are unchanged (same windows, same math) — only the number of SQL queries dropped.