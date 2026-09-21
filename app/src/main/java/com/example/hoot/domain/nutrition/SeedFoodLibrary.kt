package com.example.hoot.domain.nutrition

/**
 * Bundled seed LUT — deterministic, zero-network ingredient → per-100 g
 * nutrition panels for ~65 commonly logged whole foods (audit 2026-09-21,
 * docs/LLM_AUDIT.md §3–§5). Values are USDA SR Legacy-derived, transcribed
 * into Hoot's canonical nutrient ids/units (`data/local/NutrientSeed.kt`).
 *
 * Resolution order becomes: LookupCache → **SEED** → LLM → MCP-web. A seed
 * hit costs ZERO LLM calls, is byte-identical on every resolution, works
 * with no LLM configured, and lands in the normal LookupCache through the
 * resolver — so the cache is pre-warmed with database-grade values instead
 * of model memory.
 *
 * Coverage policy: whole foods only. Branded/composite/regional items stay
 * OUT of the LUT on purpose — those are exactly where the LLM/web tiers add
 * value over a table. Units are the canonical ones per nutrient (kcal for
 * calories, g/mg/mcg as seeded); vitamin A is RAE µg, vitamin D µg.
 *
 * Keys MUST be [FoodNormalizer]-stable: the unit test asserts
 * `FoodNormalizer.normalize(key) == key` for every primary entry, so future
 * edits cannot silently orphan a row. [ALIASES] cover common phrase
 * variants the normalizer does not fold; every alias must point at an
 * existing primary key (also asserted).
 */
object SeedFoodLibrary {

    /** One bundled food: full per-100 g panel in canonical units. */
    data class SeedFood(
        val key: String,
        val displayName: String,
        val category: String,
        val typicalServingGrams: Double,
        val per100: Map<String, Double>
    )

    private fun f(
        key: String, name: String, category: String, serving: Double,
        vararg v: Pair<String, Double>
    ) = SeedFood(key, name, category, serving, v.toMap())

