# ADR addendum: Focus Now scroll polish (2026-11-17, feedback round 2)

**Status:** accepted

## Decision
1. Removed the "↓ N nutrients — scroll for more" hint row and the count-mentioning section subtitle. The header now reads "Chronically low nutrients, ranked by long-term gap."
2. New shared modifier `Modifier.killParentFling()` in `HootUi.kt`: a `NestedScrollConnection` whose `onPostFling` returns the leftover velocity as consumed. Attached BEFORE the scroll modifier on fixed-height inner scrollables, it makes a fling die at the section edge instead of chaining to the parent dashboard list. Deliberate slow drags still chain (standard nested scroll), so pulling past a section with a slow gesture continues to scroll the main screen — only fling momentum is killed.
3. Applied to both nested scrollables on Home: the Focus-now `verticalScroll` Column and the Likely-symptoms fixed-height `LazyColumn`. Other `verticalScroll`/`LazyColumn` usages are full-screen or bottom-sheet hosted (not nested inside a parent scroller) and need no change; any future fixed-height inner scroller should reuse `killParentFling()`.

## Verification
`:app:compileDebugKotlin` + full unit suite green (44 classes, 0 failures); `installDebug` deployed to SM-S918U1.
