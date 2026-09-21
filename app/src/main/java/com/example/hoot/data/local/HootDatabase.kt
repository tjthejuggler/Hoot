package com.example.hoot.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.hoot.data.local.dao.DietaryProfileDao
import com.example.hoot.data.local.dao.FoodDao
import com.example.hoot.data.local.dao.IngredientDao
import com.example.hoot.data.local.dao.IntakeDao
import com.example.hoot.data.local.dao.LookupCacheDao
import com.example.hoot.data.local.dao.MealDao
import com.example.hoot.data.local.dao.NutrientDao
import com.example.hoot.data.local.dao.NutrientGoalDao
import com.example.hoot.data.local.dao.ProfileDao
import com.example.hoot.data.local.dao.RecommendationDao
import com.example.hoot.data.local.dao.ScoreSnapshotDao
import com.example.hoot.data.local.dao.SourceDao
import com.example.hoot.data.local.dao.SupplementDao
import com.example.hoot.data.local.dao.TailConfigDao
import com.example.hoot.data.local.dao.TailEntryDao
import com.example.hoot.data.local.entity.DietaryProfileEntity
import com.example.hoot.data.local.entity.FoodEntity
import com.example.hoot.data.local.entity.FoodNutrientProfileEntity
import com.example.hoot.data.local.entity.IngredientEntity
import com.example.hoot.data.local.entity.LookupCacheEntity
import com.example.hoot.data.local.entity.MealEntity
import com.example.hoot.data.local.entity.NutrientDefinitionEntity
import com.example.hoot.data.local.entity.NutrientGoalEntity
import com.example.hoot.data.local.entity.NutrientIntakeEntity
import com.example.hoot.data.local.entity.RecommendationEntity
import com.example.hoot.data.local.entity.ScoreSnapshotEntity
import com.example.hoot.data.local.entity.SourceEntity
import com.example.hoot.data.local.entity.SupplementEntity
import com.example.hoot.data.local.entity.TailAppConfigEntity
import com.example.hoot.data.local.entity.TailEntryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Hoot's Room database. All schema lives in `data/local/entity/` (see
 * `docs/ARCHITECTURE.md` §3); `nutrient_definitions` is seeded on first
 * creation from [nutrientSeed] via [SeedCallback].
 */
@Database(
    entities = [
        FoodEntity::class,
        IngredientEntity::class,
        MealEntity::class,
        SupplementEntity::class,
        NutrientDefinitionEntity::class,
        NutrientIntakeEntity::class,
        FoodNutrientProfileEntity::class,
        LookupCacheEntity::class,
        SourceEntity::class,
        TailAppConfigEntity::class,
        DietaryProfileEntity::class,
        NutrientGoalEntity::class,
        RecommendationEntity::class,
        ScoreSnapshotEntity::class,
        TailEntryEntity::class
    ],
    // v4: IngredientEntity/SupplementEntity gained `resolveAttempts` (bug fix:
    // permanently-failed items must not re-enqueue on every start). v3 → v4 is
    // a non-destructive additive migration; the destructive fallback stays as
    // a last-resort debug safety net only.
    // v5: tail_app_config gained water/misc habit mapping + cursors; new
    // `tail_entries` table holds water/misc Tail log entries. Additive only.
    // v6: meals gained the Tail-compatible capture structure (summary, kcal,
    // macros, vegan flag, health notes, transcript, photo path). Additive.
    version = 6,
    exportSchema = false
)
abstract class HootDatabase : RoomDatabase() {
    abstract fun mealDao(): MealDao
    abstract fun ingredientDao(): IngredientDao
    abstract fun supplementDao(): SupplementDao
    abstract fun nutrientDao(): NutrientDao
    abstract fun foodDao(): FoodDao
    abstract fun profileDao(): ProfileDao
    abstract fun lookupCacheDao(): LookupCacheDao
    abstract fun sourceDao(): SourceDao
    abstract fun intakeDao(): IntakeDao
    abstract fun tailConfigDao(): TailConfigDao
    abstract fun dietaryProfileDao(): DietaryProfileDao
    abstract fun nutrientGoalDao(): NutrientGoalDao
    abstract fun recommendationDao(): RecommendationDao
    abstract fun scoreSnapshotDao(): ScoreSnapshotDao
    abstract fun tailEntryDao(): TailEntryDao

    companion object {
        private const val DB_NAME = "hoot.db"

        @Volatile
        private var instance: HootDatabase? = null

        /**
         * Builds (once per process) and returns the singleton database.
         * The seed needs a scope because the onCreate callback outlives no
         * caller-provided coroutine context.
         */
        fun build(context: Context, scope: CoroutineScope): HootDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    HootDatabase::class.java,
                    DB_NAME
                )
                    .addCallback(SeedCallback(scope))
                    // v3 → v4 adds `resolveAttempts` to ingredients/supplements
                    // (bug fix: failed items must stop re-enqueueing on restart).
                    // v4 → v5 adds water/misc Tail-habit mapping + `tail_entries`.
                    // v5 → v6 adds the Tail-compatible capture columns on meals.
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    // Debug-only schema: a missed migration rebuilds from the seed.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }

        /** v3 → v4: additive `resolveAttempts` columns; preserves all resolved data. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ingredients ADD COLUMN resolveAttempts INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE supplements ADD COLUMN resolveAttempts INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v4 → v5: water/misc Tail habit mappings. Additive, mirroring the
         * v3 → v4 pattern: four nullable/defaulted columns on the singleton
         * tail_app_config row plus a new `tail_entries` table (raw water and
         * misc log rows). No existing data is touched.
         */
        /**
         * v5 → v6: Tail-compatible capture structure on `meals` (Intake tab).
         * All nullable or defaulted columns; no existing data is touched.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE meals ADD COLUMN summary TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE meals ADD COLUMN calories INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE meals ADD COLUMN proteinGrams REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE meals ADD COLUMN carbsGrams REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE meals ADD COLUMN fatGrams REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE meals ADD COLUMN isVegan INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE meals ADD COLUMN healthNotes TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE meals ADD COLUMN transcript TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE meals ADD COLUMN photoPath TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tail_app_config ADD COLUMN waterHabitName TEXT DEFAULT NULL")
                db.execSQL(
                    "ALTER TABLE tail_app_config ADD COLUMN miscHabitNamesJson TEXT NOT NULL DEFAULT '[]'"
                )
                db.execSQL("ALTER TABLE tail_app_config ADD COLUMN lastWaterSyncAt INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE tail_app_config ADD COLUMN lastMiscSyncAt INTEGER DEFAULT NULL")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tail_entries` (" +
                        "`id` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`habitName` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`day` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`amount` REAL, " +
                        "`unit` TEXT, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tail_entries_day` ON `tail_entries` (`day`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tail_entries_kind` ON `tail_entries` (`kind`)")
            }
        }
    }

    /** Seeds `nutrient_definitions` exactly once, when the schema is created. */
    private class SeedCallback(private val scope: CoroutineScope) : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            scope.launch(Dispatchers.IO) {
                instance?.nutrientDao()?.upsertAll(nutrientSeed())
            }
        }
    }
}
