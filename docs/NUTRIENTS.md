# Hoot — Nutrient Tracking Plan

> Canonical list of tracked nutrients, targets, tiers and aggregation rules.
> This table seeds the Room table `nutrient_definitions` on first launch.
> Status: design document (pre-implementation). Last revised: 2026-09-20.

> **2026-09-20 — data-correctness fixes.**
> 1. **Panel-key alias map** (`domain/nutrition/NutrientKeys.kt`) is now the
>    single source of truth for nutrient-id ↔ LLM-panel-key mapping; LLM key
>    variants ("fibre", "dietary_fiber", "iodide", "fat", "kcal", …) fold to
>    canonical ids at the parse boundary. Completeness invariants (every seed
>    id mapped, aliases unambiguous) are enforced by `NutrientKeysMappingTest`.
> 2. **Water entered the ledger**: `tail_entries` (kind=water) — Tail water
>    habit AND in-app quick-adds — is aggregated as the "water" nutrient
>    (canonical L) in `IntakeAggregator`; water-only days are covered by
>    recompute; display keeps one decimal ("2.1 L"). See `WaterIntake.kt`.
> 3. Derived nutrients (`electrolyte_ratio`) are excluded from LLM prompts —
>    they are computed views, never panel values.

Reference values are **adult (19-50 y) RDAs / AIs** from the NIH Office of
Dietary Supplements fact sheets and the USDA FoodData Central basis; they are
defaults, always overridable per user in `nutrient_goals`.

Legend:
- **RDA/AI** — default daily target (RDA = Recommended Dietary Allowance, AI = Adequate Intake).
- **UL** — Tolerable Upper Intake Level (from *supplements/fortified foods* for some nutrients; noted where it applies to total intake).
- **Tier** — priority in the scoring engine: 🟥 **Tier 1 critical**, 🟧 **Tier 2 important**, 🟨 **Tier 3 nice-to-have**.

---

## 1. Macronutrients

| Nutrient | RDA/AI (adult) | Unit (canonical) | UL | Tier | Deficiency symptoms | Excess risks | Common food sources |
|---|---|---|---|---|---|---|---|
| **Protein** | 0.8 g/kg body weight (≈ 56 g ♂ / 46 g ♀) | g | none established | 🟥 Tier 1 | Muscle wasting, edema, poor immunity, brittle hair/nails | Kidney strain (pre-existing disease), weight gain | Meat, fish, eggs, dairy, legumes, tofu, seitan |
| **Carbohydrates** | 45-65 % of energy (≈ 130 g minimum) | g | none | 🟨 Tier 3 | Ketosis (usually benign), fatigue, poor exercise performance | Weight gain if refined-heavy | Grains, fruit, legumes, tubers |
| **Fiber** | 38 g ♂ / 25 g ♀ (AI) | g | none (ramp up gradually) | 🟥 Tier 1 | Constipation, gut-dysbiosis, ↑LDL, poor glycemic control | Gas/bloating, mineral-binding at very high doses | Whole grains, legumes, vegetables, fruit, nuts |
| **Sugars (added)** | < 10 % of energy (WHO; < 25 g ideal) | g | 10 % energy (practical cap) | 🟧 Tier 2 (limit-tracker) | — (marker of diet quality) | Dental caries, insulin resistance, triglycerides ↑ | Sweets, soda, baked goods, juice |
| **Total fat** | 20-35 % of energy | g | none | 🟨 Tier 3 | Poor absorption of A/D/E/K, dry skin, hormonal issues | Weight gain, ↑ CVD risk (with sat-fat) | Oils, nuts, avocado, fatty fish, dairy |
| — Saturated fat | < 10 % of energy | g | 10 % energy (practical cap) | 🟧 Tier 2 (limit-tracker) | — | ↑ LDL / CVD risk | Butter, fatty meat, coconut, cheese |
| — Monounsaturated fat | no RDA (beneficial) | g | none | 🟨 Tier 3 | — | — (benign) | Olive oil, avocados, almonds |
| — Polyunsaturated fat | 5-10 % of energy | g | none | 🟨 Tier 3 | Dry skin, poor wound healing | — (benign in food) | Sunflower oil, walnuts, fish |
| — **Omega-3 EPA+DHA** | 250 mg/day combined (AI; 1 g if CVD) | mg | 3 g (supplement form, FDA) | 🟥 Tier 1 | Dry eyes/skin, poor cognition, ↑ inflammation, CVD risk | Bleeding risk > 3 g/d, immune suppression | Fatty fish (salmon, mackerel, sardines), algae oil |
| — Omega-3 ALA | 1.6 g ♂ / 1.1 g ♀ (AI) | g | none | 🟧 Tier 2 | Scaly skin, neurological issues (rare) | — | Flax, chia, walnuts, canola |
| — Omega-6 LA | 17 g ♂ / 12 g ♀ (AI) | g | none | 🟨 Tier 3 | Dermatitis (rare) | Pro-inflammatory excess vs omega-3 | Seed oils, nuts, grains |
| — Trans fat | 0 (as low as possible) | g | < 1 % energy | 🟧 Tier 2 (limit-tracker) | — | ↑ LDL ↓ HDL, CVD | Processed/industrial baked & fried foods |
| **Cholesterol** | no RDA (< 300 mg advisory) | mg | none (dietary) | 🟨 Tier 3 | — | Hyper-responder CVD risk | Eggs, organ meat, shellfish, dairy fat |
| **Water** | 3.7 L ♂ / 2.7 L ♀ total (AI, incl. food) | L | none in food; > 1 L/h drinking risk | 🟥 Tier 1 | Thirst, headache, dark urine, kidney stones, confusion | Hyponatremia (rare, extreme) | Drinks, fruit/veg (~20 %), soups |
| **Calories** | individualized (weight-goal based) | kcal | goal-dependent | 🟥 Tier 1 | Underweight, fatigue, hormonal disruption | Weight gain, metabolic disease | Everything — aggregate of intake |

