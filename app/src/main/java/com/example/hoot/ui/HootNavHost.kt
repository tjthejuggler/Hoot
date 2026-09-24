package com.example.hoot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.content.Context
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.hoot.appGraph
import com.example.hoot.ui.history.HistoryScreen
import com.example.hoot.ui.home.HomeScreen
import com.example.hoot.ui.input.AddMealScreen
import com.example.hoot.ui.input.AddSupplementScreen
import com.example.hoot.ui.insights.InsightsScreen
import com.example.hoot.ui.intake.IntakeScreen
import com.example.hoot.ui.library.FoodLibraryScreen
import com.example.hoot.ui.settings.SettingsScreen
import com.example.hoot.ui.tail.TailSetupScreen

/**
 * Type-safe route names for the four top-level destinations (UI overhaul
 * feedback #4): Home is the actionable start tab; Today is kept as a
 * lightweight alias route to the same screen for back-stack compatibility.
 */
enum class HootRoute(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Today),
    INTAKE("Intake", Icons.Filled.Add),
    HISTORY("History", Icons.Filled.History),
    INSIGHTS("Insights", Icons.Filled.BarChart),
    SETTINGS("Settings", Icons.Filled.Settings)
}

/** Kept as an alias of [HootRoute.HOME] so old links/back-stack entries resolve. */
const val ROUTE_TODAY_ALIAS = "Today"

/** Detail/setup routes below the tab bar. */
const val ROUTE_TAIL_SETUP = "tail_setup"
const val ROUTE_ADD_MEAL = "add_meal"
const val ROUTE_ADD_SUPPLEMENT = "add_supplement"
const val ROUTE_FOOD_LIBRARY = "food_library"

/** DataStore flag key: has the user seen the Tail setup prompt? */
const val PREF_TAIL_PROMPT_SHOWN = "tail_setup_prompt_shown"

/**
 * App shell: Material 3 [Scaffold] with a [NavigationBar] hosting the four
 * top-level routes plus the [ROUTE_TAIL_SETUP] sub-route. First launch with
 * no Tail mapping auto-prompts into setup once (the banner offers a standing
 * entry point afterwards).
 */
@Composable
fun HootNavHost(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // One-time auto-prompt on first launch when Tail is not configured yet.
    val context = LocalContext.current
    var tailPromptChecked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!tailPromptChecked) {
            tailPromptChecked = true
            val graph = context.appGraph
            val config = runCatching { graph.tailConfig.tailConfig() }.getOrNull()
            val needsSetup = config == null || (!config.integrationEnabled &&
                config.mealHabitName == null && config.pillsHabitName == null)
            val prefs = context.getSharedPreferences("hoot_ui", Context.MODE_PRIVATE)
            val prompted = prefs.getBoolean(PREF_TAIL_PROMPT_SHOWN, false)
            if (needsSetup && !prompted) {
                prefs.edit().putBoolean(PREF_TAIL_PROMPT_SHOWN, true).apply()
                navController.navigate(ROUTE_TAIL_SETUP) { launchSingleTop = true }
            }
        }
    }

    Scaffold(
        bottomBar = {
            // Hide the tab bar on the setup sub-route (full-screen flow).
            if (currentRoute != ROUTE_TAIL_SETUP) {
                NavigationBar {
                    HootRoute.entries.forEach { route ->
                        NavigationBarItem(
                            selected = currentRoute == route.name,
                            onClick = {
                                navController.navigate(route.name) {
                                    // Single top-level copy of each destination;
                                    // Back from a tab exits the app.
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(route.icon, contentDescription = route.label) },
                            label = { Text(route.label) }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            // "+ Add" now jumps straight to the Intake tab (text/photo/voice
            // composer) — the add_meal/add_supplement routes stay reachable
            // from Intake's supplement quick-mode and remain valid routes.
            if (currentRoute == HootRoute.HOME.name || currentRoute == ROUTE_TODAY_ALIAS) {
                FloatingActionButton(onClick = {
                    navController.navigate(HootRoute.INTAKE.name) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }) {
                    Icon(Icons.Filled.Add, contentDescription = "Open Intake")
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = HootRoute.HOME.name,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(HootRoute.HOME.name) {
                HomeScreen(
                    onOpenHistory = { navController.navigate(HootRoute.HISTORY.name) { launchSingleTop = true } },
                    onOpenInsights = { navController.navigate(HootRoute.INSIGHTS.name) { launchSingleTop = true } },
                    onOpenTailSetup = { navController.navigate(ROUTE_TAIL_SETUP) { launchSingleTop = true } }
                )
            }
            // Legacy alias: same screen as HOME, so old back-stack entries work.
            composable(ROUTE_TODAY_ALIAS) {
                HomeScreen(
                    onOpenHistory = { navController.navigate(HootRoute.HISTORY.name) { launchSingleTop = true } },
                    onOpenInsights = { navController.navigate(HootRoute.INSIGHTS.name) { launchSingleTop = true } },
                    onOpenTailSetup = { navController.navigate(ROUTE_TAIL_SETUP) { launchSingleTop = true } }
                )
            }
            composable(HootRoute.INTAKE.name) { IntakeScreen() }
            composable(HootRoute.HISTORY.name) { HistoryScreen() }
            composable(HootRoute.INSIGHTS.name) {
                InsightsScreen(
                    onOpenHistory = { navController.navigate(HootRoute.HISTORY.name) { launchSingleTop = true } }
                )
            }
            composable(HootRoute.SETTINGS.name) {
                SettingsScreen(
                    onOpenTailSetup = { navController.navigate(ROUTE_TAIL_SETUP) },
                    onOpenFoodLibrary = { navController.navigate(ROUTE_FOOD_LIBRARY) }
                )
            }
            composable(ROUTE_FOOD_LIBRARY) {
                FoodLibraryScreen(onBack = { navController.popBackStack() })
            }
            composable(ROUTE_TAIL_SETUP) {
                TailSetupScreen(onDone = { navController.popBackStack() })
            }
            composable(ROUTE_ADD_MEAL) {
                AddMealScreen(onDone = { navController.popBackStack() })
            }
            composable(ROUTE_ADD_SUPPLEMENT) {
                AddSupplementScreen(onDone = { navController.popBackStack() })
            }
        }
    }
}

/** Shared placeholder (kept for future sub-routes). */
@Composable
fun PlaceholderScreen(route: HootRoute) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = route.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(48.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("${route.label} — coming in a later phase", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Phase 1 foundation is in place: Room schema, DataStore settings, repositories and DI graph.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
