package com.example.hoot.data.repository

import com.example.hoot.data.local.dao.DietaryProfileDao
import com.example.hoot.data.local.dao.TailConfigDao
import com.example.hoot.data.local.entity.DietaryProfileEntity
import com.example.hoot.data.local.entity.TailAppConfigEntity
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray

/**
 * Singleton-row config stores: Tail integration config + dietary profile.
 * These hold sync cursors and recommender filters that must be transactional
 * with the Room data they govern (unlike the DataStore-backed user prefs).
 */
class TailConfigRepository(
    private val tailConfigDao: TailConfigDao,
    private val dietaryProfileDao: DietaryProfileDao
) {
    // ---- Tail integration -------------------------------------------
    fun observeTailConfig(): Flow<TailAppConfigEntity?> = tailConfigDao.observe()

    suspend fun tailConfig(): TailAppConfigEntity? = tailConfigDao.get()

    suspend fun saveTailConfig(config: TailAppConfigEntity) =
        tailConfigDao.upsert(config.copy(id = SINGLETON_ID))

    /**
     * One-time backfill hook: clears the pills cursor so the next sync
     * re-pulls the full pills backlog (multi-item split re-ingestion).
     * No-op when Tail has never been configured.
     */
    suspend fun resetPillsCursor() {
        val current = tailConfigDao.get() ?: return
        tailConfigDao.upsert(current.copy(lastPillsSyncAt = null))
    }

    /** Advance the incremental-sync cursor after a successful backlog pass. */
    suspend fun updateSyncCursor(
        lastMealSyncAt: Long?,
        lastPillsSyncAt: Long?,
        lastWaterSyncAt: Long? = null,
        lastMiscSyncAt: Long? = null
    ) {
        val current = tailConfigDao.get() ?: TailAppConfigEntity(id = SINGLETON_ID)
        tailConfigDao.upsert(
            current.copy(
                lastMealSyncAt = lastMealSyncAt ?: current.lastMealSyncAt,
                lastPillsSyncAt = lastPillsSyncAt ?: current.lastPillsSyncAt,
                lastWaterSyncAt = lastWaterSyncAt ?: current.lastWaterSyncAt,
                lastMiscSyncAt = lastMiscSyncAt ?: current.lastMiscSyncAt
            )
        )
    }

    /**
     * Persist the habit mapping chosen in Tail setup; resets sync cursors so
     * the next sync runs a full backlog pass. Water/misc mappings are new in
     * v5 and optional — existing meal/pills flows are unchanged.
     */
    suspend fun saveHabitMapping(
        mealHabit: String?,
        pillsHabit: String?,
        waterHabit: String? = null,
        miscHabitNames: List<String> = emptyList()
    ) = tailConfigDao.upsert(
        (tailConfigDao.get() ?: TailAppConfigEntity(id = SINGLETON_ID)).copy(
            integrationEnabled = mealHabit != null || pillsHabit != null ||
                waterHabit != null || miscHabitNames.isNotEmpty(),
            mealHabitName = mealHabit?.takeIf { it.isNotBlank() },
            pillsHabitName = pillsHabit?.takeIf { it.isNotBlank() },
            waterHabitName = waterHabit?.takeIf { it.isNotBlank() },
            miscHabitNamesJson = JSONArray(miscHabitNames).toString(),
            lastMealSyncAt = null,
            lastPillsSyncAt = null,
            lastWaterSyncAt = null,
            lastMiscSyncAt = null,
            knownEntryIdsJson = "[]"
        )
    )

    /** Adds entry ids to the dedup set (compact JSON array, newest appended; window capped to keep the row small). */
    suspend fun rememberEntryIds(newIds: Collection<String>) {
        if (newIds.isEmpty()) return
        val current = tailConfigDao.get() ?: TailAppConfigEntity(id = SINGLETON_ID)
        // Rebuild the dedup set without duplicates (JSONArray is re-parsed defensively).
        val seen = LinkedHashSet<String>()
        val existing = runCatching { JSONArray(current.knownEntryIdsJson) }.getOrDefault(JSONArray())
        for (i in 0 until existing.length()) {
            val s = existing.optString(i)
            if (s.isNotEmpty()) seen += s
        }
        seen += newIds
        // Cap the window: dedup primarily relies on stable PK upserts; this set
        // only guards legacy timestamp-key collisions for recent entries.
        val window = seen.toList().takeLast(KNOWN_IDS_WINDOW)
        tailConfigDao.upsert(current.copy(knownEntryIdsJson = JSONArray(window).toString()))
    }

    suspend fun knownEntryIds(): Set<String> {
        val current = tailConfigDao.get() ?: return emptySet()
        return runCatching {
            val arr = JSONArray(current.knownEntryIdsJson)
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotEmpty() } }.toSet()
        }.getOrDefault(emptySet())
    }

    // ---- Dietary profile ----------------------------------------------
    fun observeDietaryProfile(): Flow<DietaryProfileEntity?> = dietaryProfileDao.observe()

    suspend fun dietaryProfile(): DietaryProfileEntity? = dietaryProfileDao.get()

    suspend fun saveDietaryProfile(profile: DietaryProfileEntity) =
        dietaryProfileDao.upsert(profile.copy(id = SINGLETON_ID))

    companion object {
        const val SINGLETON_ID = 1

        /** Max ids kept in the compact dedup window (PK upserts are the primary dedup). */
        const val KNOWN_IDS_WINDOW = 512
    }
}
