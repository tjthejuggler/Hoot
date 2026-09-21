# Hoot — Architecture & Technical Plan

> Nutrition-recommendation companion for the Tail habit tracker.
> Status: design document (pre-implementation). Last revised: 2026-09-19.

---

## 0. Deviations from this plan (as implemented, phases 1–6 + 2026-09-20 batches)

The sections below are the original design; the shipped code diverges in
these places (all intentional, none affect the external contract):

| Plan | Implementation | Why |
|---|---|---|
| WorkManager `TailSyncWorker` for backlog polling | Coroutine periodic loop in `data/tail/TailSyncManager.kt` + permission-guarded manifest `TailSyncReceiver` | Same-keystore push broadcast makes polling-to-survive-death unnecessary; a loop honors interval changes instantly without WorkManager constraints. Revisit only if a periodic-under-doze guarantee is ever required. |
| `data/nutrition/` (resolver, aggregator, score, recommender) | Split into `domain/nutrition/`, `domain/score/`, `domain/insights/` (all pure-JVM) | Keeps every engine Android-import-free and JVM-unit-testable (55 tests green). |
| `data/repo/` (Food/Meal/Intake/Recommendation repositories) | `data/repository/` (`MealRepository`, `NutrientRepository`, `LookupRepository`, `TailConfigRepository`) | Fewer, facaded repositories over 15 DAOs. |
| `data/settings/SettingsStore.kt` | `data/local/SettingsRepository.kt` (DataStore-backed) | Naming consistency with the other repositories. |
| `data/remote/llm/` incl. `McpSession.kt` | Flat `data/remote/` (`Http.kt`, `LlmClient.kt`, `McpClient.kt`); session handling folded into `McpClient` | One session per client instance was enough; no separate session object needed. |
| Optional direct USDA FoodData Central REST fallback (`data/remote/usda/`) | Not implemented — MCP web-search/read covers the fallback tier | The MCP path already delivers sourced per-100 g data; a direct REST tier would duplicate it. |
| Optional **Vico** for standard line charts | All charts custom Compose-Canvas (`ui/charts/HootCharts.kt`) | Deterministic styling, theme-colored, zero extra dependency. |
| Settings sections as separate files (`LlmSettingsSection.kt`, …) | `ui/settings/` split across `SettingsScreen.kt` + `SettingsSections.kt`, `SettingsGoalsSection.kt`, `SettingsProfileSections.kt`, `SettingsDataSections.kt` | Same separation of concerns, grouped by screen region instead of one file per section. |
| Dashboard route `ui/dashboard/`, meals route `ui/meals/`, stats `ui/stats/`, recommendations `ui/recommendations/` | `ui/today/`, `ui/history/`, `ui/insights/`, plus `ui/input/` (add meal/supplement) and `ui/tail/` (setup) | Names match the tab navigation; recommendations live inside Insights per the phase-4 scope. |
| Four-tab navigation (Today / History / Insights / Settings), Room schema stops at v4 | **Five-tab navigation** Home / Intake / History / Insights / Settings (`HootRoute`, `Today` kept as alias route); DB **v5** (Tail water/misc entries) and **v6** (meal capture columns: transcript/photo path) via additive migrations `MIGRATION_4_5`, `MIGRATION_5_6` | Intake tab consolidates the Tail-style composer (text/photo/voice); schema growth is additive only, existing installs migrate in place. |
| Smart picks recompute freely per ledger emission | `SmartFoodProvider` memoizes the last (day, gap-signature) result — identical recomputes return the cached result and the thin-cache LLM batch fires at most once per distinct gap set | Prevents recompute/LLM storms while multi-pass meal resolution ticks the daily-totals Flow. |

Everything else — Room schema (§3), Tail provider contract (§4), the
cache→LLM→web resolution pipeline (§5), the score formula (§6), and the
chart/a11y approach (§7) — matches the plan.

---

## 1. Technology Stack

Chosen to mirror Inuit's proven approach wherever possible, extended only where
Hoot's domain (persistence, charting, images) demands it.