---

## 2. Vitamins

| Vitamin | RDA/AI (adult) | Unit (canonical) | UL | Tier | Deficiency symptoms | Excess risks | Common food sources |
|---|---|---|---|---|---|---|---|
| **A** (retinol eq.) | 900 µg ♂ / 700 µg ♀ (RAE) | mcg (RAE) | 3000 µg (preformed) | 🟧 Tier 2 | Night blindness, xerophthalmia, poor immunity, keratosis | Liver damage, teratogenicity (preformed A), headache | Liver, eggs, dairy; beta-carotene: carrots, sweet potato, kale |
| **B1** (Thiamin) | 1.2 mg ♂ / 1.1 mg ♀ | mg | none | 🟧 Tier 2 | Beriberi: fatigue, neuropathy, heart failure; Wernicke (alcohol) | — (water-soluble) | Whole grains, pork, legumes, sunflower seeds |
| **B2** (Riboflavin) | 1.3 mg ♂ / 1.1 mg ♀ | mg | none | 🟧 Tier 2 | Cheilosis, glossitis, light sensitivity, anemia (B6/B2 interplay) | — (yellow urine only) | Dairy, eggs, organ meat, almonds, mushrooms |
| **B3** (Niacin) | 16 mg NE ♂ / 14 mg ♀ | mg (NE) | 35 mg (flush/„nicotinic acid“ form) | 🟨 Tier 3 | Pellagra: dermatitis, diarrhea, dementia | Flushing, liver damage (high-dose SR) | Meat, poultry, tuna, peanuts, whole grains |
| **B5** (Pantothenic acid) | 5 mg (AI) | mg | none | 🟨 Tier 3 | Rare: numbness, burning feet, fatigue | — | Almost all foods; organ meat, mushrooms, avocado |
| **B6** (Pyridoxine) | 1.3-1.7 mg | mg | 100 mg (supplement) | 🟥 Tier 1 | Neuropathy, glossitis, depression, microcytic anemia (dopa-decarboxylase) | **Sensory neuropathy** (chronic > 100 mg) | Chickpeas, salmon, potatoes, banana, poultry |
| **B7** (Biotin) | 30 µg (AI) | mcg | none | 🟨 Tier 3 | Hair loss, brittle nails, rash, neurological symptoms (rare) | Interferes with lab assays (troponin!) | Eggs (cooked), liver, nuts, soy, sweet potato |
| **B9** (Folate) | 400 µg DFE | mcg (DFE) | 1000 µg (folic acid form — masks B12 deficit) | 🟥 Tier 1 | Megaloblastic anemia, neural-tube defects (pregnancy), fatigue | Masks B12 deficiency neuropathy | Leafy greens, legumes, asparagus, avocado; fortified grains |
| **B12** (Cobalamin) | 2.4 µg | mcg | none | 🟥 **Tier 1 — TOP priority** (esp. vegans/vegetarians) | Megaloblastic anemia, peripheral neuropathy, cognitive decline (irreversible if late) | — (no known toxicity) | Meat, fish, eggs, dairy; **fortified foods/supplements for vegans** |
| **C** | 90 mg ♂ / 75 mg ♀ | mg | 2000 mg (supplement) | 🟧 Tier 2 | Scurvy (bleeding gums, poor wound healing), fatigue, iron-deficiency co-factor | GI upset, oxalate kidney stones (> 2 g) | Citrus, peppers, broccoli, strawberries, kiwi |
| **D** | 15-20 µg (600-800 IU) | **mcg (canonical)** | 100 µg (4000 IU) | 🟥 **Tier 1 — TOP priority** (low-sun latitudes, winter) | Osteomalacia, bone pain, muscle weakness, immunity ↓, mood ↓ | Hypercalcemia, nephrocalcinosis (> 100 µg/d) | Fatty fish, UV mushrooms, fortified milk; **supplements** |
| **E** | 15 mg (α-tocopherol) | mg | 1000 mg (supplement) | 🟨 Tier 3 | Rare: hemolytic anemia, neuropathy | Bleeding (vit-K antagonism at high dose) | Nuts, seeds, wheat germ, vegetable oils |
| **K** | 120 µg ♂ / 90 µg ♀ (AI) | mcg | none (K1/K2 food form) | 🟧 Tier 2 | Bleeding, bruising; bone-mineral loss (K2 role) | Antagonizes warfarin (consistency matters) | Kale, spinach, broccoli (K1); natto, cheese (K2) |

