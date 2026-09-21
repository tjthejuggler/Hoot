package com.example.hoot.ui.tail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hoot.appGraph
import com.example.hoot.data.tail.TailAppInfo
import com.example.hoot.data.tail.TailHabit
import com.example.hoot.data.tail.TailSyncState
import com.example.hoot.data.repository.TailConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One step of the [TailSetupScreen] wizard.
 */
enum class TailSetupStep { PICK_APP, PICK_HABITS, DONE }

/**
 * State machine for the Tail setup wizard + sync controls. All provider I/O
 * runs through [com.example.hoot.data.tail.TailClient] (soft-fail); UI state
 * is plain StateFlow — no magic, phase-4's full settings UI will reuse this.
 */
class TailSetupViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app.appGraph

    val syncState: StateFlow<TailSyncState> = graph.tailSync.syncState

    /** Singleton tail_app_config row (last sync cursors, current mapping). */
    val config = graph.tailConfig.observeTailConfig()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _step = MutableStateFlow(TailSetupStep.PICK_APP)
    val step: StateFlow<TailSetupStep> = _step.asStateFlow()

    private val _apps = MutableStateFlow<List<TailAppInfo>>(emptyList())
    val apps: StateFlow<List<TailAppInfo>> = _apps.asStateFlow()

    private val _habits = MutableStateFlow<List<TailHabit>>(emptyList())
    val habits: StateFlow<List<TailHabit>> = _habits.asStateFlow()

    private val _selectedPackage = MutableStateFlow<String?>(null)
    val selectedPackage: StateFlow<String?> = _selectedPackage.asStateFlow()

    private val _mealHabit = MutableStateFlow<String?>(null)
    val mealHabit: StateFlow<String?> = _mealHabit.asStateFlow()

    private val _pillsHabit = MutableStateFlow<String?>(null)
    val pillsHabit: StateFlow<String?> = _pillsHabit.asStateFlow()

    /** Water habit mapping (v5); null = unmapped. */
    private val _waterHabit = MutableStateFlow<String?>(null)
    val waterHabit: StateFlow<String?> = _waterHabit.asStateFlow()

    /** N miscellaneous habit mappings (v5); add/remove rows in the UI. */
    private val _miscHabits = MutableStateFlow<List<String>>(emptyList())
    val miscHabits: StateFlow<List<String>> = _miscHabits.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refreshApps()
    }

    /** Re-scans for installed Tail-provider apps. */
    fun refreshApps() {
        viewModelScope.launch {
            _loading.value = true
            val found = runCatching { graph.tailClient.listTailApps() }.getOrDefault(emptyList())
            _apps.value = found
            _loading.value = false
            // Preselect the canonical package when only one candidate exists.
            if (found.size == 1) selectApp(found.first().packageName)
        }
    }

    /** Loads habits of the chosen app and moves to the habit-mapping step. */
    fun selectApp(pkg: String) {
        _selectedPackage.value = pkg
        viewModelScope.launch {
            _loading.value = true
            val habits = runCatching { graph.tailClient.listHabits(pkg) }.getOrDefault(emptyList())
            preselectSavedMapping(pkg)
            _habits.value = habits
            _loading.value = false
            if (habits.isEmpty()) {
                _message.value = "No habits returned by $pkg — is the integration enabled in Tail?"
            } else {
                _step.value = TailSetupStep.PICK_HABITS
            }
        }
    }

    /**
     * Re-applies the previously saved habit mapping when re-entering setup for
     * the same Tail package, so changing ONE mapping never silently clears the
     * others and the user sees what is currently configured. No-op on a fresh
     * setup (nothing saved yet) or a different package.
     */
    private suspend fun preselectSavedMapping(pkg: String) {
        val cfg = runCatching { graph.tailConfig.tailConfig() }.getOrNull() ?: return
        if (cfg.mealHabitName == null && cfg.pillsHabitName == null &&
            cfg.waterHabitName == null && cfg.miscHabitNames.isEmpty()
        ) return
        val savedPkg = runCatching { graph.settings.current().tailPackage }.getOrNull().orEmpty()
        if (savedPkg.isNotBlank() && savedPkg != pkg) return
        _mealHabit.value = cfg.mealHabitName
        _pillsHabit.value = cfg.pillsHabitName
        _waterHabit.value = cfg.waterHabitName
        _miscHabits.value = cfg.miscHabitNames
    }

    fun setMealHabit(name: String?) {
        _mealHabit.value = name
    }

    fun setPillsHabit(name: String?) {
        _pillsHabit.value = name
    }

    fun setWaterHabit(name: String?) {
        _waterHabit.value = name
    }

    /** Adds one more miscellaneous habit row (deduped). */
    fun addMiscHabit(name: String?) {
        val n = name?.trim().takeUnless { it.isNullOrEmpty() } ?: return
        if (n !in _miscHabits.value) _miscHabits.value = _miscHabits.value + n
    }

    /** Removes one miscellaneous habit row. */
    fun removeMiscHabit(name: String) {
        _miscHabits.value = _miscHabits.value - name
    }

    /** Persists the mapping (DataStore + tail_app_config; cursors reset → next sync is full backlog). */
    fun saveMapping() {
        val pkg = _selectedPackage.value ?: return
        val meal = _mealHabit.value
        val pills = _pillsHabit.value
        val water = _waterHabit.value
        val misc = _miscHabits.value
        if (meal == null && pills == null && water == null && misc.isEmpty()) {
            _message.value = "Pick at least one habit to sync."
            return
        }
        viewModelScope.launch {
            runCatching {
                graph.settings.saveTailMapping(
                    tailPackage = pkg,
                    mealHabit = meal.orEmpty(),
                    pillsHabit = pills.orEmpty(),
                    waterHabit = water.orEmpty(),
                    miscHabitsJson = org.json.JSONArray(misc).toString()
                )
                graph.tailConfig.saveHabitMapping(meal, pills, water, misc)
            }.onSuccess {
                _step.value = TailSetupStep.DONE
                _message.value = "Saved — running first sync…"
                // Full backlog on first run (cursors were reset), incremental afterwards.
                graph.tailSync.sync()
            }.onFailure {
                _message.value = "Could not save mapping: ${it.message}"
            }
        }
    }

    /** Manual "Sync now". */
    fun syncNow() {
        viewModelScope.launch { graph.tailSync.sync() }
    }

    /** Clears the mapping (keeps synced rows; they are just data). */
    fun disconnect() {
        viewModelScope.launch {
            runCatching {
                graph.settings.saveTailMapping(tailPackage = "", mealHabit = "", pillsHabit = "")
                graph.tailConfig.saveHabitMapping(null, null)
            }
            _mealHabit.value = null
            _pillsHabit.value = null
            _waterHabit.value = null
            _miscHabits.value = emptyList()
            _step.value = TailSetupStep.PICK_APP
            refreshApps()
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