| Concern | Choice | Rationale |
|---|---|---|
| Language | **Kotlin 2.2.x** | Same as Tail / Inuit / current Hoot template |
| UI | **Jetpack Compose + Material 3** | Template already set up (`composeBom 2026.02.01`) |
| Architecture | **MVVM** (ViewModel + StateFlow) | Matches Inuit's `MainViewModel` pattern |
| Persistence | **Room** (SQL) + **DataStore Preferences** (settings) | Hoot needs relational nutrition data; Inuit uses DataStore alone but stores no relational data |
| Async | **Kotlinx Coroutines + Flow** | Matches Inuit (`1.10.x`) |
| DI | **Manual DI via an `AppGraph`** | Matches Inuit's `AppGraph` ("Hand-rolled DI graph — small app, no framework needed"). No Hilt. |
| Networking | **OkHttp + org.json (raw JSON)** | Matches Inuit exactly — it deliberately avoids Retrofit/Moshi; `LlmClient`/`Http.kt`/`McpClient.kt` are hand-rolled and battle-tested |
| Images | **Coil Compose** | Food photos in the recommendations carousel |
| Charts | **Compose Canvas custom charts** (like Inuit's `ui/charts/Charts.kt`) + optional **Vico** for line charts | Hoot's charts are domain-specific (nutrient timelines, radar) — Canvas gives full control, Vico for standard lines |
| MCP | **Streamable-HTTP MCP client** (JSON-RPC 2.0 over POST) | Port Inuit's `McpClient.kt` / `McpSession.kt` design nearly verbatim |
| Background sync | WorkManager (Tail backlog polling) | Inuit uses app-scope coroutines; Hoot needs polling to survive process death |
| min/target SDK | 26 / 37 | Identical to Inuit and the current Hoot template |

---

## 2. Module & Package Layout

Single Gradle module (`:app`), package-rooted by layer — same style as Inuit
(`data/`, `ui/`) with Hoot-specific additions:

```
com.example.hoot/
├── HootApp.kt                    # Application: owns AppGraph
├── MainActivity.kt
├── di/
│   └── AppGraph.kt               # manual DI (mirrors Inuit AppGraph.kt)
├── data/
│   ├── local/
│   │   ├── HootDatabase.kt       # Room DB
│   │   ├── dao/                  # FoodDao, MealDao, NutrientDao, LookupCacheDao, …
│   │   └── entity/               # Room entities (§3)
│   ├── settings/
│   │   └── SettingsStore.kt      # DataStore (mirrors Inuit SettingsStore.kt)
│   ├── remote/
│   │   ├── llm/
│   │   │   ├── Http.kt           # port of Inuit Http.kt
│   │   │   ├── LlmClient.kt      # port of Inuit LlmClient.kt (chat/completions + tools)
│   │   │   ├── McpClient.kt      # port of Inuit McpClient.kt (streamable-http)
│   │   │   └── McpSession.kt     # port of Inuit McpSession.kt
│   │   └── usda/                 # optional direct USDA FoodData Central REST fallback
│   ├── tail/
│   │   ├── TailIntegration.kt    # ContentProvider queries (mirrors Inuit TailIntegration.kt)
│   │   ├── TailModels.kt         # TailHabit, TailMealLog, TailTextEntry, TailEntry
│   │   └── TailSyncWorker.kt     # WorkManager incremental backlog sync
│   ├── nutrition/
│   │   ├── FoodResolver.kt       # lookup-cache → LLM → web-search pipeline (§5)
│   │   ├── NutrientAggregator.kt # ingredient grams → per-nutrient daily totals (§6)
│   │   ├── ScoreEngine.kt        # daily 0-100 score (§6)
│   │   └── Recommender.kt        # deficiency-driven food recommendations
│   └── repo/
│       ├── FoodRepository.kt
│       ├── MealRepository.kt
│       ├── IntakeRepository.kt
│       └── RecommendationRepository.kt
├── domain/                       # pure Kotlin models (no Android imports) — JVM-testable
│   ├── Nutrient.kt               # NutrientId, NutrientDefinition, Tier
│   ├── Food.kt, Ingredient.kt, Meal.kt
│   ├── Intake.kt                 # per-nutrient-per-day records
│   ├── DailyScore.kt
│   └── Recommendation.kt
└── ui/
    ├── theme/                    # existing template theme
    ├── dashboard/                # today: score ring, top gaps, next meal advice
    ├── meals/                    # meal list + detail (mirrors Tail's MealDetail UX)
    ├── stats/                    # graphs: 7d/30d/90d/1y/all, radar, top sources
    ├── recommendations/          # carousel with food images (Coil)
    └── settings/                 # SettingsScreen + sections:
        ├── LlmSettingsSection.kt # base URL / key / model / temperature (Inuit clone)
        ├── McpSettingsSection.kt # MCP JSON editor (Inuit clone)
        ├── TailSettingsSection.kt# Tail app + habit mapping (Inuit clone)
        ├── DietSettingsSection.kt# dietary restrictions
        ├── GoalsSettingsSection.kt# custom nutrient targets
        └── CacheSettingsSection.kt# view/clear LookupCache + Sources
```

Files stay well under 500 lines; the `domain/` package contains no Android
imports so aggregation/scoring logic is plain-JVM unit-testable.

---

## 3. Room Schema

All entities in `data/local/entity/`. IDs are `String` UUIDs (like Tail's
`MealLog.id`) to make sync dedup trivial; times are epoch millis (Tail meal
convention) or `yyyy-MM-dd` strings (Tail day-key convention).

### Foods
```kotlin
@Entity(tableName = "foods")
data class FoodEntity(
    @PrimaryKey val id: String,          // UUID
    val normalizedName: String,          // lowercased, singularized, trimmed — join key
    val displayName: String,
    val category: String?,               // "vegetable", "supplement", …
    val isSupplement: Boolean = false,
    val createdAt: Long
)
```

### Ingredients
Normalized ingredient rows extracted from meal text (one row per ingredient
per meal).
```kotlin
@Entity(tableName = "ingredients")
data class IngredientEntity(
    @PrimaryKey val id: String,
    val mealId: String,                  // FK → meals
    val rawText: String,                 // exactly as written by user/LLM
    val foodId: String?,                 // FK → foods (null until resolved)
    val amount: Double?,                 // numeric quantity
    val unit: String?,                   // "g", "ml", "cup", "tbsp", "piece", …
    val gramsEstimate: Double?           // normalized weight for aggregation
)
```

### Meals / MealItems
```kotlin
@Entity(tableName = "meals")
data class MealEntity(
    @PrimaryKey val id: String,          // Tail entry dedup key (§ TailIntegration)
    val tailHabitName: String,           // e.g. "Food"
    val timestamp: Long,                 // epoch millis
    val day: String,                     // "yyyy-MM-dd" local
    val title: String?,
    val rawText: String,                 // full meal description from Tail
    val source: String                   // "tail" | "manual" | "hoot"
)
```
(`MealItem` is realized by `ingredients` — every ingredient belongs to exactly one meal.)

### Supplements
```kotlin
@Entity(tableName = "supplements")
data class SupplementEntity(
    @PrimaryKey val id: String,
    val label: String,                   // exact Tail "Took Pills" option label, e.g. "Magnesium"
    val description: String?,            // Tail option description (dose/notes) when exposed
    val resolvedFoodId: String?,         // FK → foods
    val doseAmount: Double?,             // parsed from label/description
    val doseUnit: String?,               // "mg", "mcg", "IU", "g"
    val nutrientContributions: String    // JSON: [{nutrientId, amount, unit}]
)
```

### NutrientDefinitions
```kotlin
@Entity(tableName = "nutrient_definitions")
data class NutrientDefinitionEntity(
    @PrimaryKey val id: String,          // "protein", "vitamin_d", "magnesium", …
    val name: String,                    // "Vitamin D"
    val group: String,                   // "macronutrient" | "vitamin" | "mineral" | "other"
    val unit: String,                    // canonical unit: "g" | "mg" | "mcg"
    val rdaValue: Double?,               // adult RDA/AI in canonical unit
    val ulValue: Double?,                // tolerable Upper Limit (null = none)
    val tier: Int,                       // 1 = critical, 2 = important, 3 = nice-to-have
    val deficiencySymptoms: String?,     // markdown
    val excessRisks: String?,
    val foodSources: String?             // markdown list of common sources
)
```
Seeded on first launch from the canonical table in [`NUTRIENTS.md`](NUTRIENTS.md).

### NutrientIntakeLog (per nutrient per day)
```kotlin
@Entity(tableName = "nutrient_intake_log")
data class NutrientIntakeEntity(
    @PrimaryKey val id: String,
    val nutrientId: String,              // FK → nutrient_definitions
    val day: String,                     // "yyyy-MM-dd"
    val amount: Double,                  // canonical unit
    val sourceMealId: String?,           // FK → meals (null for supplement-only rows)
    val sourceSupplementId: String?
)
```
One row per (nutrient, day, contributing source); daily totals are `SUM(amount)`
grouped by (nutrient, day).

### FoodNutrientProfile (resolved nutrition panel)
```kotlin
@Entity(tableName = "food_nutrient_profile")
data class FoodNutrientProfileEntity(
    @PrimaryKey val id: String,
    val foodId: String,                  // FK → foods (unique per food)
    val perAmount: Double,               // e.g. 100
    val perUnit: String,                 // "g" — values are per 100 g unless noted
    val valuesJson: String,              // {"protein": 12.3, "iron_mg": 2.1, …} canonical units
    val confidence: Double,              // 0-1 from LLM/self-report
    val resolutionMethod: String         // "cache" | "llm" | "web_usda" | "manual"
)
```

### LookupCache
```kotlin
@Entity(tableName = "lookup_cache")
data class LookupCacheEntity(
    @PrimaryKey val normalizedKey: String,   // normalized food-name query
    val resolvedFoodId: String?,             // FK → foods
    val profileId: String?,                  // FK → food_nutrient_profile
    val sourceUrlsJson: String,              // ["https://…usda…", …]
    val fetchedAt: Long,                     // epoch millis — TTL decisions
    val hitCount: Int                        // popularity telemetry
)
```

### Sources (citation records)
```kotlin
@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val id: String,
    val lookupKey: String,               // FK → lookup_cache
    val url: String,
    val title: String?,                  // page/document title
    val publisher: String?,              // "USDA FDC", "NIH ODS", …
    val fetchedAt: Long,
    val toolName: String?                // MCP tool that produced it: "web-search-prime"
)
```

### TailAppConfig
```kotlin
@Entity(tableName = "tail_app_config")
data class TailAppConfigEntity(
    @PrimaryKey val id: Int = 1,         // singleton row
    val integrationEnabled: Boolean = false,
    val mealHabitName: String?,          // e.g. "Food"  (meal-type habit)
    val pillsHabitName: String?,         // e.g. "Took Pills" (text-entry habit)
    val lastMealSyncAt: Long?,           // incremental-sync cursor (millis)
    val lastPillsSyncAt: Long?,
    val knownEntryIdsJson: String        // bloom/JSON set for fast dedup (compact window)
)
```

### DietaryProfile
```kotlin
@Entity(tableName = "dietary_profile")
data class DietaryProfileEntity(
    @PrimaryKey val id: Int = 1,
    val dietStyle: String,               // "omnivore" | "vegan" | "vegetarian" | "pescatarian" | "keto" | …
    val allergiesJson: String,           // ["peanuts", "shellfish"]
    val dislikesJson: String,            // foods to never recommend
    val excludeFromScoring: Boolean = false
)
```

### NutrientGoals
```kotlin
@Entity(tableName = "nutrient_goals")
data class NutrientGoalEntity(
    @PrimaryKey val id: String,
    val nutrientId: String,
    val targetValue: Double,             // canonical unit
    val isCustom: Boolean,               // false = RDA default, true = user override
    val priority: Int                    // 1-3; overrides definition tier when set
)
```

### RecommendationLog
```kotlin
@Entity(tableName = "recommendation_log")
data class RecommendationEntity(
    @PrimaryKey val id: String,
    val day: String,                     // issued on
    val nutrientId: String,              // deficiency targeted
    val foodName: String,                // "lentils"
    val reasonText: String,              // shown in carousel
    val imageUrl: String?,               // Coil-loaded
    val sourceIdsJson: String,           // citations backing the claim
    val accepted: Boolean?               // user tapped "ate it" / dismissed (null = open)
)
```

### ScoreSnapshot
```kotlin
@Entity(tableName = "score_snapshot")
data class ScoreSnapshotEntity(
    @PrimaryKey val day: String,         // "yyyy-MM-dd"
    val score: Double,                   // 0-100
    val completeness: Double,            // micronutrient-coverage component
    val deficiencyPenalty: Double,
    val adherence: Double,               // recommendation-following component
    val nutrientsMet: Int,
    val nutrientsTracked: Int
)
```

### SettingsPersist
Settings live in **DataStore Preferences** (mirroring Inuit's `SettingsStore`
key-for-key where applicable), not Room:

```kotlin
data class AppSettings(
    // LLM — keys identical to Inuit ("llm_base_url", "llm_api_key", "llm_model",
    // "llm_temperature", "llm_disable_thinking"). Defaults blank like Inuit;
    // llmConfigured = baseUrl.isNotBlank() && model.isNotBlank().
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Float = 0.7f,
    val disableThinking: Boolean = false,

    // MCP — same JSON shape and same DEFAULT_MCP_JSON seed as Inuit
    // (z.ai web-search-prime + web-reader, streamable-http).
    val mcpJson: String = DEFAULT_MCP_JSON,
    val mcpBudget: Int = 3,              // tool calls per resolution run

    // Hoot-specific
    val syncEnabled: Boolean = true,
    val syncIntervalMinutes: Int = 60,
    val cacheTtlDays: Int = 30,          // LookupCache freshness window
    val webSearchFallback: Boolean = true
)
```

The `DEFAULT_MCP_JSON` seed (identical to Inuit's):

```json
{
  "mcpServers": {
    "web-search-prime": {
      "type": "streamable-http",
      "url": "https://api.z.ai/api/mcp/web_search_prime/mcp",
      "headers": { "Authorization": "Bearer PASTE_ZAI_API_KEY_HERE" }
    },
    "web-reader": {
      "type": "streamable-http",
      "url": "https://api.z.ai/api/mcp/web_reader/mcp",
      "headers": { "Authorization": "Bearer PASTE_ZAI_API_KEY_HERE" }
    }
  }
}
```

---

## 4. Tail Data Acquisition

`data/tail/TailIntegration.kt` mirrors Inuit's integration one-for-one
(same authority, columns, permission), extended to read meal history:

| Capability | Mechanism (current Tail surface) | Hoot wrapper |
|---|---|---|
| Enumerate habits | query `content://com.example.tail.provider/habits` → `habit_id`, `habit_name` | `fetchHabits()` |
| Shared text habits | `…/text_habits` → `habit_name` | `fetchSharedTextHabits()` |
| Recent text entries | `…/text_habits/recent?limit=N` → `habit_name`, `entry_ts`, `entry_text` (≤5/habit, 14-day window, 300 chars) | `fetchRecentTextEntries(limit)` |
| Meal logs / full backlog | **Not yet exposed** — this is the gap [`TAIL_REQUEST.md`](TAIL_REQUEST.md) asks Tail to fill | `fetchMealLogs(fromTs)` (proposed) |
| Permission | signature permission `com.example.tail.permission.TAIL_INTEGRATION` (Hoot must be signed with the same keystore) | declared in Hoot's manifest |

`TailSyncWorker` (WorkManager, periodic + on-demand "Sync now") runs
incremental sync: entries newer than `lastMealSyncAt` / `lastPillsSyncAt`,
deduped by stable entry id, written into `meals` / `supplements` intake rows.

---

## 5. Nutrition Resolution Engine

The heart of Hoot: turning "150 g lentils + spinach + olive oil" into a
per-nutrient vector, with citations, cached.

```mermaid
flowchart TD
    A[Meal text from Tail\nor manual entry] --> B[Normalize ingredient list\nsplit, lowercase, singularize,\nquantity+unit extraction]
    B --> C{LookupCache hit\nfor each ingredient?}
    C -- hit --> F[Use cached profile\n+ sources]
    C -- miss --> D[LLM structured parse\nchat/completions + JSON schema:\nfood → per-100g nutrient panel]
    D --> E{Profile confident?\nconfidence ≥ 0.75}
    E -- yes --> G[Persist: Foods + Profile\n+ LookupCache + Sources]
    E -- no --> H[MCP web-search fallback\nweb-search-prime → web-reader\nUSDA FoodData Central, NIH ODS]
    H --> I[LLM extract panel from\nfetched pages; cite URLs]
    I --> G
    F --> J[Ingredient grams →\nnutrient vector]
    G --> J
    J --> K[NutrientIntakeLog rows\nper nutrient per day]
    K --> L[ScoreEngine → ScoreSnapshot\n+ Recommender]
```

Rules:
- **Cache first.** `normalizedKey` lookups are O(1); `hitCount++` on use.
  Stale entries (older than `cacheTtlDays`) are refreshed in the background.
- **LLM parse** uses the same `LlmClient` + JSON-strict prompt style as
  Inuit's `QuestionGenerator`; response schema:
  `{"food": "...", "per_amount": 100, "per_unit": "g", "values": {"protein_g": …},
  "confidence": 0.0-1.0}`.
- **Web fallback** (budgeted, like Inuit's `mcpBudget`): web-search-prime for
  queries like `lentils cooked USDA FoodData Central per 100g`, web-reader to
  fetch the page, LLM extracts the panel and the citation URLs. Every URL is
  stored as a `SourceEntity`.
- **Supplements**: labels like "Magnesium 400 mg" are parsed directly
  (regex + LLM assist) into `nutrientContributions` — no web lookup needed
  unless the compound is ambiguous (e.g. "magnesium citrate" → elemental mg).

---

## 6. Scoring System

### Daily score (0-100)

```
score = w1·completeness + w2·(1 − deficiencyPenalty) + w3·adherence
        defaults w1 = 0.45, w2 = 0.35, w3 = 0.20
```

1. **Completeness** — for each tracked nutrient *n* with weight `tierWeight(n)`
   (Tier 1 = 3, Tier 2 = 2, Tier 3 = 1):

   ```
   coverage(n) = min(1, intake(n) / target(n))
   completeness = 100 · Σ tierWeight(n)·coverage(n) / Σ tierWeight(n)
   ```

2. **DeficiencyPenalty** — extra deductions for Tier-1 nutrients below 50 % of
   target for *consecutive* days (a 3-day iron deficit hurts more than a
   one-day miss), and soft caps when intake exceeds the UL (`excessPenalty`).

3. **Adherence** — share of `RecommendationLog` entries the user actually ate
   (`accepted == true`) over the trailing 7 days. Dismissed recommendations
   count mildly against; open ones are ignored.

### Weekly trend
`ScoreSnapshot` rows are averaged into a 7-day rolling trend, displayed as a
sparkline next to the daily ring. Week-over-week delta is the headline number
on the dashboard.

---

## 7. Graphs & Statistics

All charts Compose-Canvas-first (Inuit `ui/charts/` style), Vico only for
standard interactive line charts.

| View | Content | Windows |
|---|---|---|
| **Daily score line** | `ScoreSnapshot.score` history with 7-day moving average | 7d / 30d / 90d / 1y / all |
| **Per-nutrient history** | one line per selected nutrient, % of target; dashed UL line when relevant | 7d / 30d / 90d / 1y / all |
| **Radar** | top-N nutrients by tier at current % coverage — instantly shows the "shape" of the diet | today / 7d avg / 30d avg |
| **Top sources** | for each nutrient: ranked list of foods by cumulative contribution (`SELECT sourceMeal→ingredient→food SUM(amount) GROUP BY food`) | 30d / 90d / all |
| **Recommendations carousel** | open/accepted recommendations with Coil food images, reason text and citation chips; swipe to accept/dismiss | latest 10 |

All aggregation runs in `Flow` off Room DAOs (`@MapInfo`-style queries),
so stats screens react live to new Tail syncs.

---

## 8. Settings Plan

`ui/settings/SettingsScreen.kt` — section cards, mirroring Inuit exactly for
the shared sections (same labels, same defaults, same "Test" button):

1. **LLM** — Base URL (placeholder `https://api.example.com/v1`), API key
   (masked + visibility toggle), Model name (placeholder `e.g. glm-4.7, gpt-4o,
   llama-3.1-70b`), temperature slider, `disableThinking` switch ("GLM
   reasoning models: skip internal chains — much faster and cheaper"), Save +
   **Test** buttons (Test = `listModels` reachability check, Inuit-identical).
   Defaults: blank base URL / key / model — exactly Inuit's defaults.
2. **Web tools (MCP)** — JSON editor seeded with Inuit's `DEFAULT_MCP_JSON`
   (z.ai `web-search-prime` + `web-reader`, streamable-http, Bearer-token
   headers), tool-call budget number field (default 3, range 0-20).
3. **Tail app** — Refresh button → habits from the provider; pick the
   **meal habit** (single select, expected name "Food") and the **pills
   habit** (single select, expected "Took Pills"); sync-interval selector;
   "Send backlog" progress indicator.
4. **Dietary restrictions** — diet style picker + allergy/dislike chips.
5. **Goals** — list of tracked nutrients with RDA pre-filled, editable
   custom targets, priority override.
6. **Cache management** — LookupCache row count, oldest/newest fetch dates,
   per-entry viewer (key → food → sources), "Clear cache" (keeps Sources),
   "Clear all".

---

## 9. End-to-End Data Flow

```mermaid
flowchart LR
    subgraph Tail[Tail app]
        T1[(habitsdb + meal_logs\n+ text logs)]
    end
    subgraph Hoot
        P[TailSyncWorker\nContentProvider queries] --> M[(meals / supplements\nRoom)]
        M --> R[FoodResolver]
        R --> C[(LookupCache)]
        R --> L[LLM / MCP web tools]
        L --> S[(Sources)]
        R --> I[(NutrientIntakeLog)]
        I --> SC[ScoreEngine]
        SC --> K[(ScoreSnapshot)]
        SC --> RE[Recommender]
        RE --> RL[(RecommendationLog)]
        subgraph UI[Compose UI — MVVM]
            V1[Dashboard VM] --> K
            V2[Stats VM] --> I
            V2 --> K
            V3[Recommendations VM] --> RL
        end
    end
    T1 -. ContentProvider\nhabit list + entries .-> P
    L -. chat/completions .-> API[LLM API]
    L -. JSON-RPC streamable-http .-> MCP[z.ai web-search-prime / web-reader]
```

---

## 10. Error Handling & Resilience

- **Tail absent / permission denied** → soft-fail like Inuit (`DebugLog.w`,
  amber "Tail app not found" UI state); all local features keep working.
- **LLM unreachable** → resolution falls back to cache-only + a "needs
  nutrition" placeholder state; sync never blocks the UI.
- **MCP failure** → logged, skipped (Inuit `McpSession` behaviour); budget
  preserved for the next run.
- **LLM JSON invalid** → one retry with a stricter prompt, then quarantine
  row in `LookupCache` (`profileId = null`) with negative TTL to avoid
  repeated bad parses.
- **Rate limits** — tool budget per run + per-URL dedup in Sources before
  fetching.

---

## 11. Testing Strategy

- `domain/` pure-JVM unit tests: normalization, aggregation, scoring
  (JUnit, like Tail's JVM-testable pure objects).
- Room DAO tests on Robolectric or instrumented.
- `TailIntegration` mapping tests with fixture cursors.
- LLM/MCP behind interfaces; fakes in unit tests, live "Test" button in
  settings for real-endpoint checks (Inuit pattern).