---

## 3. Minerals

| Mineral | RDA/AI (adult) | Unit (canonical) | UL | Tier | Deficiency symptoms | Excess risks | Common food sources |
|---|---|---|---|---|---|---|---|
| **Calcium** | 1000 mg (1200 mg ♀ > 50) | mg | 2500 mg (2000 mg > 50 y) | 🟥 **Tier 1 — TOP priority** | Osteopenia/osteoporosis, cramps, tetany (severe) | Kidney stones, milk-alkali syndrome, ↓Fe/Zn absorption | Dairy, fortified plant milk, tofu (Ca-set), kale, sardines w/ bones |
| **Phosphorus** | 700 mg | mg | 4000 mg | 🟨 Tier 3 (rarely deficient) | Muscle weakness, osteomalacia (rare; alcoholism) | Vascular calcification (CKD; high additive intake) | Meat, dairy, legumes, processed food (additives) |
| **Magnesium** | 400-420 mg ♂ / 310-320 mg ♀ | mg | 350 mg **supplemental** form (food UL none) | 🟥 **Tier 1 — TOP priority** | Cramps, tremor, arrhythmia, insomnia, anxiety, migraine | Diarrhea (supplemental), hypotension (extreme) | Pumpkin seeds, spinach, legumes, dark chocolate, nuts, whole grains |
| **Sodium** | 1500 mg (AI); < 2300 mg (CDG limit) | mg | 2300 mg | 🟧 Tier 2 (limit-tracker) | Hyponatremia (rare; extreme sweat) | Hypertension, stroke, kidney disease, stomach cancer | Table salt, processed food, bread, cheese, sauces |
| **Potassium** | 3400 mg ♂ / 2600 mg ♀ (AI) | mg | none in food (supplement form only) | 🟥 **Tier 1 — TOP priority** (DASH-blood-pressure) | Weakness, cramps, arrhythmia (severe), ↑ BP | Hyperkalemia risk only w/ CKD/ACE-i | Potatoes, bananas, beans, avocado, tomato, leafy greens |
| **Chloride** | 2300 mg (AI) | mg | 3600 mg | 🟨 Tier 3 | Rare (follows sodium) | Contributes to hypertension (as NaCl) | Salt, seaweed, processed food |
| **Iron** | 8 mg ♂ / 18 mg ♀ (pre-menopause) | mg | 45 mg | 🟥 **Tier 1 — TOP priority** (esp. menstruating ♀, vegans) | Microcytic anemia, fatigue, hair loss, RLS, poor exercise tolerance | GI distress, hemochromatosis (genetic), oxidative stress | Red meat, lentils, spinach, tofu, pumpkin seeds (heme vs non-heme note) |
| **Zinc** | 11 mg ♂ / 8 mg ♀ | mg | 40 mg | 🟥 Tier 1 (vegan phytate-adjust +50 %) | Impaired taste/smell, slow wound healing, hair loss, immunity ↓, low T | ↓ Copper absorption (anemia), HDL ↓ | Oysters, beef, pumpkin seeds, legumes, cheese |
| **Copper** | 900 µg | mcg | 10 000 µg | 🟨 Tier 3 | Anemia (unresponsive to Fe), neuropathy, bone abnormalities | Wilson-disease-like liver damage | Liver, shellfish, cashews, dark chocolate, lentils |
| **Manganese** | 2.3 mg ♂ / 1.8 mg ♀ (AI) | mg | 11 mg | 🟨 Tier 3 | Rare: poor bone health, dermatitis | Neurotoxicity (occupational; supplement abuse) | Whole grains, legumes, tea, nuts, pineapple |
| **Fluoride** | 4 mg ♂ / 3 mg ♀ (AI) | mg | 10 mg | 🟨 Tier 3 | Dental caries susceptibility | Dental/skeletal fluorosis (water > 2 mg/L regions) | Fluoridated water, tea, marine fish |
| **Selenium** | 55 µg | mcg | 400 µg | 🟧 Tier 2 | Keshan cardiomyopathy (regional), immune ↓, thyroid dysfunction (T4→T3) | Selenosis: garlic breath, hair/nail loss, neuropathy (> 400 µg) | Brazil nuts (1-2 nuts = RDA!), tuna, sardines, eggs, sunflower seeds |
| **Iodine** | 150 µg | mcg | 1100 µg | 🟥 Tier 1 (vegan/low-salt priority) | Goiter, hypothyroidism, fatigue, cold intolerance; fetal cretinism | Thyroiditis, hypo- **or** hyper-thyroidism | Iodized salt, seaweed, cod, dairy, eggs |
| **Chromium** | 25-35 µg (AI) | mcg | none | 🟨 Tier 3 | Impaired glucose tolerance (rare, TPN only) | — (poorly absorbed) | Broccoli, grape juice, meat, whole grains, brewer's yeast |
| **Molybdenum** | 45 µg (AI) | mcg | 2000 µg | 🟨 Tier 3 | Extremely rare (TPN): neuro damage | Rare: gout-like symptoms | Legumes, grains, nuts, dairy |

