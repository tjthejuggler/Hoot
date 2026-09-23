package com.example.hoot.data.tail

/**
 * UI-visible progress of the Tail sync pipeline.
 */
sealed interface TailSyncState {
    data object Idle : TailSyncState

    /** [firstRun] = full-backlog pass (no persisted cursor yet). */
    data class Syncing(val firstRun: Boolean) : TailSyncState

    /**
     * [mealsInserted]/[supplementsInserted] count GENUINELY-NEW rows only —
     * unchanged re-served upserts are excluded so the post-sync resolver
     * kick in [com.example.hoot.di.AppGraph] stays silent when nothing new
     * arrived (restart re-analysis fix, 2026-09).
     */
    data class Success(
        val mealsInserted: Int,
        val supplementsInserted: Int,
        val full: Boolean,
        /** True when Tail does not expose meal logs yet (see docs/TAIL_REQUEST.md). */
        val mealLogsUnavailable: Boolean,
        /** v5: water/misc habit rows written (0 when those habits are unmapped). */
        val waterInserted: Int = 0,
        val miscInserted: Int = 0
    ) : TailSyncState

    data class Error(val message: String) : TailSyncState
}

/** What kicked off a sync run (affects logging and broadcast replies). */
enum class TailSyncTrigger { MANUAL, STARTUP, PERIODIC, BROADCAST }
