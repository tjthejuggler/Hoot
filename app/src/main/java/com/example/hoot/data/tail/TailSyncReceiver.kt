package com.example.hoot.data.tail

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.hoot.appGraph
import kotlinx.coroutines.launch

/**
 * Receives Tail's push broadcasts (protocol v5 pattern: the payload carries
 * the *event* timestamp in [EXTRA_TIMESTAMP], not the receive time — Hoot
 * must use it verbatim for cursors/days).
 *
 * Tail currently has no outbound entry broadcast (that is R4 in
 * `docs/TAIL_REQUEST.md`); this receiver is the future-proof hook. It
 * declares the expected actions and handles them defensively:
 *
 *  - `com.example.tail.ACTION_ENTRY_ADDED` (proposed R4) — extras
 *    `EXTRA_HABIT_ID`, optional `EXTRA_ENTRY_ID`, optional
 *    [EXTRA_TIMESTAMP] (Long, epoch millis).
 *  - Any of Tail's integration actions carrying [EXTRA_TIMES_JSON]
 *    (`{"yyyy-MM-dd": ["HH:mm:ss", …]}`) — treated as a generic
 *    "data changed" nudge; we re-pull incrementally.
 *
 * Registration is manifest-declared, package-targeted semantics preserved by
 * the signature permission: only same-keystore apps (i.e. Tail) can deliver
 * to it, so `exported=true` + `permission` guard is the same trust level as
 * Inuit's send-side `sendBroadcast(intent, PERMISSION_TAIL)`.
 */
class TailSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return

        // v5 protocol: event time beats receive time.
        val eventTs = intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())

        // Nudge the pipeline on the app scope; goAsync window is enough for
        // the enqueue, the sync itself lives on the coroutine.
        val pending = goAsync()
        val graph = runCatching { context.appGraph }.getOrNull()
        if (graph == null) {
            pending.finish()
            return
        }
        graph.appScope.launch {
            try {
                android.util.Log.d(
                    "TailSync",
                    "broadcast $action habit=${intent.getStringExtra(EXTRA_HABIT_ID)} " +
                        "entry=${intent.getStringExtra(EXTRA_ENTRY_ID)} ts=$eventTs"
                )
                graph.tailSync.sync(TailSyncTrigger.BROADCAST)
            } catch (e: Exception) {
                android.util.Log.w("TailSync", "broadcast-triggered sync failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Proposed R4 write-broadcast action from `docs/TAIL_REQUEST.md`. */
        const val ACTION_ENTRY_ADDED = "com.example.tail.ACTION_ENTRY_ADDED"

        /** Fallback: treat any Tail integration action as a change nudge. */
        const val ACTION_INCREMENT = "com.example.tail.ACTION_INCREMENT_HABIT"
        const val ACTION_SET_HABIT_VALUES = "com.example.tail.ACTION_SET_HABIT_VALUES"

        /** Protocol v5 event-timestamp extra (Long, epoch millis). */
        const val EXTRA_TIMESTAMP = "EXTRA_TIMESTAMP"
        const val EXTRA_HABIT_ID = "EXTRA_HABIT_ID"
        const val EXTRA_ENTRY_ID = "EXTRA_ENTRY_ID"

        /** v5 per-date times payload (`{"yyyy-MM-dd": ["HH:mm:ss", …]}`). */
        const val EXTRA_TIMES_JSON = "EXTRA_TIMES_JSON"

        val HANDLED_ACTIONS = setOf(
            ACTION_ENTRY_ADDED, ACTION_INCREMENT, ACTION_SET_HABIT_VALUES
        )
    }
}