---

## 4. Other Tracked Compounds

| Compound | Target (adult) | Unit (canonical) | UL | Tier | Notes | Common food sources |
|---|---|---|---|---|---|---|
| **Lutein + Zeaxanthin** | 10 mg/day (observational eye-health) | mg | none | 🟨 Tier 3 (opt-in) | Macular-pigment density; AMD/cataract protection signal | Kale, spinach, corn, egg yolk, goji |
| **Choline** | 550 mg ♂ / 425 mg ♀ (AI) | mg | 3500 mg | 🟧 Tier 2 | Fatty liver, muscle damage (rare); fetal brain development | Eggs (best), liver, soy, cod, quinoa |
| **Electrolytes — combined check** | ratio dashboard | — | — | 🟧 Tier 2 | Derived view: Na : K ratio target < 1:1; sweat-loss note for athletes | — |

Phytonutrient tracking beyond lutein/zeaxanthin (polyphenols, flavonoids,
sulforaphane) is a **future opt-in**: no RDAs exist, so they will be scored as
"diversity bonuses", never penalties.

---

## 5. Priority Summary (scoring weights)

**🟥 Tier 1 — critical (weight 3):** protein, fiber, water, calories,
omega-3 (EPA+DHA), vitamin B6, B9, **B12**, **D**, calcium, magnesium,
potassium, iron, zinc, iodine.

