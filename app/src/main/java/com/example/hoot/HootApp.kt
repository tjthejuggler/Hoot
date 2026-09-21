package com.example.hoot

import android.app.Application
import android.content.Context
import com.example.hoot.di.AppGraph

class HootApp : Application() {
    lateinit var appGraph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        appGraph = AppGraph(this)
        // Coroutine-based periodic Tail sync (honors syncEnabled /
        // syncIntervalMinutes DataStore settings; no-ops when unconfigured).
        appGraph.tailSync.startPeriodicSync()
    }
}

/** Convenience accessor for Composables/ViewModels. */
val Context.appGraph: AppGraph
    get() = (applicationContext as HootApp).appGraph