    private val RAW: List<SeedFood> = listOf(
        // ---- Poultry / meat --------------------------------------------------
        f("chicken breast", "Chicken breast", "poultry", 150.0,
            "calories" to 165.0, "protein" to 31.0, "total_fat" to 3.6, "saturated_fat" to 1.0,
            "cholesterol" to 85.0, "sodium" to 74.0, "potassium" to 256.0, "phosphorus" to 228.0,
            "selenium" to 27.6, "zinc" to 1.0, "vitamin_b6" to 0.6, "vitamin_b3" to 13.7,
            "choline" to 85.0),
        f("turkey", "Turkey", "poultry", 150.0,
            "calories" to 135.0, "protein" to 29.0, "total_fat" to 1.7, "saturated_fat" to 0.5,
            "cholesterol" to 68.0, "sodium" to 68.0, "potassium" to 282.0, "selenium" to 29.0,
            "zinc" to 1.7, "vitamin_b3" to 11.2, "vitamin_b6" to 0.7),
        f("beef", "Beef", "meat", 150.0,
            "calories" to 250.0, "protein" to 26.0, "total_fat" to 15.0, "saturated_fat" to 5.9,
            "cholesterol" to 90.0, "sodium" to 66.0, "potassium" to 315.0, "phosphorus" to 200.0,
            "iron" to 2.6, "zinc" to 6.3, "vitamin_b12" to 2.6, "selenium" to 18.0,
            "vitamin_b2" to 0.2, "vitamin_b3" to 4.9),
        f("pork", "Pork", "meat", 150.0,
            "calories" to 242.0, "protein" to 27.0, "total_fat" to 14.0, "saturated_fat" to 5.0,
            "cholesterol" to 80.0, "potassium" to 420.0, "phosphorus" to 200.0,
            "vitamin_b1" to 0.9, "zinc" to 2.2, "vitamin_b12" to 0.7, "selenium" to 38.0,
            "vitamin_b3" to 8.0),
        f("bacon", "Bacon", "meat", 30.0,
            "calories" to 541.0, "protein" to 37.0, "total_fat" to 42.0, "saturated_fat" to 14.0,
            "cholesterol" to 110.0, "sodium" to 1717.0, "phosphorus" to 150.0, "selenium" to 30.0,
            "vitamin_b1" to 0.4, "vitamin_b3" to 10.0),

        // ---- Fish / seafood ---------------------------------------------------
        f("salmon", "Salmon", "fish", 150.0,
            "calories" to 208.0, "protein" to 20.0, "total_fat" to 13.0, "saturated_fat" to 3.1,
            "cholesterol" to 55.0, "sodium" to 59.0, "potassium" to 363.0, "phosphorus" to 220.0,
            "omega3_epa_dha" to 2100.0, "vitamin_d" to 11.0, "vitamin_b12" to 3.2,
            "selenium" to 36.0, "vitamin_b6" to 0.6),
        f("tuna", "Tuna", "fish", 150.0,
            "calories" to 116.0, "protein" to 26.0, "total_fat" to 0.8, "saturated_fat" to 0.2,
            "cholesterol" to 30.0, "sodium" to 247.0, "potassium" to 237.0, "phosphorus" to 140.0,
            "omega3_epa_dha" to 240.0, "vitamin_b12" to 2.5, "selenium" to 80.0,
            "vitamin_b3" to 11.0),
        f("shrimp", "Shrimp", "seafood", 150.0,
            "calories" to 99.0, "protein" to 24.0, "total_fat" to 0.3, "saturated_fat" to 0.1,
            "cholesterol" to 189.0, "sodium" to 111.0, "potassium" to 259.0, "phosphorus" to 205.0,
            "iodine" to 35.0, "selenium" to 38.0, "calcium" to 70.0, "vitamin_b12" to 1.1),
        f("cod", "Cod", "fish", 150.0,
            "calories" to 105.0, "protein" to 23.0, "total_fat" to 0.9, "saturated_fat" to 0.1,
            "cholesterol" to 55.0, "sodium" to 78.0, "potassium" to 268.0, "phosphorus" to 203.0,
            "iodine" to 110.0, "selenium" to 33.0, "vitamin_b12" to 1.1),
        f("sardine", "Sardines", "fish", 90.0,
            "calories" to 208.0, "protein" to 25.0, "total_fat" to 11.5, "saturated_fat" to 1.9,
            "cholesterol" to 142.0, "sodium" to 505.0, "calcium" to 382.0, "iron" to 2.9,
            "phosphorus" to 400.0, "omega3_epa_dha" to 980.0, "vitamin_d" to 4.8,
            "vitamin_b12" to 8.9, "selenium" to 53.0),
        f("tilapia", "Tilapia", "fish", 150.0,
            "calories" to 129.0, "protein" to 26.0, "total_fat" to 2.7, "saturated_fat" to 0.9,
            "cholesterol" to 57.0, "sodium" to 56.0, "potassium" to 302.0, "phosphorus" to 170.0,
            "omega3_epa_dha" to 130.0, "selenium" to 54.0, "vitamin_b12" to 1.9),

        // ---- Eggs -------------------------------------------------------------
        f("egg", "Egg", "egg", 50.0,
            "calories" to 155.0, "protein" to 13.0, "total_fat" to 11.0, "saturated_fat" to 3.3,
            "cholesterol" to 373.0, "sodium" to 124.0, "potassium" to 126.0, "iron" to 1.2,
            "zinc" to 1.3, "selenium" to 30.0, "vitamin_a" to 74.0, "vitamin_d" to 2.2,
            "vitamin_b12" to 1.1, "vitamin_b9" to 44.0, "vitamin_b7" to 20.0,
            "vitamin_b2" to 0.5, "vitamin_b5" to 1.5, "choline" to 251.0, "iodine" to 24.0),
        f("egg white", "Egg white", "egg", 33.0,
            "calories" to 52.0, "protein" to 11.0, "total_fat" to 0.2, "sodium" to 166.0,
            "potassium" to 163.0, "selenium" to 20.0, "vitamin_b2" to 0.44),

        // ---- Dairy ------------------------------------------------------------
        f("milk", "Milk", "dairy", 240.0,
            "calories" to 61.0, "protein" to 3.2, "total_fat" to 3.3, "saturated_fat" to 1.9,
            "cholesterol" to 10.0, "sodium" to 43.0, "potassium" to 143.0, "calcium" to 113.0,
            "phosphorus" to 91.0, "vitamin_a" to 46.0, "vitamin_d" to 1.3, "vitamin_b12" to 0.5,
            "vitamin_b2" to 0.17, "iodine" to 15.0),
        f("yogurt", "Yogurt", "dairy", 170.0,
            "calories" to 61.0, "protein" to 3.5, "total_fat" to 3.3, "saturated_fat" to 2.1,
            "cholesterol" to 13.0, "sodium" to 46.0, "potassium" to 155.0, "calcium" to 121.0,
            "phosphorus" to 95.0, "vitamin_a" to 27.0, "vitamin_b12" to 0.4,
            "vitamin_b2" to 0.23),
        f("greek yogurt", "Greek yogurt", "dairy", 170.0,
            "calories" to 59.0, "protein" to 10.3, "total_fat" to 0.4, "saturated_fat" to 0.1,
            "sodium" to 36.0, "potassium" to 141.0, "calcium" to 110.0, "phosphorus" to 135.0,
            "vitamin_b12" to 0.75, "vitamin_b2" to 0.23),
        f("cheddar", "Cheddar cheese", "dairy", 30.0,
            "calories" to 403.0, "protein" to 25.0, "total_fat" to 33.0, "saturated_fat" to 19.0,
            "cholesterol" to 105.0, "sodium" to 653.0, "calcium" to 721.0, "phosphorus" to 512.0,
            "zinc" to 3.1, "vitamin_a" to 265.0, "vitamin_b12" to 1.1, "vitamin_b2" to 0.43),
        f("mozzarella", "Mozzarella", "dairy", 30.0,
            "calories" to 300.0, "protein" to 22.0, "total_fat" to 22.0, "saturated_fat" to 13.0,
            "cholesterol" to 79.0, "sodium" to 627.0, "calcium" to 505.0, "phosphorus" to 354.0,
            "zinc" to 2.9, "vitamin_a" to 179.0, "vitamin_b2" to 0.23),
        f("cottage cheese", "Cottage cheese", "dairy", 200.0,
            "calories" to 98.0, "protein" to 11.0, "total_fat" to 4.3, "saturated_fat" to 1.7,
            "sodium" to 364.0, "calcium" to 91.0, "phosphorus" to 165.0, "selenium" to 15.0,
            "vitamin_b12" to 0.7),

        // ---- Grains / starches --------------------------------------------------
        f("rice", "Rice", "grain", 160.0,
            "calories" to 130.0, "protein" to 2.7, "carbohydrates" to 28.0, "fiber" to 0.4,
            "iron" to 1.5, "manganese" to 0.4, "selenium" to 7.5, "vitamin_b9" to 58.0,
            "vitamin_b1" to 0.16),
        f("brown rice", "Brown rice", "grain", 160.0,
            "calories" to 123.0, "protein" to 2.7, "carbohydrates" to 26.0, "fiber" to 1.6,
            "magnesium" to 39.0, "manganese" to 0.9, "selenium" to 5.8, "potassium" to 86.0,
            "vitamin_b3" to 2.6),
        f("oat", "Oats", "grain", 40.0,
            "calories" to 389.0, "protein" to 16.9, "total_fat" to 6.9, "saturated_fat" to 1.2,
            "carbohydrates" to 66.0, "fiber" to 10.6, "magnesium" to 177.0, "iron" to 4.7,
            "zinc" to 4.0, "phosphorus" to 523.0, "vitamin_b1" to 0.76, "manganese" to 4.9,
            "selenium" to 28.0),
        f("oatmeal", "Oatmeal", "grain", 240.0,
            "calories" to 71.0, "protein" to 2.5, "total_fat" to 1.5, "saturated_fat" to 0.3,
            "carbohydrates" to 12.0, "fiber" to 1.7, "magnesium" to 26.0, "iron" to 0.9,
            "phosphorus" to 52.0, "manganese" to 0.6),
        f("bread", "Bread", "grain", 30.0,
            "calories" to 254.0, "protein" to 13.0, "total_fat" to 3.5, "saturated_fat" to 0.9,
            "carbohydrates" to 43.0, "fiber" to 6.0, "sodium" to 450.0, "iron" to 2.5,
            "magnesium" to 76.0, "selenium" to 25.0, "manganese" to 1.2, "vitamin_b9" to 40.0),
        f("pasta", "Pasta", "grain", 140.0,
            "calories" to 158.0, "protein" to 5.8, "carbohydrates" to 31.0, "fiber" to 1.8,
            "iron" to 1.3, "manganese" to 0.3, "selenium" to 26.0, "phosphorus" to 58.0,
            "vitamin_b9" to 82.0),
        f("quinoa", "Quinoa", "grain", 160.0,
            "calories" to 120.0, "protein" to 4.4, "total_fat" to 1.9, "saturated_fat" to 0.2,
            "carbohydrates" to 21.0, "fiber" to 2.8, "magnesium" to 64.0, "iron" to 1.5,
            "phosphorus" to 140.0, "zinc" to 1.1, "manganese" to 0.6, "vitamin_b9" to 42.0),

        // ---- Vegetables ----------------------------------------------------------
        f("potato", "Potato", "vegetable", 173.0,
            "calories" to 93.0, "protein" to 2.5, "carbohydrates" to 21.0, "fiber" to 2.2,
            "potassium" to 535.0, "vitamin_c" to 9.6, "vitamin_b6" to 0.3, "iron" to 1.1,
            "magnesium" to 28.0),
        f("sweet potato", "Sweet potato", "vegetable", 150.0,
            "calories" to 90.0, "protein" to 2.0, "carbohydrates" to 21.0, "fiber" to 3.3,
            "vitamin_a" to 961.0, "vitamin_c" to 19.6, "potassium" to 475.0, "manganese" to 0.5,
            "vitamin_b6" to 0.29, "magnesium" to 27.0),
        f("zucchini", "Zucchini", "vegetable", 120.0,
            "calories" to 17.0, "protein" to 1.2, "carbohydrates" to 3.1, "fiber" to 1.0,
            "vitamin_c" to 17.9, "potassium" to 261.0, "vitamin_b9" to 24.0,
            "vitamin_b6" to 0.16),
        f("eggplant", "Eggplant", "vegetable", 120.0,
            "calories" to 25.0, "protein" to 1.0, "carbohydrates" to 6.0, "fiber" to 2.5,
            "potassium" to 229.0, "vitamin_b9" to 22.0, "manganese" to 0.23),
        f("spinach", "Spinach", "vegetable", 80.0,
            "calories" to 23.0, "protein" to 2.9, "carbohydrates" to 3.6, "fiber" to 2.2,
            "vitamin_a" to 469.0, "vitamin_k" to 483.0, "vitamin_c" to 28.0,
            "vitamin_b9" to 194.0, "vitamin_e" to 2.0, "iron" to 2.7, "calcium" to 99.0,
            "magnesium" to 79.0, "potassium" to 558.0, "manganese" to 0.9,
            "lutein_zeaxanthin" to 12.2),
        f("kale", "Kale", "vegetable", 80.0,
            "calories" to 43.0, "protein" to 3.3, "carbohydrates" to 8.8, "fiber" to 3.6,
            "vitamin_a" to 241.0, "vitamin_k" to 390.0, "vitamin_c" to 93.0,
            "vitamin_b9" to 62.0, "calcium" to 254.0, "potassium" to 348.0, "iron" to 1.6,
            "magnesium" to 34.0, "manganese" to 0.66, "lutein_zeaxanthin" to 18.2),
        f("broccoli", "Broccoli", "vegetable", 150.0,
            "calories" to 34.0, "protein" to 2.8, "carbohydrates" to 6.6, "fiber" to 2.6,
            "vitamin_c" to 89.2, "vitamin_k" to 101.6, "vitamin_b9" to 63.0,
            "potassium" to 316.0, "iron" to 0.7, "magnesium" to 21.0, "chromium" to 15.0),
        f("carrot", "Carrot", "vegetable", 80.0,
            "calories" to 41.0, "carbohydrates" to 9.6, "fiber" to 2.8, "vitamin_a" to 835.0,
            "vitamin_k" to 13.2, "potassium" to 320.0, "vitamin_c" to 5.9,
            "manganese" to 0.14),
        f("bell pepper", "Bell pepper", "vegetable", 150.0,
            "calories" to 31.0, "carbohydrates" to 6.0, "fiber" to 2.1, "vitamin_c" to 127.7,
            "vitamin_a" to 157.0, "vitamin_e" to 1.6, "vitamin_b6" to 0.29,
            "vitamin_b9" to 46.0, "potassium" to 211.0),
        f("tomato", "Tomato", "vegetable", 123.0,
            "calories" to 18.0, "protein" to 0.9, "carbohydrates" to 3.9, "fiber" to 1.2,
            "vitamin_c" to 13.7, "vitamin_a" to 42.0, "vitamin_k" to 7.9,
            "potassium" to 237.0, "vitamin_b9" to 15.0, "manganese" to 0.15),
        f("cucumber", "Cucumber", "vegetable", 100.0,
            "calories" to 15.0, "carbohydrates" to 3.6, "fiber" to 0.5, "vitamin_k" to 16.4,
            "potassium" to 147.0, "vitamin_c" to 2.8),
        f("onion", "Onion", "vegetable", 110.0,
            "calories" to 40.0, "carbohydrates" to 9.3, "fiber" to 1.7, "vitamin_c" to 7.4,
            "vitamin_b6" to 0.12, "potassium" to 146.0),
        f("mushroom", "Mushroom", "vegetable", 70.0,
            "calories" to 22.0, "protein" to 3.1, "carbohydrates" to 3.3, "fiber" to 1.0,
            "vitamin_b2" to 0.4, "vitamin_b3" to 3.6, "vitamin_b5" to 1.5, "copper" to 0.32,
            "selenium" to 9.3, "potassium" to 318.0, "vitamin_d" to 0.2),
        f("lettuce", "Lettuce", "vegetable", 85.0,
            "calories" to 17.0, "carbohydrates" to 3.3, "fiber" to 1.2, "vitamin_a" to 436.0,
            "vitamin_k" to 126.0, "vitamin_b9" to 38.0, "potassium" to 247.0, "iron" to 1.0),

        // ---- Fruit ---------------------------------------------------------------
        f("banana", "Banana", "fruit", 118.0,
            "calories" to 89.0, "protein" to 1.1, "carbohydrates" to 23.0, "fiber" to 2.6,
            "potassium" to 358.0, "vitamin_c" to 8.7, "vitamin_b6" to 0.37,
            "magnesium" to 27.0, "manganese" to 0.27),
        f("apple", "Apple", "fruit", 180.0,
            "calories" to 52.0, "carbohydrates" to 14.0, "fiber" to 2.4, "vitamin_c" to 4.6,
            "potassium" to 107.0),
        f("orange", "Orange", "fruit", 131.0,
            "calories" to 47.0, "carbohydrates" to 12.0, "fiber" to 2.4, "vitamin_c" to 53.2,
            "vitamin_b9" to 30.0, "potassium" to 181.0, "calcium" to 40.0),
        f("strawberry", "Strawberries", "fruit", 150.0,
            "calories" to 32.0, "carbohydrates" to 7.7, "fiber" to 2.0, "vitamin_c" to 58.8,
            "manganese" to 0.39, "vitamin_b9" to 24.0, "potassium" to 153.0),
        f("blueberry", "Blueberries", "fruit", 148.0,
            "calories" to 57.0, "carbohydrates" to 14.5, "fiber" to 2.4, "vitamin_c" to 9.7,
            "vitamin_k" to 19.3, "manganese" to 0.34),
        f("raspberry", "Raspberries", "fruit", 150.0,
            "calories" to 52.0, "carbohydrates" to 11.9, "fiber" to 6.5, "vitamin_c" to 26.2,
            "manganese" to 0.67, "vitamin_b9" to 21.0, "potassium" to 151.0),
        f("grape", "Grapes", "fruit", 151.0,
            "calories" to 69.0, "carbohydrates" to 18.0, "fiber" to 0.9, "vitamin_c" to 3.7,
            "vitamin_k" to 14.6, "potassium" to 191.0),
        f("watermelon", "Watermelon", "fruit", 280.0,
            "calories" to 30.0, "carbohydrates" to 7.6, "fiber" to 0.4, "vitamin_a" to 28.0,
            "vitamin_c" to 8.1, "potassium" to 112.0),
        f("mango", "Mango", "fruit", 165.0,
            "calories" to 60.0, "carbohydrates" to 15.0, "fiber" to 1.6, "vitamin_a" to 54.0,
            "vitamin_c" to 36.4, "vitamin_b9" to 43.0, "potassium" to 168.0),
        f("cantaloupe", "Cantaloupe", "fruit", 160.0,
            "calories" to 34.0, "carbohydrates" to 8.2, "fiber" to 0.9, "vitamin_a" to 169.0,
            "vitamin_c" to 36.7, "vitamin_b9" to 21.0, "potassium" to 197.0),
        f("avocado", "Avocado", "fruit", 150.0,
            "calories" to 160.0, "protein" to 2.0, "total_fat" to 14.7, "saturated_fat" to 2.1,
            "monounsaturated_fat" to 9.8, "polyunsaturated_fat" to 1.8, "fiber" to 6.7,
            "potassium" to 485.0, "vitamin_b9" to 81.0, "vitamin_k" to 21.0,
            "vitamin_e" to 2.1, "vitamin_c" to 10.0, "magnesium" to 29.0, "copper" to 0.19),

        // ---- Legumes --------------------------------------------------------------
        f("lentil", "Lentils", "legume", 150.0,
            "calories" to 116.0, "protein" to 9.0, "carbohydrates" to 20.0, "fiber" to 7.9,
            "vitamin_b9" to 181.0, "iron" to 3.3, "manganese" to 0.5, "phosphorus" to 180.0,
            "potassium" to 369.0, "zinc" to 1.3, "vitamin_b1" to 0.17, "copper" to 0.27),
        f("chickpea", "Chickpeas", "legume", 130.0,
            "calories" to 164.0, "protein" to 8.9, "total_fat" to 2.6, "saturated_fat" to 0.3,
            "carbohydrates" to 27.0, "fiber" to 7.6, "vitamin_b9" to 172.0, "iron" to 2.9,
            "magnesium" to 48.0, "manganese" to 1.0, "zinc" to 1.5, "vitamin_b6" to 0.14,
            "copper" to 0.45),
        f("black bean", "Black beans", "legume", 130.0,
            "calories" to 132.0, "protein" to 8.9, "carbohydrates" to 24.0, "fiber" to 8.7,
            "vitamin_b9" to 149.0, "magnesium" to 70.0, "iron" to 2.1, "potassium" to 355.0,
            "vitamin_b1" to 0.24),
        f("bean", "Beans", "legume", 130.0,
            "calories" to 127.0, "protein" to 8.7, "carbohydrates" to 22.8, "fiber" to 6.4,
            "vitamin_b9" to 130.0, "iron" to 2.2, "magnesium" to 45.0, "potassium" to 405.0),
        f("tofu", "Tofu", "legume", 120.0,
            "calories" to 144.0, "protein" to 17.0, "total_fat" to 8.7, "saturated_fat" to 1.3,
            "calcium" to 683.0, "iron" to 2.7, "magnesium" to 58.0, "manganese" to 1.2,
            "copper" to 0.4, "selenium" to 17.4, "zinc" to 1.6),

        // ---- Nuts / seeds -----------------------------------------------------------
        f("peanut", "Peanuts", "nut", 28.0,
            "calories" to 567.0, "protein" to 25.8, "total_fat" to 49.0, "saturated_fat" to 6.3,
            "monounsaturated_fat" to 24.4, "fiber" to 8.5, "vitamin_b3" to 12.1,
            "vitamin_b9" to 240.0, "vitamin_e" to 8.3, "vitamin_b1" to 0.64,
            "magnesium" to 168.0, "phosphorus" to 366.0, "zinc" to 3.3, "copper" to 1.1),
        f("peanut butter", "Peanut butter", "nut", 32.0,
            "calories" to 588.0, "protein" to 25.0, "total_fat" to 50.0, "saturated_fat" to 10.0,
            "fiber" to 6.0, "vitamin_b3" to 13.0, "vitamin_e" to 9.1, "vitamin_b9" to 87.0,
            "magnesium" to 168.0, "zinc" to 2.9, "copper" to 0.42),
        f("almond", "Almonds", "nut", 28.0,
            "calories" to 579.0, "protein" to 21.2, "total_fat" to 49.9, "saturated_fat" to 3.8,
            "monounsaturated_fat" to 31.6, "fiber" to 12.5, "vitamin_e" to 25.6,
            "vitamin_b2" to 1.1, "magnesium" to 270.0, "calcium" to 269.0,
            "phosphorus" to 481.0, "manganese" to 2.3, "copper" to 1.0),
        f("walnut", "Walnuts", "nut", 28.0,
            "calories" to 654.0, "protein" to 15.2, "total_fat" to 65.0, "saturated_fat" to 6.1,
            "polyunsaturated_fat" to 42.7, "omega3_ala" to 9.1, "fiber" to 6.7,
            "vitamin_b9" to 98.0, "vitamin_b6" to 0.54, "copper" to 1.6, "manganese" to 3.4,
            "magnesium" to 158.0),
        f("cashew", "Cashews", "nut", 28.0,
            "calories" to 553.0, "protein" to 18.0, "total_fat" to 44.0, "saturated_fat" to 7.8,
            "monounsaturated_fat" to 23.5, "fiber" to 3.3, "copper" to 2.2,
            "magnesium" to 292.0, "zinc" to 5.8, "iron" to 6.7, "manganese" to 1.7,
            "vitamin_k" to 34.0),
        f("pumpkin seed", "Pumpkin seeds", "seed", 28.0,
            "calories" to 559.0, "protein" to 30.0, "total_fat" to 49.0, "saturated_fat" to 8.7,
            "fiber" to 6.0, "iron" to 8.8, "magnesium" to 592.0, "zinc" to 7.8,
            "copper" to 1.3, "manganese" to 2.5, "phosphorus" to 1233.0),
        f("sunflower seed", "Sunflower seeds", "seed", 28.0,
            "calories" to 584.0, "protein" to 20.8, "total_fat" to 51.0, "saturated_fat" to 4.5,
            "fiber" to 8.6, "vitamin_e" to 35.2, "vitamin_b6" to 1.35, "vitamin_b9" to 227.0,
            "selenium" to 53.0, "copper" to 1.8, "magnesium" to 325.0),
        f("flaxseed", "Flaxseed", "seed", 10.0,
            "calories" to 534.0, "protein" to 18.3, "total_fat" to 42.0, "saturated_fat" to 3.7,
            "omega3_ala" to 22.8, "fiber" to 27.3, "vitamin_b1" to 1.64,
            "magnesium" to 392.0, "manganese" to 2.5, "copper" to 1.2),
        f("chia seed", "Chia seeds", "seed", 15.0,
            "calories" to 486.0, "protein" to 16.5, "total_fat" to 30.7, "saturated_fat" to 3.3,
            "omega3_ala" to 17.8, "fiber" to 34.4, "calcium" to 631.0, "iron" to 7.7,
            "magnesium" to 335.0, "zinc" to 4.6),

        // ---- Fats / sweets / drinks ---------------------------------------------------
        f("olive oil", "Olive oil", "fat", 14.0,
            "calories" to 884.0, "total_fat" to 100.0, "saturated_fat" to 13.8,
            "monounsaturated_fat" to 73.0, "polyunsaturated_fat" to 10.5,
            "vitamin_e" to 14.4, "vitamin_k" to 60.2),
        f("butter", "Butter", "fat", 14.0,
            "calories" to 717.0, "total_fat" to 81.0, "saturated_fat" to 51.0,
            "cholesterol" to 215.0, "vitamin_a" to 684.0, "vitamin_d" to 1.5,
            "vitamin_e" to 2.3),
        f("dark chocolate", "Dark chocolate", "sweet", 25.0,
            "calories" to 598.0, "protein" to 7.8, "total_fat" to 43.0, "saturated_fat" to 24.0,
            "fiber" to 11.0, "iron" to 11.9, "magnesium" to 228.0, "copper" to 1.8,
            "manganese" to 1.9),
        f("honey", "Honey", "sweet", 21.0,
            "calories" to 304.0, "carbohydrates" to 82.4, "added_sugar" to 82.4),
        f("sugar", "Sugar", "sweet", 4.0,
            "calories" to 387.0, "carbohydrates" to 100.0, "added_sugar" to 100.0),
        f("coffee", "Coffee", "drink", 240.0,
            "calories" to 1.0, "potassium" to 49.0, "magnesium" to 3.0,
            "vitamin_b2" to 0.08)
    )

