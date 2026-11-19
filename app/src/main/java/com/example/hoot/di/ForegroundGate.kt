package com.example.hoot.di

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground-only execution policy (battery fix, 2026-11-19).
 *
 * Hoot was burning battery in the background: every process start — including
 * cold starts triggered by Tail's habit-log broadcasts, which fire dozens of
 * times per day — spun up the periodic Tail sync loop, the 6-hour auto-export
 * loop and the whole NutritionPipeline self-heal set (full-ledger recompute,
 * LLM resolution drain, recommendation LLM call). A backgrounded app has
 * nothing urgent to do, so this gate makes "am I visible?" the single switch
 * all work loops consult.
 *
 * Tracking is the standard started-activity counter over
 * [Application.ActivityLifecycleCallbacks] — no extra dependency (the
 * ProcessLifecycleOwner artifact is not on the classpath) and precise for a
 * single-activity app. Transitions are delivered on the main thread.
 */
class ForegroundGate : Application.ActivityLifecycleCallbacks {

    private val startedActivities = AtomicInteger(0)

    private val _foreground = MutableStateFlow(false)

    /** True while any Hoot activity is started (i.e. the app is visible). */
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private val listeners = mutableListOf<(Boolean) -> Unit>()

    /** Registers the callbacks; call once from [Application.onCreate]. */
    fun begin(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
    }

    /**
     * Listener for foreground/background transitions. Invoked on the main
     * thread for every 0→1 and 1→0 activity-count transition; if the app is
     * already foregrounded when the listener is registered it fires
     * immediately with `true` (defensive — wiring happens in onCreate before
     * any activity starts, but late wiring must not miss the session).
     */
    fun onTransition(listener: (Boolean) -> Unit) {
        listeners += listener
        if (_foreground.value) listener(true)
    }

    override fun onActivityStarted(activity: Activity) {
        if (startedActivities.incrementAndGet() == 1 && !_foreground.value) {
            _foreground.value = true
            listeners.forEach { it(true) }
        }
    }

    override fun onActivityStopped(activity: Activity) {
        if (startedActivities.decrementAndGet() == 0 && _foreground.value) {
            _foreground.value = false
            listeners.forEach { it(false) }
        }
    }

    // Unused lifecycle callbacks — the counter only needs started/stopped.
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
