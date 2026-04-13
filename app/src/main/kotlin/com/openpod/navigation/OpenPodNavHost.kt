package com.openpod.navigation

import com.openpod.BuildConfig
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.openpod.feature.alerts.AlertsScreen
import com.openpod.feature.basal.BasalScreen
import com.openpod.feature.bolus.BolusScreen
import com.openpod.feature.dashboard.DashboardScreen
import com.openpod.feature.history.HistoryScreen
import com.openpod.feature.onboarding.navigation.ONBOARDING_GRAPH_ROUTE
import com.openpod.feature.onboarding.navigation.onboardingNavGraph
import com.openpod.feature.pairing.PairingScreen
import com.openpod.feature.settings.SettingsScreen
import timber.log.Timber

/**
 * Top-level navigation destinations shown in the bottom navigation bar.
 *
 * These routes are only visible after onboarding is complete.
 */
enum class TopLevelRoute(val route: String, val label: String, val icon: ImageVector) {
    Dashboard("dashboard", "Dashboard", Icons.Default.Home),
    History("history", "History", Icons.Default.History),
    Settings("settings", "Settings", Icons.Default.Settings),
}

/**
 * Root navigation host for the OpenPod app.
 *
 * Determines the start destination based on onboarding state:
 * - First launch: starts at onboarding graph
 * - Returning user: starts at dashboard
 *
 * ## Navigation flow
 *
 * ```
 * App launch
 *   ├─ onboarding incomplete → Onboarding graph (Welcome → ... → Ready)
 *   │                            └─ "Pair Your First Pod" → Pairing wizard
 *   │                                                         └─ Complete → Dashboard
 *   └─ onboarding complete → Dashboard
 *        ├─ Bolus FAB → Bolus flow
 *        ├─ Pod status → Pod status / Pairing
 *        ├─ History tab
 *        └─ Settings tab
 * ```
 */
@Composable
fun OpenPodNavHost(
    viewModel: NavigationViewModel = hiltViewModel(),
) {
    val isOnboardingComplete by viewModel.isOnboardingComplete.collectAsState()

    // Show nothing until we know the onboarding state (avoids flash of wrong screen)
    if (isOnboardingComplete == null) return

    // Compute start destination ONCE based on the initial onboarding state.
    // This must not react to later preference changes (e.g., when the Ready screen
    // sets onboarding_complete=true before navigating to pairing). If it did, the
    // NavHost would recompose with startDestination=dashboard, wiping the pairing route.
    val startDestination = remember(Unit) {
        if (isOnboardingComplete == true) {
            TopLevelRoute.Dashboard.route
        } else {
            ONBOARDING_GRAPH_ROUTE
        }
    }

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val topLevelRoutes = TopLevelRoute.entries

    // Bottom nav is only visible on top-level destinations (not during onboarding or pairing)
    val isTopLevel = topLevelRoutes.any { route ->
        currentDestination?.hierarchy?.any { it.route == route.route } == true
    }

    Scaffold(
        bottomBar = {
            if (isTopLevel) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 2.dp,
                ) {
                    topLevelRoutes.forEach { route ->
                        NavigationBarItem(
                            icon = { Icon(route.icon, contentDescription = route.label) },
                            label = { Text(route.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == route.route } == true,
                            onClick = {
                                navController.navigate(route.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        ) {
            // ── Onboarding graph ────────────────────────────────────────
            onboardingNavGraph(
                navController = navController,
                onOnboardingComplete = {
                    Timber.i("Onboarding complete — navigating to pairing wizard")
                    navController.navigate("pairing") {
                        popUpTo(ONBOARDING_GRAPH_ROUTE) { inclusive = true }
                    }
                },
            )

            // ── Main app destinations ───────────────────────────────────
            composable(TopLevelRoute.Dashboard.route) {
                DashboardScreen(
                    onNavigateToBolus = { navController.navigate("bolus") },
                    onNavigateToPairing = { navController.navigate("pairing") },
                )
            }

            composable(TopLevelRoute.History.route) {
                HistoryScreen()
            }

            composable(TopLevelRoute.Settings.route) {
                SettingsScreen(
                    appDataResetter = viewModel.appDataResetter,
                    isDebugBuild = BuildConfig.DEBUG,
                    onNavigateToPairing = { navController.navigate("pairing") },
                )
            }

            composable("bolus") {
                BolusScreen(onBack = { navController.popBackStack() })
            }

            composable("basal") {
                BasalScreen(onBack = { navController.popBackStack() })
            }

            composable("pairing") {
                PairingScreen(
                    onComplete = {
                        Timber.i("Pod pairing complete — navigating to dashboard")
                        navController.navigate(TopLevelRoute.Dashboard.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                inclusive = true
                            }
                        }
                    },
                    onCancel = {
                        Timber.i("Pod pairing cancelled")
                        if (!navController.popBackStack()) {
                            // If there's nothing to pop back to (first launch), go to dashboard
                            navController.navigate(TopLevelRoute.Dashboard.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    inclusive = true
                                }
                            }
                        }
                    },
                )
            }

            composable("alerts") {
                AlertsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
