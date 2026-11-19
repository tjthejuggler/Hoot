# ADR: Foreground-Only Execution Policy (Battery Fix)

**Date:** 2026-11-19
**Status:** Accepted

## Context
Hoot was consuming massive battery in the background. Root causes:
1. `TailSyncManager.startPeriodicSync()` ran an infinite `while(true)` full-pull sync loop started at EVERY process creation — foreground or background — and never cancelled (process held hourly delay wakeups + full ContentProvider pulls forever).
2. `AutoExportManager` ran an unconditional network export at every process start plus an infinite 6-hour re-export loop.
3. `NutritionPipeline.start()` fired ~8 self-heal coroutines (full-ledger recompute of ALL days, score snapshot recompute, LLM resolution drain, diet-guard recommendation LLM call) in `AppGraph`'s constructor — i.e. in every process.
4. The killer: `TailSyncReceiver` (manifest-declared, signature-permission-gated) receives a broadcast for every meal/pill/water logged in Tail — dozens per day. Each broadcast cold-started the Hoot process in the background, triggering items 1–3 in full. The process then churned for minutes per broadcast.

## Decision
Hoot performs zero periodic/network work while backgrounded. All work is gated on foreground visibility via a started-activity-count gate (`ForegroundGate`, `Application.ActivityLifecycleCallbacks`-based; no new dependencies):

1. **`ForegroundGate` (di/ForegroundGate.kt)** — activity started/stopped counter → `foreground: StateFlow<Boolean>` + main-thread transition listeners.
2. **Tail sync**: periodic loop now takes a `visible: () -> Boolean` predicate and EXITS when the app leaves the foreground (relaunched on next foreground entry; delay-first so it never double-pulls after the entry sync). New `TailSyncManager.onForeground()` runs a sync ONLY when there is a deferred pending flag or the last pass is older than `syncIntervalMinutes`. Last-run stamp + pending flag persisted in DataStore; every completed sync pass refreshes them.
3. **Tail broadcasts**: foregrounded → sync immediately (near-real-time while user watches); backgrounded → set a one-bit persisted pending flag and finish. The next foreground session consumes it — N piled-up broadcasts collapse into ONE sync.
4. **Auto-export**: replaced startup export + 6h loop with `runOnForeground()` — at most one export per calendar day, attempted at foreground entry; day stamped in DataStore only on success (failed bridge push retries next entry; midnight rollover re-exports naturally).
5. **NutritionPipeline**: `start()` moved from `AppGraph` init to the FIRST foreground transition (once per process, stays armed — collectors are cheap idle coroutines; re-arming per session would drop in-flight sync states mid-drain). Background cold starts build the graph and immediately go quiet.

## Consequences
- A backgrounded Hoot performs zero ContentProvider pulls, zero network calls, zero recomputes. Battery drain from background broadcasts is eliminated.
- Tail data logged while Hoot is closed lands at the next app open (one deferred sync) instead of immediately — acceptable for a nutrition journal; the user sees data the moment they look at the app.
- Manual "Sync now" (Settings/TailSetup) behavior unchanged — user-triggered syncs still run on demand.
- New DataStore keys: `tail_sync_pending`, `tail_sync_last_run`, `tailcue_export_day` (bookkeeping only, not surfaced in AppSettings/UI).