> Vegan/vegetarian profile auto-promotes: B12, iron, zinc, iodine,
> omega-3 (ALA→EPA conversion is poor) — implemented as tier overrides in
> `nutrient_goals` when `diet_style` != omnivore.

**🟧 Tier 2 — important (weight 2):** vitamin A, B1, B2, C, K, choline,
selenium, sodium-limit, added-sugar-limit, saturated-fat-limit, trans-fat-limit.

**🟨 Tier 3 — nice-to-have (weight 1):** carbs, total fat, MUFA, PUFA,
ALA, omega-6, cholesterol, B3, B5, B7, E, phosphorus, chloride, copper,
manganese, fluoride, chromium, molybdenum, lutein+zeaxanthin.

---

## 6. Unit Normalization

Canonical storage units: **g** (macros, fiber, water-as-L→g? **no** — water
stores as L, calories as kcal; everything else as below).

| Incoming unit | Canonical | Rule |
|---|---|---|
| g | g | identity |
| mg | mcg or mg | keep mg; µg-nutrients: ÷1000 |
| µg / mcg | mcg | identity |
| IU (vitamin A) | mcg RAE | retinol: IU ÷ 3.33; β-carotene supplement: IU ÷ 1.67 (food β-carotene: µg ÷ 12 → RAE) |
| IU (vitamin D) | mcg | ÷ 40 |
| IU (vitamin E) | mg α-tocopherol | ÷ 1.49 (natural), ÷ 2.22 (synthetic dl-) |
| NE (niacin equivalents) | mg NE | identity; 60 mg tryptophan ≈ 1 mg NE when LLM reports tryptophan |
| DFE (folate) | mcg DFE | food folate ×1; folic acid ×1.7 (fortified), ×1.0 with food |
| kcal | kcal | identity (calories row) |
| L / ml | L | ml ÷ 1000 |

All conversions live in `domain/Nutrient.kt` as pure functions with exhaustive
unit tests — LLM/web sources report in **whatever unit the source uses**, the
aggregator always converts to canonical before writing `NutrientIntakeLog`.

---

## 7. Daily Aggregation Logic

```
for each day D, nutrient N:
    intake(D, N) = Σ over meals M eaten on D:
                       Σ over ingredients I in M:
                           gramsEstimate(I) / profile.perAmount
                           × convertToCanonical(profile.valuesJson[N])
                 + Σ over supplements S taken on D:
                       convertToCanonical(contribution S→N)
```

Rules and edge cases:

1. **Grams estimation fallback.** When the user gave no quantity ("spinach"),
   the resolver stores a **portion default** (e.g. spinach side = 80 g, apple =
   180 g) with `confidence` penalty ×0.8 on that ingredient's contribution.
2. **Meal-day assignment** uses the meal's local `yyyy-MM-dd` (Tail's day-key
   convention), not UTC.
3. **Supplements** contribute exactly their `nutrientContributions`; "Magnesium
   400 mg" adds 400 mg magnesium to the day without food-lookup round-trips.
4. **Double-counting guard.** A supplement resolved to a `FoodEntity` never
   enters via the meal path (supplements are separate `sourceSupplementId`
   rows; the aggregator sums disjoint sources only).
5. **Rounding & precision.** Store full `Double`; round only at render
   (1 decimal for mg/mcg nutrients, whole for g/kcal).
6. **Partial data.** Days with unresolved ingredients contribute
   `× coverage` weights; the score row records `nutrientsTracked` so the UI
   can show "estimated" rather than silently low-balling.
7. **Negative / limit-tracker nutrients** (sodium, added sugar, sat-fat,
   trans-fat): aggregated identically but scored against a **cap**, not a
   target — `coverage = 1` while under cap, decaying to 0 at 1.5× cap.