    /**
     * Common phrase variants the [FoodNormalizer] synonym table does not fold.
     * Every target must be a primary key (asserted by tests). Note the
     * normalizer's descriptor stripping means "whole milk" → "milk" and
     * "fried egg" → "egg" never even reach this table.
     */
    private val ALIASES: Map<String, String> = mapOf(
        "chicken" to "chicken breast",
        "ground beef" to "beef", "steak" to "beef", "hamburger" to "beef",
        "ground turkey" to "turkey", "pork chop" to "pork", "porkchop" to "pork",
        "ham" to "pork",
        "scrambled egg" to "egg", "omelette" to "egg", "omelet" to "egg",
        "cheese" to "cheddar", "cheddar cheese" to "cheddar",
        "white rice" to "rice", "jasmine rice" to "rice", "basmati rice" to "rice",
        "spaghetti" to "pasta", "macaroni" to "pasta", "penne" to "pasta",
        "noodle" to "pasta", "noodles" to "pasta",
        "mashed potato" to "potato", "new potato" to "potato", "red potato" to "potato",
        "baby spinach" to "spinach", "red onion" to "onion", "green onion" to "onion",
        "portobello" to "mushroom", "cremini mushroom" to "mushroom",
        "cherry tomato" to "tomato",
        "red bell pepper" to "bell pepper", "green bell pepper" to "bell pepper",
        "yellow bell pepper" to "bell pepper",
        "yoghurt" to "yogurt", "greek yoghurt" to "greek yogurt",
        "wheat bread" to "bread", "wholemeal bread" to "bread", "toast" to "bread",
        "white bread" to "bread", "sourdough" to "bread",
        "chocolate" to "dark chocolate",
        "extra virgin olive oil" to "olive oil", "virgin olive oil" to "olive oil",
        "flax seed" to "flaxseed", "chia" to "chia seed",
        "brown sugar" to "sugar",
        "espresso" to "coffee"
    )

    /** Primary (canonical) keys — every key satisfies `normalize(key) == key`. */
    val primaryKeys: Set<String> = RAW.mapTo(HashSet()) { it.key }

    /** key (primary or alias) → seed food; resolved once per process. */
    val foods: Map<String, SeedFood> = buildMap {
        for (food in RAW) put(food.key, food)
        for ((alias, target) in ALIASES) {
            val canonical = RAW.firstOrNull { it.key == target }
                ?: error("SeedFoodLibrary alias '$alias' → unknown primary key '$target'")
            put(alias, canonical)
        }
    }

    /** Bundled panel for a normalized food key; null when not in the LUT. */
    fun lookup(normalizedKey: String): SeedFood? = foods[normalizedKey]
}
