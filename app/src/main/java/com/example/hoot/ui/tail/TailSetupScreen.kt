package com.example.hoot.ui.tail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.hoot.data.tail.TailHabit
import com.example.hoot.data.tail.TailSyncState

/**
 * Tail integration setup: choose the Tail app → map the meal habit (e.g.
 * "Food") and the pills text habit (e.g. "Took Pills") → save & first sync.
 * Reached from Settings and auto-prompted on first launch when unconfigured.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TailSetupScreen(
    onDone: () -> Unit,
    viewModel: TailSetupViewModel = viewModel()
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val habits by viewModel.habits.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val mealHabit by viewModel.mealHabit.collectAsStateWithLifecycle()
    val pillsHabit by viewModel.pillsHabit.collectAsStateWithLifecycle()
    val waterHabit by viewModel.waterHabit.collectAsStateWithLifecycle()
    val waterUnitMode by viewModel.waterUnitMode.collectAsStateWithLifecycle()
    val miscHabits by viewModel.miscHabits.collectAsStateWithLifecycle()
    val selectedPackage by viewModel.selectedPackage.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(syncState) {
        val s = syncState
        if (s is TailSyncState.Success && s.mealLogsUnavailable) {
            snackbar.showSnackbar(
                "Tail doesn't expose meal logs yet — syncing text entries. See docs/TAIL_REQUEST.md"
            )
        } else if (s is TailSyncState.Error) {
            snackbar.showSnackbar("Sync failed: ${s.message}")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tail integration") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (step) {
                TailSetupStep.PICK_APP -> AppPickerSection(
                    apps = apps,
                    loading = loading,
                    onSelect = viewModel::selectApp,
                    onRefresh = viewModel::refreshApps
                )

                TailSetupStep.PICK_HABITS -> HabitMappingSection(
                    habits = habits,
                    mealHabit = mealHabit,
                    pillsHabit = pillsHabit,
                    waterHabit = waterHabit,
                    waterUnitMode = waterUnitMode,
                    miscHabits = miscHabits,
                    loading = loading,
                    onMeal = viewModel::setMealHabit,
                    onPills = viewModel::setPillsHabit,
                    onWater = viewModel::setWaterHabit,
                    onWaterUnitMode = viewModel::setWaterUnitMode,
                    onAddMisc = viewModel::addMiscHabit,
                    onRemoveMisc = viewModel::removeMiscHabit,
                    onSave = viewModel::saveMapping,
                    onCancel = { viewModel.refreshApps() }
                )

                TailSetupStep.DONE -> ConnectedSection(
                    config = config,
                    syncState = syncState,
                    onSyncNow = viewModel::syncNow,
                    onDisconnect = viewModel::disconnect
                )
            }
        }
    }
}
