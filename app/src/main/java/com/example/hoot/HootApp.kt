package com.example.hoot

import android.app.Application
import android.content.Context
import com.example.hoot.di.AppGraph
import kotlinx.coroutines.launch

class HootApp : Application() {
    lateinit var appGraph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        appGraph = AppGraph(this)

        // ── Foreground-only execution policy (battery fix, 2026-11-19) ─────
        // Hoot used to start two infinite work loops at EVERY process start —
        // including the dozens of background cold starts per day caused by
        // Tail's habit-log broadcasts — plus the full NutritionPipeline
        // self-heal set (full-ledger recompute of every day, LLM resolution
        // drain, recommendation LLM call). A backgrounded nutrition tracker
        // has nothing urgent to do, so ALL of it is now gated on visibility:
        //
        //  1. Nothing runs while the app is created in the background; the
        //     process builds its graph, registers the gate and goes quiet.
        //  2. On entering the foreground: run the deferred Tail sync ONLY if
        //     a broadcast nudge piled up or the last pass is stale, kick the
        //     periodic sync loop (which exits by itself when we leave the
        //     foreground), attempt the daily TailCue export (once per
        //     calendar day), and arm the nutrition pipeline self-heals once
        //     per process (see AppGraph init).
        //  3. On leaving the foreground: stop the periodic loop; any Tail
        //     broadcast that arrives afterwards only sets a cheap persisted
        //     pending flag consumed by the next foreground session.
        appGraph.foregroundGate.begin(this)
        appGraph.foregroundGate.onTransition { foreground ->
            if (foreground) {
                appGraph.appScope.launch {
                    appGraph.tailSync.onForeground()
                }
                appGraph.tailSync.startPeriodicSync { appGraph.foregroundGate.foreground.value }
                appGraph.autoExport.runOnForeground()
            } else {
                appGraph.tailSync.stopPeriodicSync()
            }
        }
    }
}

/** Convenience accessor for Composables/ViewModels. */
val Context.appGraph: AppGraph
    get() = (applicationContext as HootApp).appGraph
