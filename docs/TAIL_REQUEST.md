# Feature & Compatibility Request: Extended Read API for External Companion Apps

**To:** the Tail (habit tracker) team
**From:** a same-keystore companion app that wants to read habit history
**Date:** 2026-09-19
**Status:** REQUEST — no Tail code changes implied by this document; it only
states what an external app needs and proposes a contract Tail may adopt.

---

Hi Tail team!

First, thank you — the ContentProvider + signature-permission integration
(`com.example.tail.provider`, `com.example.tail.permission.TAIL_INTEGRATION`)
already works beautifully. The Inuit integration shows how well this pattern
works in practice.

We'd love to build a deeper companion integration on top of it, but today's
provider surface is a bit narrower than what a "read the whole history"
companion needs. This document is a polite feature request: everything below
is **read-only**, respects Tail's existing permission model, and is
incrementally adoptable (each numbered request stands alone).

---

## 1. What we need — summary

| # | Request | Why |
|---|---|---|
| R1 | Enumerate **all** apps/habits with **id, name, and type** | We must find the user's meal habit and their text habits programmatically — today we cannot tell a meal habit from a counter from a text habit |
| R2 | Read the **FULL history** of a chosen habit (all entries + timestamps + values), not just the ≤5-entry / 14-day `text_habits/recent` slice | Nutrition analysis needs the complete meal/pill backlog, not a tiny recent window |
| R3 | **Incremental sync**: entries created/changed after timestamp X | Polling the full backlog on every sync would be wasteful for years of data |
| R4 | A way to **subscribe/poll for new entries** (change cursor, content-observer, or even a broadcast on write) | Live-ish updates without hammering the provider |
| R5 | **Stable entry IDs** for every exposed entry | Dedup on sync; today, timestamp-keys are the only identity and text entries can share/shift keys |
| R6 | **Versioned API / capability flags** | A companion can adapt instead of crashing when Tail evolves |

---

## 2. Proposed contract

All URIs below live under Tail's existing authority
(`content://com.example.tail.provider`) and are covered by the existing
signature permission (`com.example.tail.permission.TAIL_INTEGRATION`) —
read-only, same-keystore callers only, exactly like today. Suggested:
`android:readPermission` on the provider (unchanged), no write endpoints.

### R1 — Habit & app enumeration

**Proposed URI:** `content://com.example.tail.provider/v2/habits`

| Column | Type | Meaning |
|---|---|---|
| `habit_id` | String | stable ID (the habit name is fine if that's what Tail uses internally — see R5) |
| `habit_name` | String | display name, e.g. "Food", "Took Pills" |
| `habit_type` | String | one of: `counter`, `text`, `meal`, `timed`, `dated_entry`, `sleep`, `subtyped`, `app_link`, `other` |
| `has_options` | Boolean | text-habit "show options" sub-feature on |
| `is_sharable` | Boolean | text-habit sharable sub-feature on |
| `subtype_names` | String (JSON array) | for subtyped habits, e.g. `["breakfast","lunch","dinner"]` |

Same for **apps** if Tail ever has multiple "app" groupings:

`content://com.example.tail.provider/v2/apps` → `app_id`, `app_name`, plus
`habit_count`.

**Minimum viable alternative (v1-only change):** add a single `habit_type`
column to the existing `/habits` endpoint. That alone would unblock habit
discovery for us.

Example cursor content:

```
habit_id=Food        habit_name=Food        habit_type=meal     …
habit_id=Took Pills  habit_name=Took Pills  habit_type=text     has_options=1
habit_id=Meditations habit_name=Meditations habit_type=counter
```

### R2 — Full history

**Proposed URIs:**

- `content://com.example.tail.provider/v2/habits/{habit_id}/entries` — every entry of that habit, oldest-first
- Same with query params: `?from=YYYY-MM-DD HH:mm:ss` / `?to=…` for range reads
- A JSON export alternative is equally welcome — e.g. an "Export for companion"
  button writing one file per habit; we can read it via SAF. Provider is
  preferred, though, because it avoids storage-permission friction.

**Meal-type habits** (`habit_type=meal`) — today's meal lives in
`files/meal_logs/<habit>.json` as an array of rich records. We'd love them
exposed as rows:

| Column | Type | Meaning (matches Tail's internal `MealLog`) |
|---|---|---|
| `entry_id` | String | `MealLog.id` (UUID) — see R5 |
| `habit_name` | String | e.g. "Food" |
| `timestamp` | Long | epoch millis the meal was logged |
| `group_start_timestamp` | Long? | anchor of the close-succession capture group (null on the first of a group) |
| `title` | String | "Vegan Buddha Bowl" |
| `summary` | String? | LLM/user summary |
| `calories` | Int | estimated kcal |
| `protein_grams`, `carbs_grams`, `fat_grams` | Double | macros |
| `ingredients` | String (JSON array) | e.g. `["150 g lentils","spinach","1 tbsp olive oil"]` |
| `is_vegan_verified` | Boolean | per user dietary rules |
| `health_notes` | String? | |
| `voice_transcript` | String? | spoken description |
| `is_manual` | Boolean | no LLM involved |
| `counted_increment` | Boolean | whether the log incremented the habit counter |

JSON example of one row's payload (mirroring the internal file format exactly —
no transformation needed on Tail's side, just serialization):

```json
{
  "id": "b1e0d6f4-9a2e-4f6c-8f1a-3d2b7c9e5a10",
  "habitId": "Food",
  "timestamp": 1768848000000,
  "groupStartTimestamp": null,
  "title": "Vegan Buddha Bowl",
  "summary": "Lentils with spinach and tahini dressing",
  "calories": 620,
  "macronutrients": { "proteinGrams": 28.4, "carbsGrams": 71.0, "fatGrams": 22.6 },
  "ingredientsDetected": ["150 g cooked lentils", "spinach", "1 tbsp tahini"],
  "isVeganVerified": true,
  "healthNotes": "High iron; pair with vitamin C for absorption",
  "isManual": false,
  "voiceTranscript": null,
  "macroRatings": { "protein": 3, "carbs": 2, "fat": 2 },
  "countedIncrement": true
}
```

**Text-entry habits** (`habit_type=text`) — full log as rows:

| Column | Type | Meaning |
|---|---|---|
| `entry_id` | String | stable id (R5) |
| `habit_name` | String | e.g. "Took Pills" |
| `entry_ts` | String | log key `"yyyy-MM-dd HH:mm:ss"` (same format as today) |
| `entry_text` | String | **full, untruncated** text (today's 300-char cap doesn't work for us) |

This is exactly today's `text_habits/recent` shape minus the 14-day / 5-entry /
300-char bounds. Those bounds make total sense for Inuit's "inspiration"
use-case — we just need an *unbounded* sibling endpoint, e.g.
`/v2/habits/{id}/entries`, so the privacy-conscious small slice can stay.

### R3 — Incremental sync

Add `?after=<timestamp>` (same `yyyy-MM-dd HH:mm:ss` string, or epoch millis —
whichever is easier on Tail's side) to the R2 endpoints, returning only entries
with `timestamp > after`. A companion stores the max seen and passes it on the
next sync. Alternative that's equally fine: a monotonically increasing
`row_version` column we can filter on.

### R4 — New-entry notification

Any ONE of these is plenty (in order of preference):

1. **Content observer support** — rows come from a MatrixCursor today, so
   `notifyChange()` on the URI would let us register a
   `ContentObserver` and re-sync only when something actually changed.
2. **A write broadcast** — e.g. `com.example.tail.ACTION_ENTRY_ADDED` with
   extras `EXTRA_HABIT_ID`, `EXTRA_ENTRY_ID`, permission-guarded like the
   existing increment broadcasts.
3. **A cheap "last modified" endpoint** —
   `content://…/v2/changes` returning a single row `last_change_ts`; we poll
   it on our own schedule and do a full incremental pull only when it moved.

### R5 — Stable entry IDs

For dedup we need every exposed entry to have an `entry_id` that:

- is unique per habit (globally unique is nicer),
- survives app restarts and Tail updates,
- does NOT change on habit rename (the habit *name* changing should not
  orphan our local rows).

For meal logs the `MealLog.id` UUID already exists — exposing it satisfies
this request completely. For text habits, the timestamp-key is *almost* unique
already; a UUID (or `habit_name + entry_ts` hash documented as stable) would
be enough. If Tail renames a habit, keeping the same `habit_id` (or exposing a
renamed_at / old-name alias) would let companions follow it.

### R6 — Versioned API / capability flags

**Proposed:** `content://com.example.tail.provider/v2/capabilities` returning
one row:

| Column | Type | Example |
|---|---|---|
| `api_version` | Int | `2` |
| `min_supported_version` | Int | `1` |
| `features` | String (JSON array) | `["habits_v2","entries_full","entries_incremental","meal_logs","text_options","capability_flags"]` |

A companion queries this first; anything missing degrades gracefully to the v1
endpoints. When Tail adds features later, we adapt by feature-flag instead of
by crash-and-burn column mismatches.

---

## 3. "Took Pills" — current payload and the upcoming labels + descriptions feature

**Current state** (what we can see today): "Took Pills" is a text-input habit
with the "show options" sub-feature. Its log is
`{ "yyyy-MM-dd HH:mm:ss": "<free text>" }`, e.g.:

```json
{
  "2026-09-18 08:30:12": "Magnesium 400 mg",
  "2026-09-18 08:30:45": "Vitamin D3 2000 IU"
}
```

For a nutrition companion, the interesting part is the **options** sub-feature:
Tail already stores, per habit, a map of option text → description
(`textInputOptionDescriptions`), and shows counts of past usage. The upcoming
labels + descriptions feature would formalize this — when it lands, we'd love
the exact schema. Specifically, please document:

1. **Labels list** — per habit: the ordered set of canonical labels
   (supplement names) the user can pick. Proposed exposure:
   `content://…/v2/habits/{id}/labels` →

   | Column | Type | Example |
   |---|---|---|
   | `label_id` | String | `"magnesium_citrate"` (or stable auto-id) |
   | `label_text` | String | `"Magnesium"` |
   | `description` | String? | `"400 mg citrate, morning"` — the existing option-description metadata |
   | `default_amount` | Double? | `400` |
   | `default_unit` | String? | `"mg"` |
   | `nutrient_tags` | String? (JSON array) | optional — if the labels feature ever grows structured tags |

2. **Per-entry label values** — when an entry references a label, we need to
   know which one (and any per-entry amount override):

   | Column (added to entries rows) | Type | Example |
   |---|---|---|
   | `label_id` | String? | `"magnesium_citrate"` |
   | `label_amount` | Double? | `400` |
   | `label_unit` | String? | `"mg"` |

3. **Rename semantics** — Tail's option renames are retroactive (they rewrite
   past log entries). If that stays true, a label rename will *change history*
   — please either (a) expose the label_id on entries (above) so renames don't
   break our parsing, or (b) document a `labels/changes` feed with
   old→new mappings so companions can rewrite their local caches.

With that, "Took Pills" entries become machine-readable supplement doses —
e.g. `label_text="Magnesium"`, `amount=400`, `unit="mg"` — and a nutrition app
can credit the day's magnesium intake without any text parsing at all.

If the feature isn't built yet, the **minimum** that unblocks us is simply
exposing `textInputOptionDescriptions` for shared text habits (habit_name →
option_text → description). That alone lets us parse "Magnesium" + "400 mg
citrate" reliably.

---

## 4. What stays the same (so you don't have to break anything)

- Authority, signature permission, read-only semantics: **unchanged**.
- Existing v1 endpoints (`/habits`, `/text_habits`, `/text_habits/recent`)
  with their bounds: **keep as-is** — the tight slice is a feature (privacy,
  token-budget) for the Inuit use-case, not a bug.
- Privacy posture: the user explicitly opts habits in per integration
  (Tail Settings → Integrations). We'd expect the same opt-in surface (or a
  v2 equivalent) for full-history sharing — full-backlog read is sensitive,
  and per-habit user consent is exactly the right gate.

## 5. Priority

If you'd rather phase this:

1. **R1 (habit_type)** + **R2-for-text (full text entries)** — smallest change,
   biggest unlock.
2. **R2-for-meal (meal-log rows)** + **R5 (entry ids)**.
3. **R3 (incremental)** + **R4 (change notification)**.
4. **R6 (capabilities)** + the labels/descriptions schema (§3).

Thanks for considering — happy to test any prototype against a companion
build, and happy to adjust shape/naming to whatever fits Tail's internals
best. The JSON examples above are *our* wish, not a mandate: any format that
carries the same information works for us.

— A grateful companion-app developer
